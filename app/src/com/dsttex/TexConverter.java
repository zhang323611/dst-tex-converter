package com.dsttex;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.app.ProgressDialog;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class TexConverter extends Activity {
    private TextView pathView, statusView;
    private ListView listView;
    private LinearLayout actionBar;
    private Button btnToggleAll;
    private File curDir;
    private boolean engineReady = false;
    private final List<File> entries = new ArrayList<>();
    private boolean multiSelect = false;
    private String blockSize = "8x8";
    private String quality = "medium";
    private String convertMode = "auto"; // auto / png2tex / tex2png / dxt2astc
    private SharedPreferences prefs;
    private String highlightName = null; // 搜索跳转后高亮的文件名

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("texconv", MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // Toolbar
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(16, 10, 8, 10);
        toolbar.setBackgroundColor(0xFF2D5F8A);
        TextView title = new TextView(this);
        title.setText("纹理转换器");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(18);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button btnMenu = new Button(this);
        btnMenu.setText("⋮");
        btnMenu.setTextColor(0xFFFFFFFF);
        btnMenu.setBackgroundColor(0x00000000);
        btnMenu.setOnClickListener(v -> showMenu(v));
        toolbar.addView(btnMenu);
        root.addView(toolbar);

        // 路径栏
        pathView = new TextView(this);
        pathView.setTextSize(12);
        pathView.setPadding(12, 6, 12, 6);
        pathView.setBackgroundColor(0xFFE8F0F8);
        pathView.setTextColor(0xFF336699);
        pathView.setOnClickListener(v -> upDir());
        root.addView(pathView);

        // 文件列表
        listView = new ListView(this);
        listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        listView.setDividerHeight(1);
        listView.setOnItemClickListener(this::onItemClick);
        listView.setOnItemLongClickListener((p, v, pos, id) -> { enterMultiSelect(pos); return true; });
        root.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1));

        // 底部操作栏
        actionBar = new LinearLayout(this);
        actionBar.setOrientation(LinearLayout.HORIZONTAL);
        actionBar.setGravity(Gravity.CENTER);
        actionBar.setPadding(4, 6, 4, 6);
        actionBar.setBackgroundColor(0xFFF0F0F0);
        btnToggleAll = new Button(this);
        btnToggleAll.setText("全选");
        btnToggleAll.setOnClickListener(v -> toggleAll());
        actionBar.addView(btnToggleAll);
        actionBar.addView(smallBtn("转换", v -> convertSelected()));
        actionBar.addView(smallBtn("重命名", v -> renameSelected()));
        actionBar.addView(smallBtn("删除", v -> deleteSelected()));
        actionBar.addView(smallBtn("取消", v -> exitMultiSelect()));
        actionBar.setVisibility(View.GONE);
        root.addView(actionBar);

        // 状态栏
        statusView = new TextView(this);
        statusView.setTextSize(12);
        statusView.setPadding(12, 6, 12, 8);
        root.addView(statusView);

        setContentView(root);

        // 请求存储权限
        if (android.os.Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 200);
        }
        // Android 11+ 引导全文件访问
        if (android.os.Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) {
            new AlertDialog.Builder(this)
                .setTitle("需要存储权限")
                .setMessage("为浏览全部文件，请授予「所有文件访问」权限")
                .setPositiveButton("去授权", (d, w) -> startActivity(
                    new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()))))
                .setNegativeButton("取消", null).show();
        }

        File start = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (start == null || !start.exists()) start = getExternalFilesDir(null);
        browse(start != null ? start : getFilesDir());

        try {
            System.loadLibrary("astcenc");
            System.loadLibrary("tex2png");
            engineReady = true;
        } catch (Throwable e) {
            toast("引擎加载失败: " + e.getMessage());
        }
    }

    // JNI 原生方法
    private static native int nativeAstcenc(String[] args);
    private static native int nativeTex2png(String[] args);

    private Button smallBtn(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(l);
        return b;
    }

    // ---------- 菜单 ----------
    private void showMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add("转换模式设置");
        popup.getMenu().add("搜索文件");
        popup.getMenu().add("书签");
        popup.getMenu().add("选目录");
        popup.getMenu().add("关于");
        popup.setOnMenuItemClickListener(item -> {
            String t = item.getTitle().toString();
            if (t.equals("转换模式设置")) showSettings();
            else if (t.equals("搜索文件")) showSearch();
            else if (t.equals("书签")) showBookmarks();
            else if (t.equals("选目录")) pickDir();
            else if (t.equals("关于")) showAbout();
            return true;
        });
        popup.show();
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
            .setTitle("纹理转换器")
            .setMessage("饥荒手机版纹理转换工具\n\n支持：\n· DXT1/3/5 → ASTC(手机版)\n· PNG → ASTC\n· ASTC → PNG\n· zip 内批量转换\n\nASTC 8x8(2bpp) 为手机版标准格式，占用约为 RGBA 的 1/16。")
            .setPositiveButton("确定", null).show();
    }

    // ---------- 设置 ----------
    private void showSettings() {
        final String[] modeVals = {"auto", "png2tex", "tex2png", "dxt2astc"};
        final String[] modeLabels = {"自动检测（推荐）", "PNG → TEX(ASTC)", "TEX → PNG", "TEX(DXT/RGBA) → TEX(ASTC)"};
        final String[] sizeVals = {"4x4", "6x6", "8x8", "10x10", "12x12"};
        final String[] sizeLabels = {"4x4 — 高质量 8bpp", "6x6 — 均衡 3.6bpp", "8x8 — 手机版标准 2bpp", "10x10 — 1.28bpp", "12x12 — 0.89bpp"};
        final String[] qualVals = {"fast", "medium", "thorough", "exhaustive"};
        final String[] qualLabels = {"fast — 最快", "medium — 推荐", "thorough — 较慢", "exhaustive — 极慢"};

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(24, 12, 24, 12);

        // 转换方向
        TextView t0 = new TextView(this);
        t0.setText("转换方式");
        t0.setTextSize(14);
        panel.addView(t0);
        final RadioGroup rgMode = new RadioGroup(this);
        int modeIdx = 0;
        for (int i = 0; i < modeVals.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(modeLabels[i]);
            rb.setId(i);
            if (modeVals[i].equals(convertMode)) { rb.setChecked(true); modeIdx = i; }
            rgMode.addView(rb);
        }
        panel.addView(rgMode);

        // 块大小
        TextView t1 = new TextView(this);
        t1.setText("ASTC 块大小（决定压缩率/体积）");
        t1.setTextSize(14);
        t1.setPadding(0, 12, 0, 0);
        panel.addView(t1);
        final RadioGroup rgSize = new RadioGroup(this);
        for (int i = 0; i < sizeVals.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(sizeLabels[i]);
            rb.setId(i);
            if (sizeVals[i].equals(blockSize)) rb.setChecked(true);
            rgSize.addView(rb);
        }
        panel.addView(rgSize);

        // 质量
        TextView t2 = new TextView(this);
        t2.setText("压缩质量");
        t2.setTextSize(14);
        t2.setPadding(0, 12, 0, 0);
        panel.addView(t2);
        final RadioGroup rgQual = new RadioGroup(this);
        for (int i = 0; i < qualVals.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(qualLabels[i]);
            rb.setId(i);
            if (qualVals[i].equals(quality)) rb.setChecked(true);
            rgQual.addView(rb);
        }
        panel.addView(rgQual);

        new AlertDialog.Builder(this)
            .setTitle("转换模式设置")
            .setView(panel)
            .setPositiveButton("确定", (d, w) -> {
                int m = rgMode.getCheckedRadioButtonId();
                int s = rgSize.getCheckedRadioButtonId();
                int q = rgQual.getCheckedRadioButtonId();
                if (m >= 0 && m < modeVals.length) convertMode = modeVals[m];
                if (s >= 0 && s < sizeVals.length) blockSize = sizeVals[s];
                if (q >= 0 && q < qualVals.length) quality = qualVals[q];
                updateStatus();
            })
            .setNegativeButton("取消", null).show();
    }

    // ---------- 书签 ----------
    private void showBookmarks() {
        Set<String> marks = prefs.getStringSet("bookmarks", new HashSet<>());
        if (marks.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("书签")
                .setMessage("长按目录可添加书签？\n\n当前无书签。点「确定」把当前目录加入书签。")
                .setPositiveButton("加当前目录", (d, w) -> addBookmark(curDir))
                .setNegativeButton("取消", null).show();
            return;
        }
        final List<String> list = new ArrayList<>(marks);
        java.util.Collections.sort(list);
        new AlertDialog.Builder(this)
            .setTitle("书签（点击跳转，长按删除）")
            .setItems(list.toArray(new String[0]), (d, i) -> {
                File f = new File(list.get(i));
                if (f.isDirectory()) browse(f);
                else toast("目录不存在");
            })
            .setPositiveButton("加当前目录", (d, w) -> addBookmark(curDir))
            .setNegativeButton("取消", null).show();
    }

    private void addBookmark(File dir) {
        if (dir == null) return;
        Set<String> marks = new HashSet<>(prefs.getStringSet("bookmarks", new HashSet<>()));
        marks.add(dir.getAbsolutePath());
        prefs.edit().putStringSet("bookmarks", marks).apply();
        toast("已加书签: " + dir.getName());
    }

    // ---------- 搜索 ----------
    private void showSearch() {
        EditText input = new EditText(this);
        input.setHint("输入文件名关键词");
        input.setSingleLine(true);
        new AlertDialog.Builder(this)
            .setTitle("搜索文件（当前目录及子目录）")
            .setView(input)
            .setPositiveButton("搜索", (d, w) -> {
                String kw = input.getText().toString().trim();
                if (kw.isEmpty()) { toast("请输入关键词"); return; }
                doSearch(kw);
            })
            .setNegativeButton("取消", null).show();
    }

    private void doSearch(String kw) {
        final List<File> found = new ArrayList<>();
        searchRec(curDir, kw.toLowerCase(), found, 500);
        if (found.isEmpty()) { toast("未找到匹配文件"); return; }
        List<String> names = new ArrayList<>();
        for (File f : found) names.add(f.getName() + "\n  " + f.getParent());
        new AlertDialog.Builder(this)
            .setTitle("找到 " + found.size() + " 个")
            .setItems(names.toArray(new String[0]), (d, i) -> {
                File f = found.get(i);
                highlightName = f.getName();
                browse(f.getParentFile());
                for (int j = 0; j < entries.size(); j++)
                    if (entries.get(j).getName().equals(highlightName)) {
                        listView.setItemChecked(j, true);
                        listView.smoothScrollToPosition(j);
                    }
            })
            .setPositiveButton("确定", null).show();
    }

    private void searchRec(File dir, String kw, List<File> out, int limit) {
        if (dir == null || out.size() >= limit) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (out.size() >= limit) return;
            if (f.isDirectory()) searchRec(f, kw, out, limit);
            else if (f.getName().toLowerCase().contains(kw)) out.add(f);
        }
    }

    // ---------- 目录选择 ----------
    private void pickDir() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        startActivityForResult(i, 100);
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (result == RESULT_OK && data != null && req == 100) {
            String path = safToPath(data.getData());
            if (path != null) browse(new File(path));
            else toast("无法解析该目录");
        }
    }

    private String safToPath(Uri uri) {
        String s = uri.toString();
        int i = s.indexOf("primary:");
        if (i < 0) return null;
        String sub = s.substring(i + "primary:".length());
        if (sub.isEmpty()) return "/sdcard";
        return "/sdcard/" + sub;
    }

    // ---------- 多选 ----------
    private void enterMultiSelect(int pos) {
        multiSelect = true;
        listView.setItemChecked(pos, true);
        updateActionBar();
    }

    private void exitMultiSelect() {
        multiSelect = false;
        actionBar.setVisibility(View.GONE);
        listView.clearChoices();
        updateStatus();
    }

    private void toggleAll() {
        boolean all = listView.getCheckedItemCount() < entries.size();
        for (int i = 0; i < entries.size(); i++) listView.setItemChecked(i, all);
        updateActionBar();
    }

    private void updateActionBar() {
        int checked = listView.getCheckedItemCount();
        actionBar.setVisibility(multiSelect && checked > 0 ? View.VISIBLE : View.GONE);
        btnToggleAll.setText(checked >= entries.size() ? "反选" : "全选");
    }

    @Override
    public void onBackPressed() {
        if (multiSelect) {
            exitMultiSelect();
        } else if (curDir != null && curDir.getParentFile() != null) {
            upDir();
        } else {
            super.onBackPressed();
        }
    }

    private List<File> getSelected() {
        List<File> sel = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++)
            if (listView.isItemChecked(i) && !entries.get(i).isDirectory()) sel.add(entries.get(i));
        return sel;
    }

    private void renameSelected() {
        List<File> sel = getSelected();
        if (sel.size() != 1) { toast("请只勾选一个文件重命名"); return; }
        File f = sel.get(0);
        EditText input = new EditText(this);
        input.setText(f.getName());
        input.setSelection(0, f.getName().lastIndexOf('.') > 0 ? f.getName().lastIndexOf('.') : f.getName().length());
        new AlertDialog.Builder(this)
            .setTitle("重命名")
            .setView(input)
            .setPositiveButton("确定", (d, w) -> {
                String nn = input.getText().toString().trim();
                if (!nn.isEmpty() && !nn.equals(f.getName())) {
                    if (f.renameTo(new File(f.getParent(), nn))) toast("已重命名");
                    else toast("重命名失败");
                    browse(curDir);
                }
            })
            .setNegativeButton("取消", null).show();
    }

    private void deleteSelected() {
        final List<File> sel = getSelected();
        if (sel.isEmpty()) { toast("请先勾选要删除的文件"); return; }
        new AlertDialog.Builder(this)
            .setTitle("删除 " + sel.size() + " 个文件？")
            .setMessage("删除后不可恢复")
            .setPositiveButton("删除", (d, w) -> {
                int ok = 0;
                for (File f : sel) if (f.delete()) ok++;
                toast("已删除 " + ok + " 个");
                browse(curDir);
            })
            .setNegativeButton("取消", null).show();
    }

    // ---------- 文件浏览 ----------
    private void browse(File dir) {
        try {
            if (dir == null || !dir.isDirectory()) { toast("无法访问目录"); return; }
            curDir = dir;
            pathView.setText("📂 " + dir.getAbsolutePath() + "  (点此返回上级)");
            entries.clear();
            File[] fs = dir.listFiles();
            if (fs != null) {
                java.util.Arrays.sort(fs, (a, b) -> {
                    if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                    return a.getName().compareToIgnoreCase(b.getName());
                });
                for (File f : fs) entries.add(f);
            }
            listView.setAdapter(new FileAdapter());
            listView.clearChoices();
            updateStatus();
        } catch (Exception e) {
            toast("浏览失败: " + e.getMessage());
        }
    }

    private void onItemClick(AdapterView<?> p, View v, int pos, long id) {
        File f = entries.get(pos);
        if (multiSelect) {
            listView.setItemChecked(pos, !listView.isItemChecked(pos));
            if (listView.getCheckedItemCount() == 0) {
                exitMultiSelect(); // 取消最后一个选中时自动退出多选
            } else {
                updateActionBar();
            }
        } else if (f.isDirectory()) {
            browse(f);
        } else if (f.getName().toLowerCase().endsWith(".zip")) {
            openZip(f);
        } else {
            toast("长按进入多选后转换。文件: " + f.getName());
        }
    }

    private void upDir() {
        File p = curDir != null ? curDir.getParentFile() : null;
        if (p != null) browse(p);
    }

    private void updateStatus() {
        statusView.setText("方式 " + modeLabel() + "  " + blockSize + "/" + quality + "   共 " + entries.size() + " 项   输出到源目录");
    }

    // ---------- 文件列表 Adapter ----------
    private class FileAdapter extends BaseAdapter {
        @Override public int getCount() { return entries.size(); }
        @Override public Object getItem(int p) { return entries.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override
        public View getView(int pos, View cv, ViewGroup parent) {
            LinearLayout row;
            if (cv instanceof LinearLayout) {
                row = (LinearLayout) cv;
            } else {
                row = new LinearLayout(TexConverter.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(12, 10, 12, 10);
                TextView nm = new TextView(TexConverter.this);
                nm.setId(1);
                nm.setTextSize(15);
                row.addView(nm, new LinearLayout.LayoutParams(0, -2, 1));
                TextView mt = new TextView(TexConverter.this);
                mt.setId(2);
                mt.setTextSize(11);
                mt.setTextColor(0xFF888888);
                row.addView(mt);
            }
            File f = entries.get(pos);
            TextView name = (TextView) row.findViewById(1);
            TextView meta = (TextView) row.findViewById(2);
            String n = f.getName().toLowerCase();
            String icon = f.isDirectory() ? "[D] " : n.endsWith(".tex") ? "[T] " : n.endsWith(".png") ? "[P] " : n.endsWith(".zip") ? "[Z] " : "[F] ";
            name.setText(icon + f.getName());
            meta.setText(f.isDirectory() ? "目录" : humanSize(f.length()));
            if (listView.isItemChecked(pos)) row.setBackgroundColor(0xFFCDE8FF);
            else row.setBackgroundColor(0x00000000);
            return row;
        }
    }

    private String humanSize(long b) {
        if (b < 1024) return b + " B";
        if (b < 1048576) return String.format("%.1f KB", b / 1024.0);
        return String.format("%.1f MB", b / 1048576.0);
    }

    // ---------- zip 浏览 ----------
    private void openZip(File zip) {
        new Thread(() -> {
            try {
                ZipFile zf = new ZipFile(zip);
                final List<String> names = new ArrayList<>();
                java.util.Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry ze = en.nextElement();
                    if (!ze.isDirectory()) {
                        String nn = ze.getName().toLowerCase();
                        if (nn.endsWith(".tex") || nn.endsWith(".png")) names.add(ze.getName());
                    }
                }
                zf.close();
                if (names.isEmpty()) { runOnUiThread(() -> toast("zip 内无 tex/png")); return; }
                runOnUiThread(() -> new AlertDialog.Builder(this)
                    .setTitle(zip.getName() + " 内纹理（" + names.size() + " 个）")
                    .setItems(names.toArray(new String[0]), null)
                    .setPositiveButton("转换 zip 内全部 DXT", (d, w) -> new Thread(() -> {
                        try {
                            String r = convertZipReplace(zip);
                            runOnUiThread(() -> toast(r));
                        } catch (Exception e) {
                            runOnUiThread(() -> toast("zip 转换失败: " + e.getMessage()));
                        }
                    }).start())
                    .setNegativeButton("取消", null).show());
            } catch (Exception e) {
                runOnUiThread(() -> toast("zip 读取失败: " + e.getMessage()));
            }
        }).start();
    }

    // ---------- 批量转换 ----------
    private void convertSelected() {
        if (!engineReady) { toast("引擎未就绪"); return; }
        // 收集工作项：选中文件 + 选中文件夹内所有 tex/zip + 选中 zip
        final List<File> texFiles = new ArrayList<>();
        final List<File> zipFiles = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            if (!listView.isItemChecked(i)) continue;
            File f = entries.get(i);
            if (f.isDirectory()) collectTexRecursive(f, texFiles, zipFiles);
            else if (f.getName().toLowerCase().endsWith(".zip")) zipFiles.add(f);
            else texFiles.add(f);
        }
        if (texFiles.isEmpty() && zipFiles.isEmpty()) { toast("请先勾选文件或文件夹"); return; }
        final int total = texFiles.size() + zipFiles.size();
        exitMultiSelect();

        // 进度对话框（任务中不可取消）
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setTitle("转换中");
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(total);
        pd.setCancelable(false);
        pd.setCanceledOnTouchOutside(false);
        pd.setMessage("准备中...");
        pd.show();

        new Thread(() -> {
            int ok = 0, fail = 0, done = 0;
            StringBuilder sb = new StringBuilder();
            for (File f : texFiles) {
                try {
                    String r = convertFile(f);
                    ok++;
                    sb.append("OK  ").append(f.getName()).append(" -> ").append(r).append("\n");
                } catch (Exception e) {
                    fail++;
                    sb.append("FAIL ").append(f.getName()).append(": ").append(e.getMessage()).append("\n");
                }
                done++;
                final int prog = done;
                final String st = "转换中 " + done + "/" + total + "  成功 " + ok + "  失败 " + fail;
                runOnUiThread(() -> { pd.setProgress(prog); pd.setMessage(st); });
            }
            for (File z : zipFiles) {
                try {
                    String r = convertZipReplace(z);
                    ok++;
                    sb.append("OK  ").append(z.getName()).append(" -> ").append(r).append("\n");
                } catch (Exception e) {
                    fail++;
                    sb.append("FAIL ").append(z.getName()).append(": ").append(e.getMessage()).append("\n");
                }
                done++;
                final int prog = done;
                final String st = "转换中 " + done + "/" + total + "  成功 " + ok + "  失败 " + fail;
                runOnUiThread(() -> { pd.setProgress(prog); pd.setMessage(st); });
            }
            final String result = sb.toString();
            final int fok = ok, ffail = fail;
            runOnUiThread(() -> {
                pd.dismiss();
                new AlertDialog.Builder(this)
                    .setTitle("转换完成")
                    .setMessage("成功 " + fok + "，失败 " + ffail + "\n（已替换原文件，备份 .bak）\n\n" + result)
                    .setPositiveButton("确定", null)
                    .show();
            });
        }).start();
    }

    // 递归收集文件夹内所有 .tex 与 .zip
    private void collectTexRecursive(File dir, List<File> texFiles, List<File> zipFiles) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) collectTexRecursive(f, texFiles, zipFiles);
            else if (f.getName().toLowerCase().endsWith(".tex")) texFiles.add(f);
            else if (f.getName().toLowerCase().endsWith(".zip")) zipFiles.add(f);
        }
    }

    private String convertFile(File f) throws Exception {
        String n = f.getName().toLowerCase();
        if (n.endsWith(".png")) {
            if (convertMode.equals("tex2png")) throw new Exception("当前模式为 TEX→PNG，跳过 PNG");
            return pngToTex(f);
        } else if (n.endsWith(".tex")) {
            int comp = readTexCompression(f);
            if (convertMode.equals("png2tex")) throw new Exception("当前模式为 PNG→TEX，跳过 TEX");
            if (convertMode.equals("tex2png")) return texToPng(f);
            if (convertMode.equals("dxt2astc")) {
                if (comp == 24) throw new Exception("已是 ASTC，跳过");
                return texToAstc(f);
            }
            // auto
            if (comp == 24) return texToPng(f);
            else return texToAstc(f);
        }
        throw new Exception("不支持的文件类型");
    }

    // ---------- 转换实现 ----------
    private String pngToTex(File png) throws Exception {
        int[] wh = readPngSize(png);
        File flip = new File(getCacheDir(), "flip.png");
        flipPng(png, flip);
        File astc = new File(getCacheDir(), "out.astc");
        runAstcenc("-cl", flip.getAbsolutePath(), astc.getAbsolutePath(), blockSize, "-" + quality);
        byte[] raw = stripAstcHeader(readFile(astc));
        byte[] ktex = packKtex(wh[0], wh[1], raw);
        File out = new File(png.getParentFile(), baseName(png) + ".tex");
        writeFile(out, ktex);
        return out.getName();
    }

    private String texToAstc(File tex) throws Exception {
        File flipPng = new File(getCacheDir(), "decoded.png");
        runTex2png(tex.getAbsolutePath(), flipPng.getAbsolutePath());
        int[] wh = readPngSize(flipPng);
        File astc = new File(getCacheDir(), "out.astc");
        runAstcenc("-cl", flipPng.getAbsolutePath(), astc.getAbsolutePath(), blockSize, "-" + quality);
        byte[] raw = stripAstcHeader(readFile(astc));
        byte[] ktex = packKtex(wh[0], wh[1], raw);
        // 备份原文件为 .bak（仅首次），然后替换原文件
        File bak = new File(tex.getAbsolutePath() + ".bak");
        if (!bak.exists()) copyFile(tex, bak);
        writeFile(tex, ktex);
        return "已替换，原文件→" + bak.getName();
    }

    private String texToPng(File tex) throws Exception {
        byte[] ktex = readFile(tex);
        KtexInfo info = unpackKtex(ktex);
        File astc = new File(getCacheDir(), "in.astc");
        byte[] astcFull = new byte[info.raw.length + 16];
        writeAstcHeader(astcFull, info.w, info.h);
        System.arraycopy(info.raw, 0, astcFull, 16, info.raw.length);
        writeFile(astc, astcFull);
        File flipPng = new File(getCacheDir(), "decoded.png");
        runAstcenc("-dl", astc.getAbsolutePath(), flipPng.getAbsolutePath());
        File out = new File(tex.getParentFile(), baseName(tex) + ".png");
        flipPng(flipPng, out);
        return out.getName();
    }

    // zip 内 .tex 批量转换（替换 zip 内条目，原 zip 备份 .bak）
    private String convertZipReplace(File zip) throws Exception {
        int ok = 0;
        File tmp = new File(getCacheDir(), "new.zip");
        ZipFile zf = new ZipFile(zip);
        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tmp));
        java.util.Enumeration<? extends ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            ZipEntry ze = en.nextElement();
            byte[] data = readAll(zf.getInputStream(ze));
            String n = ze.getName().toLowerCase();
            if (!ze.isDirectory() && n.endsWith(".tex")) {
                int comp = readTexCompression(data);
                if (comp != 24) {
                    data = convertTexBytes(data);
                    ok++;
                }
            }
            ZipEntry ne = new ZipEntry(ze.getName());
            zos.putNextEntry(ne);
            zos.write(data);
            zos.closeEntry();
        }
        zos.close();
        zf.close();
        File bak = new File(zip.getAbsolutePath() + ".bak");
        if (!bak.exists()) copyFile(zip, bak);
        copyFile(tmp, zip);
        tmp.delete();
        return "zip 内 " + ok + " 个 tex 已转换";
    }

    // 内存中把 DXT/RGBA tex 字节转成 ASTC tex 字节
    private byte[] convertTexBytes(byte[] texData) throws Exception {
        File tmpTex = new File(getCacheDir(), "tmp.tex");
        writeFile(tmpTex, texData);
        File png = new File(getCacheDir(), "tmp.png");
        runTex2png(tmpTex.getAbsolutePath(), png.getAbsolutePath());
        int[] wh = readPngSize(png);
        File astc = new File(getCacheDir(), "tmp.astc");
        runAstcenc("-cl", png.getAbsolutePath(), astc.getAbsolutePath(), blockSize, "-" + quality);
        byte[] raw = stripAstcHeader(readFile(astc));
        return packKtex(wh[0], wh[1], raw);
    }

    // ---------- 翻转 ----------
    private void flipPng(File src, File dst) throws Exception {
        Bitmap bmp = BitmapFactory.decodeFile(src.getAbsolutePath());
        if (bmp == null) throw new Exception("无法解码 PNG");
        Matrix m = new Matrix();
        m.preScale(1, -1);
        Bitmap flipped = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
        FileOutputStream out = new FileOutputStream(dst);
        flipped.compress(Bitmap.CompressFormat.PNG, 100, out);
        out.close();
        flipped.recycle(); bmp.recycle();
    }

    private int[] readPngSize(File png) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(png.getAbsolutePath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) throw new Exception("无法读取 PNG 尺寸");
        return new int[]{o.outWidth, o.outHeight};
    }

    // ---------- KTEX ----------
    private byte[] packKtex(int w, int h, byte[] raw) {
        ByteBuffer bb = ByteBuffer.allocate(8 + 10 + raw.length).order(ByteOrder.LITTLE_ENDIAN);
        bb.put((byte) 'K').put((byte) 'T').put((byte) 'E').put((byte) 'X');
        bb.putInt(0xfff02380);
        bb.putShort((short) w).putShort((short) h).putShort((short) 0).putInt(raw.length);
        bb.put(raw);
        return bb.array();
    }

    private KtexInfo unpackKtex(byte[] ktex) throws Exception {
        if (ktex.length < 18 || ktex[0] != 'K' || ktex[1] != 'T' || ktex[2] != 'E' || ktex[3] != 'X')
            throw new Exception("不是 KTEX 文件");
        ByteBuffer bb = ByteBuffer.wrap(ktex).order(ByteOrder.LITTLE_ENDIAN);
        int hdr = bb.getInt(4);
        int compression = (hdr >> 4) & 0x1F;
        int mip = (hdr >> 13) & 0x1F;
        int w = bb.getShort(8) & 0xFFFF;
        int h = bb.getShort(10) & 0xFFFF;
        int datasz = bb.getInt(14);
        int dataOff = 8 + mip * 10;
        if (compression != 24) throw new Exception("仅支持 ASTC(comp=24)，当前 comp=" + compression);
        if (dataOff + datasz > ktex.length) throw new Exception("数据长度不匹配");
        byte[] raw = new byte[datasz];
        System.arraycopy(ktex, dataOff, raw, 0, datasz);
        KtexInfo info = new KtexInfo();
        info.w = w; info.h = h; info.raw = raw;
        return info;
    }

    private int readTexCompression(File f) throws Exception {
        return readTexCompression(readFile(f));
    }

    private int readTexCompression(byte[] b) throws Exception {
        if (b.length < 8 || b[0] != 'K' || b[1] != 'T' || b[2] != 'E' || b[3] != 'X')
            throw new Exception("不是 KTEX 文件");
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        return (bb.getInt(4) >> 4) & 0x1F;
    }

    private byte[] stripAstcHeader(byte[] astc) throws Exception {
        if (astc.length < 16) throw new Exception("ASTC 数据异常");
        byte[] raw = new byte[astc.length - 16];
        System.arraycopy(astc, 16, raw, 0, raw.length);
        return raw;
    }

    private void writeAstcHeader(byte[] buf, int w, int h) {
        buf[0] = 0x13; buf[1] = (byte) 0xAB; buf[2] = (byte) 0xA1; buf[3] = 0x5C;
        buf[4] = 8; buf[5] = 8; buf[6] = 1;
        buf[7] = (byte) (w & 0xFF); buf[8] = (byte) ((w >> 8) & 0xFF); buf[9] = (byte) ((w >> 16) & 0xFF);
        buf[10] = (byte) (h & 0xFF); buf[11] = (byte) ((h >> 8) & 0xFF); buf[12] = (byte) ((h >> 16) & 0xFF);
        buf[13] = 1;
    }

    // ---------- native ----------
    private void runAstcenc(String... args) throws Exception {
        int code = nativeAstcenc(args);
        if (code != 0) throw new Exception("astcenc 退出码 " + code);
    }

    private void runTex2png(String... args) throws Exception {
        int code = nativeTex2png(args);
        if (code != 0) throw new Exception("tex2png 退出码 " + code);
    }

    // ---------- 文件工具 ----------
    private String modeLabel() {
        if (convertMode.equals("png2tex")) return "PNG→TEX";
        if (convertMode.equals("tex2png")) return "TEX→PNG";
        if (convertMode.equals("dxt2astc")) return "TEX→ASTC";
        return "自动";
    }

    private byte[] readFile(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }

    private void writeFile(File f, byte[] data) throws Exception {
        FileOutputStream out = new FileOutputStream(f);
        out.write(data);
        out.close();
    }

    private void copyStream(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close(); out.close();
    }

    private void copyFile(File src, File dst) throws Exception {
        copyStream(new FileInputStream(src), new FileOutputStream(dst));
    }

    private byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }

    private String baseName(File f) {
        String n = f.getName();
        int i = n.lastIndexOf('.');
        return i > 0 ? n.substring(0, i) : n;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    static class KtexInfo { int w, h; byte[] raw; }
}
