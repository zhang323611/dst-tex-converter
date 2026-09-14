package com.dsttex;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
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
import android.widget.CheckBox;
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
    private boolean autoBackup = false; // 自动备份 .bak，默认关闭
    private SharedPreferences prefs;
    private String highlightName = null; // 搜索跳转后高亮的文件名

    // ================= MT 风格 UI =================
    private static final int C_PRIMARY  = 0xFF2B6CB0;
    private static final int C_BG       = 0xFFF2F4F7;
    private static final int C_CARD     = 0xFFFFFFFF;
    private static final int C_TEXT     = 0xFF1F2328;
    private static final int C_TEXT_SUB = 0xFF8A9099;
    private static final int C_DIVIDER  = 0xFFEBEEF2;
    private static final int C_SEL      = 0xFFDCEAFB;
    private HorizontalScrollView crumbScroll;
    private LinearLayout crumbBar;
    private FrameLayout drawerLayer;
    private LinearLayout drawerPanel;
    // 目录子项数缓存(browse 时一次性统计, 避免 getView 每帧 listFiles)
    private final java.util.HashMap<String, Integer> dirCount = new java.util.HashMap<>();
    // 并发转换线程数(1=单线程, 2/4=固定, -1=自动检测CPU核心数)
    private int threadCount = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("texconv", MODE_PRIVATE);
        autoBackup = prefs.getBoolean("autoBackup", false);
        threadCount = prefs.getInt("threadCount", 1);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(C_BG);

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        root.addView(main, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setBackgroundColor(C_PRIMARY);
        main.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(54)));

        toolbar.addView(topBtn("\u2630", v -> openDrawer()));
        TextView title = new TextView(this);
        title.setText("纹理转换器");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(17);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1);
        tlp.leftMargin = dp(4);
        toolbar.addView(title, tlp);
        toolbar.addView(topBtn("\u2315", v -> showSearch()));
        toolbar.addView(topBtn("\u22ee", v -> showMenu(v)));

        LinearLayout crumbWrap = new LinearLayout(this);
        crumbWrap.setOrientation(LinearLayout.HORIZONTAL);
        crumbWrap.setGravity(Gravity.CENTER_VERTICAL);
        crumbWrap.setBackgroundColor(C_CARD);
        crumbWrap.setPadding(dp(6), 0, dp(6), 0);
        Button up = topBtn("\u2039", v -> upDir());
        up.setTextColor(C_PRIMARY);
        crumbWrap.addView(up);
        crumbScroll = new HorizontalScrollView(this);
        crumbScroll.setHorizontalScrollBarEnabled(false);
        crumbBar = new LinearLayout(this);
        crumbBar.setOrientation(LinearLayout.HORIZONTAL);
        crumbBar.setGravity(Gravity.CENTER_VERTICAL);
        crumbScroll.addView(crumbBar);
        crumbWrap.addView(crumbScroll, new LinearLayout.LayoutParams(0, -1, 1));
        main.addView(crumbWrap, new LinearLayout.LayoutParams(-1, dp(40)));
        main.addView(divider());

        listView = new ListView(this);
        listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        listView.setDivider(new ColorDrawable(C_DIVIDER));
        listView.setDividerHeight(dp(1));
        listView.setBackgroundColor(C_CARD);
        listView.setSelector(new ColorDrawable(0x00000000));
        listView.setOnItemClickListener(this::onItemClick);
        listView.setOnItemLongClickListener((p, v, pos, id) -> { enterMultiSelect(pos); return true; });
        main.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1));
        main.addView(divider());

        actionBar = new LinearLayout(this);
        actionBar.setOrientation(LinearLayout.HORIZONTAL);
        actionBar.setGravity(Gravity.CENTER);
        actionBar.setBackgroundColor(C_CARD);
        actionBar.setPadding(dp(2), dp(2), dp(2), dp(2));
        main.addView(actionBar);

        statusView = new TextView(this);
        statusView.setTextSize(11);
        statusView.setTextColor(C_TEXT_SUB);
        statusView.setBackgroundColor(C_CARD);
        statusView.setPadding(dp(12), dp(2), dp(12), dp(6));
        main.addView(statusView);

        drawerLayer = new FrameLayout(this);
        drawerLayer.setVisibility(View.GONE);
        View scrim = new View(this);
        scrim.setBackgroundColor(0x88000000);
        scrim.setOnClickListener(v -> closeDrawer());
        drawerLayer.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        drawerPanel = new LinearLayout(this);
        drawerPanel.setOrientation(LinearLayout.VERTICAL);
        drawerPanel.setBackgroundColor(C_CARD);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(dp(268), -1);
        dlp.gravity = Gravity.START;
        drawerLayer.addView(drawerPanel, dlp);
        buildDrawer();
        root.addView(drawerLayer, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
        updateActionBar();

        if (android.os.Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 200);
        }
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

    // ================= UI 工具 =================
    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp((int) radiusDp));
        return g;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(C_DIVIDER);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(1)));
        return v;
    }

    private Button topBtn(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(17);
        b.setTextColor(0xFFFFFFFF);
        b.setBackgroundColor(0x00000000);
        b.setMinWidth(dp(44));
        b.setMinimumWidth(dp(44));
        b.setMinHeight(dp(44));
        b.setMinimumHeight(dp(44));
        b.setOnClickListener(l);
        return b;
    }

    private Button barBtn(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(13);
        b.setTextColor(C_PRIMARY);
        b.setBackgroundColor(0x00000000);
        b.setPadding(0, dp(8), 0, dp(8));
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        return b;
    }

    private void buildDrawer() {
        TextView h = new TextView(this);
        h.setText("纹理转换器");
        h.setTextSize(16);
        h.setTextColor(C_TEXT);
        h.setPadding(dp(20), dp(24), dp(20), dp(14));
        drawerPanel.addView(h);
        drawerPanel.addView(divider());
        drawerItem("主目录", v -> { closeDrawer(); browse(Environment.getExternalStorageDirectory()); });
        drawerItem("下载目录", v -> {
            closeDrawer();
            File d = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (d != null && d.exists()) browse(d); else toast("下载目录不可用");
        });
        drawerItem("书签", v -> { closeDrawer(); showBookmarks(); });
        drawerItem("搜索文件", v -> { closeDrawer(); showSearch(); });
        drawerItem("把当前目录加为书签", v -> { closeDrawer(); addBookmark(curDir); });
        drawerItem("转换模式设置", v -> { closeDrawer(); showSettings(); });
        drawerItem("关于", v -> { closeDrawer(); showAbout(); });
    }

    private void drawerItem(String label, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(15);
        t.setTextColor(C_TEXT);
        t.setPadding(dp(20), dp(15), dp(20), dp(15));
        t.setOnClickListener(l);
        drawerPanel.addView(t);
    }

    private void openDrawer() {
        drawerLayer.setVisibility(View.VISIBLE);
        android.view.animation.TranslateAnimation a =
            new android.view.animation.TranslateAnimation(-dp(268), 0, 0, 0);
        a.setDuration(180);
        drawerPanel.startAnimation(a);
    }

    private void closeDrawer() {
        drawerLayer.setVisibility(View.GONE);
    }

    // ---------- 并发转换辅助 ----------
    private File tempWorkingDir(String suffix) {
        File dir = new File(getCacheDir(), "task_" + suffix);
        dir.mkdirs();
        return dir;
    }

    private int activeThreadCount() {
        if (threadCount < 0) return Math.max(1, Runtime.getRuntime().availableProcessors());
        return Math.max(1, threadCount);
    }

    // 并发批量转换任务(每个任务使用独立临时目录避免冲突)
    private String runConvertTaskMT(final File input, final File outputDir,
            final java.util.List<String> results) {
        final String tid = String.valueOf(Thread.currentThread().getId());
        try {
            File dir = tempWorkingDir(tid);
            try {
                String r = convertFile(input, outputDir, dir);
                synchronized(results) { results.add("OK  " + input.getName() + " -> " + r); }
                return r;
            } finally {
                File[] tfs = dir.listFiles();
                if (tfs != null) for (File c : tfs) c.delete();
                dir.delete();
            }
        } catch (Exception e) {
            synchronized(results) {
                results.add("FAIL " + input.getName() + ": " + e.getMessage());
            }
            return null;
        }
    }

    // 并发 zip 转换任务(独立临时目录, 避免并行互相覆盖)
    private String runZipTaskMT(final File zip, final java.util.List<String> results) {
        final String tid = String.valueOf(Thread.currentThread().getId());
        try {
            File dir = tempWorkingDir("zip_" + tid);
            try {
                String r = convertZipReplace(zip, dir);
                synchronized(results) { results.add("OK  " + zip.getName() + " -> " + r); }
                return r;
            } finally {
                File[] zs = dir.listFiles();
                if (zs != null) for (File c : zs) c.delete();
                dir.delete();
            }
        } catch (Exception e) {
            synchronized(results) {
                results.add("FAIL " + zip.getName() + ": " + e.getMessage());
            }
            return null;
        }
    }

    private void updateBreadcrumb() {
        if (crumbBar == null) return;
        crumbBar.removeAllViews();
        if (curDir == null) return;
        List<File> chain = new ArrayList<>();
        File f = curDir;
        while (f != null) { chain.add(0, f); f = f.getParentFile(); }
        for (int i = 0; i < chain.size(); i++) {
            final File seg = chain.get(i);
            if (i > 0) {
                TextView sep = new TextView(this);
                sep.setText("\u203a");
                sep.setTextSize(13);
                sep.setTextColor(C_TEXT_SUB);
                sep.setPadding(dp(3), 0, dp(3), 0);
                crumbBar.addView(sep);
            }
            TextView t = new TextView(this);
            String nm = seg.getName();
            if (nm == null || nm.isEmpty()) nm = "/";
            t.setText(nm);
            t.setTextSize(13);
            t.setTextColor(i == chain.size() - 1 ? C_PRIMARY : C_TEXT_SUB);
            t.setPadding(dp(6), dp(8), dp(6), dp(8));
            t.setOnClickListener(v -> browse(seg));
            crumbBar.addView(t);
        }
        crumbScroll.post(() -> crumbScroll.fullScroll(View.FOCUS_RIGHT));
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
        final String repo = "https://github.com/zhang323611/dst-tex-converter";
        String v = appVersion();
        new AlertDialog.Builder(this)
            .setTitle("关于")
            .setMessage("饥荒手机版纹理转换工具" + (v.isEmpty() ? "" : "  v" + v) + "\n\n"
                + "支持：\n"
                + "· DXT1/3/5 → ASTC(手机版)\n"
                + "· PNG → ASTC\n"
                + "· ASTC → PNG\n"
                + "· zip 内批量转换\n\n"
                + "ASTC 8x8(2bpp) 为手机版标准格式，占用约为 RGBA 的 1/16。\n\n"
                + "作者 QQ：599739709\n"
                + "GitHub：zhang323611/dst-tex-converter\n"
                + repo)
            .setPositiveButton("访问仓库", (d, w) -> openUrl(repo))
            .setNeutralButton("复制链接", (d, w) -> copyToClipboard(repo))
            .setNegativeButton("关闭", null)
            .show();
    }

    // 从清单读取版本号,避免手改漂移
    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            toast("无法打开链接");
        }
    }

    private void copyToClipboard(String text) {
        try {
            android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("link", text));
            toast("已复制到剪贴板");
        } catch (Exception e) {
            toast("复制失败");
        }
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

        // 自动备份 .bak
        final CheckBox cbBackup = new CheckBox(this);
        cbBackup.setText("自动备份 .bak（转换前备份原文件，默认关闭）");
        cbBackup.setChecked(autoBackup);
        cbBackup.setPadding(0, 12, 0, 0);
        panel.addView(cbBackup);

        // 并发线程数
        TextView t3 = new TextView(this);
        t3.setText("并发线程数（立即生效）");
        t3.setTextSize(14);
        t3.setPadding(0, 12, 0, 0);
        panel.addView(t3);
        final int cpuCores = Math.max(1, Runtime.getRuntime().availableProcessors());
        final String[] threadVals = {"1", "2", "4", "8", "-1"};
        final String[] threadLabels = {
            "1 — 单线程（默认，兼容）",
            "2 — 双线程",
            "4 — 四线程",
            "8 — 八线程",
            "自动 — 检测 " + cpuCores + " 核"
        };
        final android.widget.RadioGroup rgThreads = new android.widget.RadioGroup(this);
        for (int i = 0; i < threadVals.length; i++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setText(threadLabels[i]);
            rb.setId(i);
            if (threadCount == Integer.parseInt(threadVals[i])) rb.setChecked(true);
            rgThreads.addView(rb);
        }
        panel.addView(rgThreads);

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(panel);
        new AlertDialog.Builder(this)
            .setTitle("转换模式设置")
            .setView(sv)
            .setPositiveButton("确定", (d, w) -> {
                int m = rgMode.getCheckedRadioButtonId();
                int s = rgSize.getCheckedRadioButtonId();
                int q = rgQual.getCheckedRadioButtonId();
                if (m >= 0 && m < modeVals.length) convertMode = modeVals[m];
                if (s >= 0 && s < sizeVals.length) blockSize = sizeVals[s];
                if (q >= 0 && q < qualVals.length) quality = qualVals[q];
                autoBackup = cbBackup.isChecked();
                prefs.edit().putBoolean("autoBackup", autoBackup).apply();
                try {
                    int sel = rgThreads.getCheckedRadioButtonId();
                    threadCount = Integer.parseInt(threadVals[sel]);
                    prefs.edit().putInt("threadCount", threadCount).apply();
                } catch (Exception ignored) {}
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
        listView.invalidateViews();
        updateActionBar();
    }

    private void exitMultiSelect() {
        multiSelect = false;
        listView.clearChoices();
        listView.invalidateViews();
        updateStatus();
        updateActionBar();
    }

    private void toggleAll() {
        boolean all = listView.getCheckedItemCount() < entries.size();
        for (int i = 0; i < entries.size(); i++) listView.setItemChecked(i, all);
        updateActionBar();
    }

    private void updateActionBar() {
        if (actionBar == null) return;
        actionBar.removeAllViews();
        int checked = listView.getCheckedItemCount();
        if (multiSelect && checked > 0) {
            actionBar.addView(barBtn(checked >= entries.size() ? "反选" : "全选", v -> toggleAll()));
            actionBar.addView(barBtn("转换", v -> convertSelected()));
            actionBar.addView(barBtn("重命名", v -> renameSelected()));
            actionBar.addView(barBtn("删除", v -> deleteSelected()));
            actionBar.addView(barBtn("退出", v -> exitMultiSelect()));
        } else {
            actionBar.addView(barBtn("主页", v -> browse(Environment.getExternalStorageDirectory())));
            actionBar.addView(barBtn("书签", v -> showBookmarks()));
            actionBar.addView(barBtn("搜索", v -> showSearch()));
            actionBar.addView(barBtn("设置", v -> showSettings()));
        }
    }

    @Override
    public void onBackPressed() {
        if (drawerLayer != null && drawerLayer.getVisibility() == View.VISIBLE) {
            closeDrawer();
        } else if (multiSelect) {
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
            updateBreadcrumb();
            entries.clear();
            dirCount.clear();
            File[] fs = dir.listFiles();
            if (fs != null) {
                java.util.Arrays.sort(fs, (a, b) -> {
                    if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                    return a.getName().compareToIgnoreCase(b.getName());
                });
                for (File f : fs) {
                    entries.add(f);
                    if (f.isDirectory()) {
                        File[] ch = f.listFiles();
                        dirCount.put(f.getAbsolutePath(), ch == null ? -1 : ch.length);
                    }
                }
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
        } else if (f.getName().toLowerCase().endsWith(".dyn")) {
            openDyn(f);
        } else if (f.getName().toLowerCase().endsWith(".tex")) {
            previewTex(f);
        } else {
            toast("长按进入多选后转换。文件: " + f.getName());
        }
    }

    private void upDir() {
        File p = curDir != null ? curDir.getParentFile() : null;
        if (p != null) browse(p);
    }

    private void updateStatus() {
        String tc = threadCount < 0 ? "auto" : String.valueOf(threadCount);
        statusView.setText("方式 " + modeLabel() + "  " + blockSize + "/" + quality
            + "   共 " + entries.size() + " 项   " + tc + " 线程   输出到源目录");
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
                row.setPadding(dp(12), dp(8), dp(12), dp(8));
                row.setMinimumHeight(dp(58));

                TextView ic = new TextView(TexConverter.this);
                ic.setId(3);
                ic.setGravity(Gravity.CENTER);
                ic.setTextSize(15);
                ic.setTextColor(0xFFFFFFFF);
                ic.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(36), dp(36));
                ilp.rightMargin = dp(12);
                row.addView(ic, ilp);

                LinearLayout col = new LinearLayout(TexConverter.this);
                col.setOrientation(LinearLayout.VERTICAL);
                TextView nm = new TextView(TexConverter.this);
                nm.setId(1);
                nm.setTextSize(15);
                nm.setTextColor(C_TEXT);
                nm.setSingleLine(true);
                nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
                col.addView(nm);
                TextView mt = new TextView(TexConverter.this);
                mt.setId(2);
                mt.setTextSize(11);
                mt.setTextColor(C_TEXT_SUB);
                mt.setPadding(0, dp(2), 0, 0);
                col.addView(mt);
                row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            }
            File f = entries.get(pos);
            TextView ic = (TextView) row.findViewById(3);
            TextView nm = (TextView) row.findViewById(1);
            TextView mt = (TextView) row.findViewById(2);
            String n = f.getName().toLowerCase();
            String letter;
            int color;
            if (f.isDirectory()) { letter = "D"; color = 0xFF4A90D9; }
            else if (n.endsWith(".tex")) { letter = "T"; color = 0xFFE8833A; }
            else if (n.endsWith(".png")) { letter = "P"; color = 0xFF34A853; }
            else if (n.endsWith(".zip")) { letter = "Z"; color = 0xFF9B59B6; }
            else { letter = "F"; color = 0xFF95A1AD; }
            ic.setText(letter);
            ic.setBackground(rounded(color, 9f));
            nm.setText(f.getName());
            if (f.isDirectory()) {
                Integer cnt = dirCount.get(f.getAbsolutePath());
                mt.setText(cnt == null || cnt < 0 ? "文件夹" : "文件夹 · " + cnt + " 项");
            } else {
                mt.setText(humanSize(f.length()) + " · " +
                    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                        .format(new java.util.Date(f.lastModified())));
            }
            row.setBackgroundColor(listView.isItemChecked(pos) ? C_SEL : 0x00000000);
            return row;
        }
    }

    private String humanSize(long b) {
        if (b < 1024) return b + " B";
        if (b < 1048576) return String.format("%.1f KB", b / 1024.0);
        return String.format("%.1f MB", b / 1048576.0);
    }

    // ================= .dyn 解密 / 加密 =================
    // 8 字节分块: 置换 + XOR。解密后为 zip; 以 "PK" 开头则原样直通
    private static final int[] DYN_IDX = {5, 3, 6, 7, 4, 2, 0, 1};

    private static boolean isPk(byte[] b) {
        return b.length >= 2 && b[0] == 'P' && b[1] == 'K';
    }

    // out[i] = in[idx[i]] ^ (0x8D + i)
    private byte[] dynDecrypt(byte[] in) {
        if (isPk(in)) return in;
        byte[] out = new byte[in.length];
        int full = in.length / 8;
        for (int b = 0; b < full; b++) {
            int base = b * 8;
            for (int i = 0; i < 8; i++) {
                out[base + i] = (byte) ((in[base + DYN_IDX[i]] & 0xFF) ^ (0x8D + i));
            }
        }
        for (int k = full * 8; k < in.length; k++) out[k] = in[k];
        return out;
    }

    // out[idx[i]] = in[i] ^ (0x8D + i)
    private byte[] dynEncrypt(byte[] in) {
        if (isPk(in)) return in;
        byte[] out = new byte[in.length];
        int full = in.length / 8;
        for (int b = 0; b < full; b++) {
            int base = b * 8;
            for (int i = 0; i < 8; i++) {
                out[base + DYN_IDX[i]] = (byte) ((in[base + i] & 0xFF) ^ (0x8D + i));
            }
        }
        for (int k = full * 8; k < in.length; k++) out[k] = in[k];
        return out;
    }

    // 打开 .dyn: 解密为 zip, 列出包内条目
    private void openDyn(final File dyn) {
        toast("正在解密 " + dyn.getName() + " ...");
        new Thread(() -> {
            try {
                byte[] raw = readFile(dyn);
                final byte[] dec = dynDecrypt(raw);
                if (!isPk(dec)) {
                    runOnUiThread(() -> toast("解密结果不是 zip(可能格式不符)"));
                    return;
                }
                File zip = new File(getCacheDir(), baseName(dyn) + ".zip");
                writeFile(zip, dec);
                final java.util.List<String> items = new ArrayList<>();
                long total = 0;
                ZipFile zf = new ZipFile(zip);
                java.util.Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry ze = en.nextElement();
                    if (ze.isDirectory()) continue;
                    items.add(ze.getName() + "    " + humanSize(ze.getSize()));
                    total += ze.getSize();
                }
                zf.close();
                final long tot = total;
                runOnUiThread(() -> {
                    if (items.isEmpty()) { toast("zip 内无文件"); return; }
                    new AlertDialog.Builder(this)
                        .setTitle(dyn.getName() + "  解密后 " + items.size() + " 项 / " + humanSize(tot))
                        .setItems(items.toArray(new String[0]), null)
                        .setPositiveButton("导出 zip", (d, w) -> {
                            try {
                                File outZip = new File(dyn.getParentFile(), baseName(dyn) + ".zip");
                                copyFile(zip, outZip);
                                toast("已导出: " + outZip.getName());
                            } catch (Exception e) { toast("导出失败: " + e.getMessage()); }
                        })
                        .setNeutralButton("重新加密", (d, w) -> {
                            try {
                                File outDyn = new File(dyn.getParentFile(), baseName(dyn) + "_re.dyn");
                                writeFile(outDyn, dynEncrypt(dec));
                                toast("已写出: " + outDyn.getName());
                            } catch (Exception e) { toast("加密失败: " + e.getMessage()); }
                        })
                        .setNegativeButton("关闭", null)
                        .show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> toast("dyn 解密失败: " + e.getMessage()));
            }
        }).start();
    }

    // ================= .tex 预览 =================
    // 支持的 comp: 0/1/2(DXT1/3/5), 4(RGBA), 5(RGB) 走 tex2png; 24(ASTC) 走 astcenc
    private void previewTex(final File tex) {
        toast("正在解码 " + tex.getName() + " ...");
        new Thread(() -> {
            File tmpDir = tempWorkingDir("prev");
            try {
                byte[] data = readFile(tex);
                final int comp = readTexCompression(data);
                File raw = new File(tmpDir, "raw.png");
                if (comp == 24) {
                    KtexInfo info = unpackKtex(data);
                    File astc = new File(tmpDir, "p.astc");
                    byte[] full = new byte[info.raw.length + 16];
                    writeAstcHeader(full, info.w, info.h);
                    System.arraycopy(info.raw, 0, full, 16, info.raw.length);
                    writeFile(astc, full);
                    runAstcenc("-dl", astc.getAbsolutePath(), raw.getAbsolutePath());
                } else {
                    runTex2png(tex.getAbsolutePath(), raw.getAbsolutePath());
                }
                // KTEX 数据为垂直翻转存储, 预览需翻正
                File shown = new File(tmpDir, "shown.png");
                flipPng(raw, shown);
                final Bitmap bmp = BitmapFactory.decodeFile(shown.getAbsolutePath());
                if (bmp == null) throw new Exception("PNG 解码失败");
                final String info2 = compLabel(comp) + "   " + bmp.getWidth() + " x " + bmp.getHeight()
                    + "   " + humanSize(tex.length());
                runOnUiThread(() -> showPreviewDialog(tex.getName(), bmp, info2));
            } catch (Exception e) {
                runOnUiThread(() -> toast("预览失败: " + e.getMessage()));
            } finally {
                File[] fs = tmpDir.listFiles();
                if (fs != null) for (File c : fs) c.delete();
                tmpDir.delete();
            }
        }).start();
    }

    private String compLabel(int c) {
        switch (c) {
            case 0: return "DXT1";
            case 1: return "DXT3";
            case 2: return "DXT5";
            case 4: return "RGBA";
            case 5: return "RGB";
            case 24: return "ASTC";
            default: return "comp=" + c;
        }
    }

    private void showPreviewDialog(String title, Bitmap bmp, String info) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), dp(10), dp(12), dp(10));
        TextView t = new TextView(this);
        t.setText(info);
        t.setTextSize(12);
        t.setTextColor(C_TEXT_SUB);
        t.setPadding(0, 0, 0, dp(6));
        col.addView(t);
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setImageBitmap(bmp);
        iv.setAdjustViewBounds(true);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(iv);
        col.addView(sv);
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setView(col)
            .setPositiveButton("关闭", null)
            .show();
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
                            File zdir = tempWorkingDir("openzip_" + Thread.currentThread().getId());
                            final String r;
                            try {
                                r = convertZipReplace(zip, zdir);
                            } finally {
                                File[] zs = zdir.listFiles();
                                if (zs != null) for (File c : zs) c.delete();
                                zdir.delete();
                            }
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
        final java.util.List<File> texFiles = new java.util.ArrayList<>();
        final java.util.List<File> zipFiles = new java.util.ArrayList<>();
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

        final int N = activeThreadCount();

        final ProgressDialog pd = new ProgressDialog(this);
        pd.setTitle(N > 1 ? "转换中 (" + N + " 线程)" : "转换中");
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(total);
        pd.setCancelable(false);
        pd.setCanceledOnTouchOutside(false);
        pd.setMessage("准备中...");
        pd.show();

        new Thread(() -> {
            final java.util.List<String> results = new java.util.ArrayList<>();
            int ok = 0, fail = 0;

            if (N <= 1) {
                // ---------- 单线程路径(兼容旧行为) ----------
                int done = 0;
                for (File f : texFiles) {
                    try {
                        File tmpDir = tempWorkingDir("st_" + done);
                        try {
                            String r = convertFile(f, f.getParentFile(), tmpDir);
                            ok++; results.add("OK  " + f.getName() + " -> " + r);
                        } finally {
                            for (File c : tmpDir.listFiles()) if (c != null) c.delete();
                            tmpDir.delete();
                        }
                    } catch (Exception e) {
                        fail++; results.add("FAIL " + f.getName() + ": " + e.getMessage());
                    }
                    done++;
                    final int prog = done;
                    final String st = "转换中 " + done + "/" + total + "  成功 " + ok + "  失败 " + fail;
                    runOnUiThread(() -> { pd.setProgress(prog); pd.setMessage(st); });
                }
                for (File z : zipFiles) {
                    File zdir = tempWorkingDir("stzip_" + done);
                    try { String r = convertZipReplace(z, zdir); ok++; results.add("OK  " + z.getName() + " -> " + r); }
                    catch (Exception e) { fail++; results.add("FAIL " + z.getName() + ": " + e.getMessage()); }
                    finally {
                        File[] zfs = zdir.listFiles();
                        if (zfs != null) for (File c : zfs) c.delete();
                        zdir.delete();
                    }
                    done++;
                    final int prog = done;
                    final String st = "转换中 " + done + "/" + total + "  成功 " + ok + "  失败 " + fail;
                    runOnUiThread(() -> { pd.setProgress(prog); pd.setMessage(st); });
                }
            } else {
                // ---------- 多线程路径 ----------
                java.util.concurrent.ExecutorService executor =
                    java.util.concurrent.Executors.newFixedThreadPool(N);

                final int done[] = {0};
                java.util.List<java.util.concurrent.Future<String>> futures =
                    new java.util.ArrayList<>();

                for (final File f : texFiles) {
                    futures.add(executor.submit(() -> {
                        String r = runConvertTaskMT(f, f.getParentFile(), results);
                        synchronized(done) {
                            done[0]++;
                            final int prog = done[0];
                            final String st = "转换中 " + done[0] + "/" + total + "  线程 " + N;
                            runOnUiThread(() -> { pd.setProgress(prog); pd.setMessage(st); });
                        }
                        return r;
                    }));
                }
                for (final File z : zipFiles) {
                    futures.add(executor.submit(() -> {
                        try {
                            return runZipTaskMT(z, results);
                        } finally {
                            synchronized(done) {
                                done[0]++;
                                final int prog = done[0];
                                runOnUiThread(() -> pd.setProgress(prog));
                            }
                        }
                    }));
                }

                executor.shutdown();
                try { executor.awaitTermination(30, java.util.concurrent.TimeUnit.MINUTES); }
                catch (InterruptedException ignored) {}

                for (java.util.concurrent.Future<String> f : futures) {
                    try { if (f.get() != null) ok++; else fail++; }
                    catch (Exception e) { fail++; }
                }
            }

            final String result = String.join("\n", results);
            final int fok = ok, ffail = fail;
            runOnUiThread(() -> {
                pd.dismiss();
                new AlertDialog.Builder(this)
                    .setTitle("转换完成")
                    .setMessage("成功 " + fok + "，失败 " + ffail
                        + (N > 1 ? " (" + N + " 线程)" : "")
                        + "\n（已替换原文件，备份 .bak）\n\n" + result)
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

    private String convertFile(File f, File outputDir, File tmpDir) throws Exception {
        String n = f.getName().toLowerCase();
        if (n.endsWith(".png")) {
            if (convertMode.equals("tex2png")) throw new Exception("当前模式为 TEX→PNG，跳过 PNG");
            return pngToTex(f, outputDir, tmpDir);
        } else if (n.endsWith(".tex")) {
            int comp = readTexCompression(f);
            if (convertMode.equals("png2tex")) throw new Exception("当前模式为 PNG→TEX，跳过 TEX");
            if (convertMode.equals("tex2png")) return texToPng(f, outputDir, tmpDir);
            if (convertMode.equals("dxt2astc")) {
                if (comp == 24) throw new Exception("已是 ASTC，跳过");
                return texToAstc(f, outputDir, tmpDir);
            }
            // auto
            if (comp == 24) {
                return texToPng(f, outputDir, tmpDir);
            } else {
                return texToAstc(f, outputDir, tmpDir);
            }
        }
        throw new Exception("不支持的文件类型");
    }

    private String pngToTex(File png, File outputDir, File tmpDir) throws Exception {
        int[] wh = readPngSize(png);
        File flip = new File(tmpDir, "flip.png");
        flipPng(png, flip);
        byte[] ktex = buildAstcKtex(flip, tmpDir);
        File out = new File(outputDir, baseName(png) + ".tex");
        writeFile(out, ktex);
        return out.getName();
    }

    private String texToAstc(File tex, File outputDir, File tmpDir) throws Exception {
        File flipPng = new File(tmpDir, "decoded.png");
        runTex2png(tex.getAbsolutePath(), flipPng.getAbsolutePath());
        int[] wh = readPngSize(flipPng);
        byte[] ktex = buildAstcKtex(flipPng, tmpDir);
        File bak = null;
        if (autoBackup) {
            bak = new File(tex.getAbsolutePath() + ".bak");
            if (!bak.exists()) copyFile(tex, bak);
        }
        writeFile(tex, ktex);
        return autoBackup ? "已替换，原文件→" + bak.getName() : "已替换（未备份）";
    }

    private String texToPng(File tex, File outputDir, File tmpDir) throws Exception {
        byte[] ktex = readFile(tex);
        KtexInfo info = unpackKtex(ktex);
        File astc = new File(tmpDir, "in.astc");
        byte[] astcFull = new byte[info.raw.length + 16];
        writeAstcHeader(astcFull, info.w, info.h);
        System.arraycopy(info.raw, 0, astcFull, 16, info.raw.length);
        writeFile(astc, astcFull);
        File flipPng = new File(tmpDir, "decoded.png");
        runAstcenc("-dl", astc.getAbsolutePath(), flipPng.getAbsolutePath());
        File out = new File(outputDir, baseName(tex) + ".png");
        flipPng(flipPng, out);
        return out.getName();
    }

    // zip 内 .tex 批量转换（替换 zip 内条目，原 zip 备份 .bak）
    private String convertZipReplace(File zip, File tmpDir) throws Exception {
        int ok = 0;
        File tmp = new File(tmpDir, "new.zip");
        ZipFile zf = new ZipFile(zip);
        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tmp));
        java.util.Enumeration<? extends ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            ZipEntry ze = en.nextElement();
            String n = ze.getName().toLowerCase();
            ZipEntry ne = new ZipEntry(ze.getName());
            if (ze.getTime() > 0) ne.setTime(ze.getTime());
            zos.putNextEntry(ne);
            if (!ze.isDirectory() && n.endsWith(".tex")) {
                byte[] data = readAll(zf.getInputStream(ze));
                int comp = readTexCompression(data);
                if (comp != 24) {
                    data = convertTexBytes(data, tmpDir);
                    ok++;
                }
                zos.write(data);
            } else {
                // 非 .tex 条目流式拷贝, 避免整包读入内存导致大 zip OOM
                InputStream in = zf.getInputStream(ze);
                byte[] buf = new byte[65536];
                int r;
                while ((r = in.read(buf)) > 0) zos.write(buf, 0, r);
                in.close();
            }
            zos.closeEntry();
        }
        zos.close();
        zf.close();
        if (autoBackup) {
            File bak = new File(zip.getAbsolutePath() + ".bak");
            if (!bak.exists()) copyFile(zip, bak);
        }
        copyFile(tmp, zip);
        tmp.delete();
        return "zip 内 " + ok + " 个 tex 已转换";
    }

    // 内存中把 DXT/RGBA tex 字节转成 ASTC tex 字节
    private byte[] convertTexBytes(byte[] texData, File tmpDir) throws Exception {
        File tmpTex = new File(tmpDir, "tmp.tex");
        writeFile(tmpTex, texData);
        File png = new File(tmpDir, "tmp.png");
        runTex2png(tmpTex.getAbsolutePath(), png.getAbsolutePath());
        int[] wh = readPngSize(png);
        return buildAstcKtex(png, tmpDir);
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
    // KTEX 头部基础标志位(compression=24/ASTC 已含)。mipmap 数占 bits 13-17
    private static final int KTEX_BASE_FLAGS = 0xFFFC0380;

    // 按完整 mipmap 链打包 KTEX
    // 每级 pre: w(2) h(2) pitch(2) datasz(4)
    //   pitch  = ceil(w/8) * 16
    //   datasz = ceil(w/8) * ceil(h/8) * 16
    private byte[] packKtexLevels(java.util.List<int[]> dims, java.util.List<byte[]> datas) {
        int n = dims.size();
        int total = 8 + n * 10;
        for (byte[] p : datas) total += p.length;
        ByteBuffer bb = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        bb.put((byte) 'K').put((byte) 'T').put((byte) 'E').put((byte) 'X');
        bb.putInt(KTEX_BASE_FLAGS | (n << 13));
        for (int i = 0; i < n; i++) {
            int w = dims.get(i)[0], h = dims.get(i)[1];
            int pitch = ((w + 7) / 8) * 16;
            bb.putShort((short) w).putShort((short) h).putShort((short) pitch)
              .putInt(datas.get(i).length);
        }
        for (byte[] p : datas) bb.put(p);
        return bb.array();
    }

    // 逐级下采样 + astcenc 逐级编码, 生成完整 mipmap 链的 KTEX
    // 注意: 若只写 1 级 mipmap, 引擎启用 mipmap 过滤时纹理不完整 -> 部分机型采样为黑色
    private byte[] buildAstcKtex(File sourcePng, File tmpDir) throws Exception {
        Bitmap bmp = BitmapFactory.decodeFile(sourcePng.getAbsolutePath());
        if (bmp == null) throw new Exception("无法解码 PNG");
        java.util.List<int[]> dims = new java.util.ArrayList<>();
        java.util.List<byte[]> datas = new java.util.ArrayList<>();
        Bitmap cur = bmp;
        int w = cur.getWidth(), h = cur.getHeight();
        try {
            for (int level = 0; level < 20; level++) {
                File in = new File(tmpDir, "mip" + level + ".png");
                File out = new File(tmpDir, "mip" + level + ".astc");
                FileOutputStream fos = new FileOutputStream(in);
                cur.compress(Bitmap.CompressFormat.PNG, 100, fos);
                fos.close();
                runAstcenc("-cl", in.getAbsolutePath(), out.getAbsolutePath(), blockSize, "-" + quality);
                dims.add(new int[]{w, h});
                datas.add(stripAstcHeader(readFile(out)));
                in.delete(); out.delete();
                if (w == 1 && h == 1) break;
                int nw = Math.max(1, w / 2), nh = Math.max(1, h / 2);
                if (nw == w && nh == h) break;
                Bitmap next = Bitmap.createScaledBitmap(cur, nw, nh, true);
                if (next != cur && cur != bmp) cur.recycle();
                cur = next;
                w = nw; h = nh;
            }
        } finally {
            if (cur != null && cur != bmp) cur.recycle();
            if (bmp != null && !bmp.isRecycled()) bmp.recycle();
        }
        return packKtexLevels(dims, datas);
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
