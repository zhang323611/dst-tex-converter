package com.dsttex;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.dsttex.core.AppLog;
import com.dsttex.core.Converter;
import com.dsttex.core.Settings;
import com.dsttex.core.TexCodec;
import com.dsttex.databinding.ActivityMainBinding;
import com.dsttex.databinding.DialogInputBinding;
import com.dsttex.databinding.DialogProgressBinding;
import com.dsttex.ui.FileAdapter;
import com.dsttex.ui.FileEntry;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** 文件浏览 + 批量转换主界面 */
public class MainActivity extends AppCompatActivity implements FileAdapter.Listener {

    private static final int REQ_PERM = 200;
    private static final int REQ_PICK_DIR = 100;
    private static final int RESULT_LINES_MAX = 40;

    private ActivityMainBinding b;
    private Settings settings;
    private FileAdapter adapter;
    private File curDir;
    private boolean multiSelect;

    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        AppLog.init(this);
        settings = new Settings(this);

        b = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setSupportActionBar(b.toolbar);
        b.toolbar.setNavigationOnClickListener(v -> b.drawer.openDrawer(b.nav));

        b.nav.setNavigationItemSelectedListener(this::onNavItem);
        View header = b.nav.getHeaderView(0);
        android.widget.TextView ver = header.findViewById(R.id.navVersion);
        if (ver != null) ver.setText("v" + versionName());

        adapter = new FileAdapter(this,
                ContextCompat.getColor(this, R.color.badge_dir),
                ContextCompat.getColor(this, R.color.badge_tex),
                ContextCompat.getColor(this, R.color.badge_png),
                ContextCompat.getColor(this, R.color.badge_zip),
                ContextCompat.getColor(this, R.color.badge_file),
                getString(R.string.folder),
                getString(R.string.folder_n_items));
        b.recycler.setLayoutManager(new LinearLayoutManager(this));
        b.recycler.setAdapter(adapter);

        b.btnUp.setOnClickListener(v -> upDir());

        requestStorageAccess();

        File start = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (start == null || !start.exists()) start = getExternalFilesDir(null);
        browse(start != null ? start : getFilesDir());
    }

    // ------------------------------------------------------------------ 权限

    private void requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERM);
        }
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("需要存储权限")
                    .setMessage("为浏览全部文件，请授予「所有文件访问」权限")
                    .setPositiveButton("去授权", (d, w) -> {
                        try {
                            startActivity(new Intent(
                                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (Exception e) {
                            try {
                                startActivity(new Intent(
                                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:" + getPackageName())));
                            } catch (Exception e2) {
                                toast("请手动授予存储权限");
                            }
                        }
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        }
    }

    // ------------------------------------------------------------------ 菜单

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(multiSelect ? R.menu.menu_selection : R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_search) {
            showSearch();
            return true;
        } else if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        } else if (id == R.id.action_log) {
            startActivity(new Intent(this, LogActivity.class));
            return true;
        } else if (id == R.id.action_bookmarks) {
            showBookmarks();
            return true;
        } else if (id == R.id.action_pick_dir) {
            pickDir();
            return true;
        } else if (id == R.id.action_about) {
            showAbout();
            return true;
        } else if (id == R.id.action_toggle_all) {
            boolean all = adapter.selectedPositions().size() < adapter.size();
            adapter.selectAll(all);
            updateSelectionUi();
            return true;
        } else if (id == R.id.action_convert) {
            convertSelected();
            return true;
        } else if (id == R.id.action_rename) {
            renameSelected();
            return true;
        } else if (id == R.id.action_delete) {
            deleteSelected();
            return true;
        } else if (id == R.id.action_exit_selection) {
            exitSelection();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private boolean onNavItem(MenuItem item) {
        b.drawer.closeDrawer(b.nav);
        int id = item.getItemId();
        if (id == R.id.nav_home) {
            browse(Environment.getExternalStorageDirectory());
        } else if (id == R.id.nav_downloads) {
            File d = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (d != null && d.exists()) browse(d);
            else toast("下载目录不可用");
        } else if (id == R.id.nav_bookmarks) {
            showBookmarks();
        } else if (id == R.id.nav_search) {
            showSearch();
        } else if (id == R.id.nav_add_bookmark) {
            addBookmark(curDir);
        } else if (id == R.id.nav_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
        } else if (id == R.id.nav_log) {
            startActivity(new Intent(this, LogActivity.class));
        } else if (id == R.id.nav_about) {
            showAbout();
        }
        return true;
    }

    // ------------------------------------------------------------------ 浏览

    private void browse(File dir) {
        if (dir == null || !dir.isDirectory()) {
            toast("无法访问目录");
            return;
        }
        curDir = dir;
        exitSelection();
        adapter.submit(FileEntry.list(dir));
        updateBreadcrumb();
        updateStatus();
    }

    private void upDir() {
        File p = curDir == null ? null : curDir.getParentFile();
        if (p != null) browse(p);
        else toast("已经是顶层目录");
    }

    private void updateBreadcrumb() {
        b.crumbBar.removeAllViews();
        if (curDir == null) return;
        List<File> chain = new ArrayList<>();
        File f = curDir;
        while (f != null) {
            chain.add(0, f);
            f = f.getParentFile();
        }
        for (int i = 0; i < chain.size(); i++) {
            final File seg = chain.get(i);
            if (i > 0) {
                android.widget.TextView sep = new android.widget.TextView(this);
                sep.setText("›");
                sep.setTextColor(ContextCompat.getColor(this, R.color.badge_file));
                sep.setPadding(dp(4), 0, dp(4), 0);
                b.crumbBar.addView(sep);
            }
            android.widget.TextView t = new android.widget.TextView(this);
            String nm = seg.getName();
            t.setText(TextUtils.isEmpty(nm) ? "/" : nm);
            t.setTextSize(13);
            t.setTextColor(i == chain.size() - 1
                    ? ContextCompat.getColor(this, R.color.brand_primary)
                    : ContextCompat.getColor(this, R.color.badge_file));
            t.setPadding(dp(6), dp(8), dp(6), dp(8));
            t.setOnClickListener(v -> browse(seg));
            b.crumbBar.addView(t);
        }
        b.crumbScroll.post(() -> b.crumbScroll.fullScroll(View.FOCUS_RIGHT));
    }

    private void updateStatus() {
        String mode = modeLabel();
        String threads = settings.threads() < 0 ? "auto" : String.valueOf(settings.threads());
        b.status.setText(getString(R.string.status_fmt, mode, TexCodec.ASTC_BLOCK,
                settings.quality(), adapter.size(), threads));
    }

    private String modeLabel() {
        switch (settings.mode()) {
            case PNG2TEX: return "PNG→TEX";
            case TEX2PNG: return "TEX→PNG";
            case DXT2ASTC: return "TEX→ASTC";
            default: return getString(R.string.status_auto);
        }
    }

    // ------------------------------------------------------------- 列表回调

    @Override
    public void onClick(int position, FileEntry e) {
        if (multiSelect) {
            adapter.setSelected(position, !adapter.isSelected(position));
            if (adapter.selectedPositions().isEmpty()) exitSelection();
            else updateSelectionUi();
            return;
        }
        File f = e.file;
        if (e.dir) {
            browse(f);
        } else if (e.lower.endsWith(".zip")) {
            chooseZipAction(f);
        } else if (e.lower.endsWith(".dyn")) {
            openDyn(f);
        } else if (e.lower.endsWith(".tex")) {
            PreviewActivity.start(this, f);
        } else {
            toast("长按进入多选后可转换");
        }
    }

    @Override
    public boolean onLongClick(int position, FileEntry e) {
        if (!multiSelect) enterSelection(position);
        else {
            adapter.setSelected(position, !adapter.isSelected(position));
            updateSelectionUi();
        }
        return true;
    }

    private void enterSelection(int position) {
        multiSelect = true;
        adapter.setSelected(position, true);
        updateSelectionUi();
    }

    private void exitSelection() {
        multiSelect = false;
        adapter.clearSelection();
        invalidateOptionsMenu();
        b.toolbar.setTitle(R.string.app_name);
    }

    private void updateSelectionUi() {
        int n = adapter.selectedPositions().size();
        if (!multiSelect) return;
        b.toolbar.setTitle(getString(R.string.selected_count, n));
        invalidateOptionsMenu();
    }

    // ------------------------------------------------------------------ 操作

    private void renameSelected() {
        List<FileEntry> sel = adapter.selectedFilesOnly();
        if (sel.size() != 1) {
            toast("请只勾选一个文件重命名");
            return;
        }
        final File f = sel.get(0).file;
        DialogInputBinding in = DialogInputBinding.inflate(getLayoutInflater());
        in.input.setText(f.getName());
        int dot = f.getName().lastIndexOf('.');
        in.input.setSelection(0, dot > 0 ? dot : f.getName().length());
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.rename)
                .setView(in.getRoot())
                .setPositiveButton(R.string.ok, (d, w) -> {
                    String nn = in.input.getText() == null ? "" : in.input.getText().toString().trim();
                    if (nn.isEmpty() || nn.equals(f.getName())) return;
                    if (f.renameTo(new File(f.getParentFile(), nn))) toast("已重命名");
                    else toast("重命名失败");
                    browse(curDir);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void deleteSelected() {
        final List<FileEntry> sel = adapter.selectedFilesOnly();
        if (sel.isEmpty()) {
            toast("请先勾选要删除的文件");
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("删除 " + sel.size() + " 个文件？")
                .setMessage("删除后不可恢复")
                .setPositiveButton(R.string.delete, (d, w) -> {
                    int ok = 0;
                    for (FileEntry e : sel) if (e.file.delete()) ok++;
                    toast("已删除 " + ok + " 个");
                    browse(curDir);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------ 批量转换

    private void convertSelected() {
        if (!TexCodec.available()) {
            toast("转换引擎不可用");
            return;
        }
        final List<File> texFiles = new ArrayList<>();
        final List<File> zipFiles = new ArrayList<>();
        for (Integer i : new ArrayList<>(adapter.selectedPositions())) {
            if (i < 0 || i >= adapter.size()) continue;
            FileEntry e = adapter.get(i);
            if (e.dir) collect(e.file, texFiles, zipFiles);
            else if (e.lower.endsWith(".zip")) zipFiles.add(e.file);
            else texFiles.add(e.file);
        }
        if (texFiles.isEmpty() && zipFiles.isEmpty()) {
            toast("没有可转换的 .tex/.png/zip");
            return;
        }
        final int total = texFiles.size() + zipFiles.size();
        final int threads = settings.activeThreads();
        final Settings.Mode mode = settings.mode();
        final String quality = settings.quality();
        final boolean backup = settings.backup();
        final boolean nested = settings.nestedZip();
        exitSelection();

        DialogProgressBinding pb = DialogProgressBinding.inflate(getLayoutInflater());
        pb.progressTitle.setText(threads > 1
                ? getString(R.string.converting_n, threads) : getString(R.string.converting));
        pb.progressBar.setMax(total);
        pb.progressText.setText(R.string.preparing);
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(pb.getRoot())
                .setCancelable(true)
                .setNegativeButton(R.string.cancel, (d, w) -> cancelled.set(true))
                .create();
        dialog.setOnCancelListener(d -> cancelled.set(true));
        dialog.show();
        b.progress.setVisibility(View.VISIBLE);
        b.progress.setMax(total);

        final List<String> results = Collections.synchronizedList(new ArrayList<String>());
        final AtomicInteger ok = new AtomicInteger();
        final AtomicInteger skip = new AtomicInteger();
        final AtomicInteger fail = new AtomicInteger();
        final AtomicInteger done = new AtomicInteger();
        final long[] lastUi = {0};

        new Thread(() -> {
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<Future<?>> futures = new ArrayList<>();
            try {
                for (final File f : texFiles) {
                    futures.add(pool.submit(() -> {
                        if (cancelled.get()) return;
                        File tmp = tmpDir("c");
                        try {
                            String r = Converter.convertFile(f, f.getParentFile(), tmp, mode, quality, backup);
                            ok.incrementAndGet();
                            results.add("OK   " + f.getName() + " -> " + r);
                        } catch (Converter.Skip s) {
                            skip.incrementAndGet();
                            results.add("SKIP " + f.getName() + ": " + s.getMessage());
                        } catch (Exception ex) {
                            fail.incrementAndGet();
                            results.add("FAIL " + f.getName() + ": " + ex.getMessage());
                            AppLog.e("转换 " + f.getName(), ex);
                        } finally {
                            TexCodec.purgeDir(tmp);
                        }
                        progress(pb, cancelled, lastUi, done.incrementAndGet(), total, dialog);
                    }));
                }
                for (final File z : zipFiles) {
                    futures.add(pool.submit(() -> {
                        if (cancelled.get()) return;
                        File tmp = tmpDir("z");
                        try {
                            String r = Converter.convertZip(z, tmp, mode, quality, backup, nested);
                            ok.incrementAndGet();
                            results.add("OK   " + z.getName() + " -> " + r);
                        } catch (Exception ex) {
                            fail.incrementAndGet();
                            results.add("FAIL " + z.getName() + ": " + ex.getMessage());
                            AppLog.e("转换 " + z.getName(), ex);
                        } finally {
                            TexCodec.purgeDir(tmp);
                        }
                        progress(pb, cancelled, lastUi, done.incrementAndGet(), total, dialog);
                    }));
                }
            } finally {
                pool.shutdown();
            }
            try {
                while (!pool.awaitTermination(200, TimeUnit.MILLISECONDS)) {
                    if (cancelled.get()) {
                        pool.shutdownNow();
                        break;
                    }
                }
            } catch (InterruptedException ie) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
            for (Future<?> f : futures) {
                if (f.isCancelled()) skip.incrementAndGet();
            }
            final int fok = ok.get(), fskip = skip.get(), ffail = fail.get();
            ui.post(() -> {
                dialog.dismiss();
                b.progress.setVisibility(View.GONE);
                if (isFinishing()) return;
                new MaterialAlertDialogBuilder(MainActivity.this)
                        .setTitle(cancelled.get() ? R.string.convert_cancelled : R.string.convert_done)
                        .setMessage(getString(R.string.result_summary, fok, fskip, ffail)
                                + "\n" + getString(backup ? R.string.backup_on_note : R.string.backup_off_note)
                                + "\n\n" + body(results))
                        .setPositiveButton(R.string.ok, null)
                        .setNeutralButton(R.string.nav_log,
                                (d, w) -> startActivity(new Intent(MainActivity.this, LogActivity.class)))
                        .show();
                updateStatus();
            });
        }).start();
    }

    private void progress(DialogProgressBinding pb, AtomicBoolean cancelled, long[] lastUi,
                          int done, int total, androidx.appcompat.app.AlertDialog dialog) {
        if (cancelled.get()) return;
        long now = System.currentTimeMillis();
        if (now - lastUi[0] < 120 && done < total) return;
        lastUi[0] = now;
        ui.post(() -> {
            pb.progressBar.setProgress(done);
            pb.progressText.setText(done + " / " + total);
            b.progress.setProgress(done);
        });
    }

    private String body(List<String> results) {
        if (results.isEmpty()) return "（无输出）";
        if (results.size() <= RESULT_LINES_MAX) return TextUtils.join("\n", results);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < RESULT_LINES_MAX; i++) sb.append(results.get(i)).append('\n');
        sb.append(getString(R.string.result_truncated, results.size() - RESULT_LINES_MAX));
        return sb.toString();
    }

    private File tmpDir(String tag) {
        File d = new File(getCacheDir(), "task_" + tag + "_" + Thread.currentThread().getId());
        d.mkdirs();
        return d;
    }

    private void collect(File dir, List<File> texFiles, List<File> zipFiles) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) {
                collect(f, texFiles, zipFiles);
                continue;
            }
            String n = f.getName().toLowerCase();
            if (n.endsWith(".bak")) continue;
            if (n.endsWith(".zip")) zipFiles.add(f);
            else if (n.endsWith(".tex") || n.endsWith(".png")) texFiles.add(f);
        }
    }

    // -------------------------------------------------------------- 搜索/书签

    private void showSearch() {
        DialogInputBinding in = DialogInputBinding.inflate(getLayoutInflater());
        in.input.setHint("输入文件名关键词");
        new MaterialAlertDialogBuilder(this)
                .setTitle("搜索文件（当前目录及子目录）")
                .setView(in.getRoot())
                .setPositiveButton(R.string.search, (d, w) -> {
                    String kw = in.input.getText() == null ? "" : in.input.getText().toString().trim();
                    if (kw.isEmpty()) {
                        toast("请输入关键词");
                        return;
                    }
                    doSearch(kw.toLowerCase(Locale.getDefault()));
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void doSearch(String kw) {
        final List<File> found = new ArrayList<>();
        searchRec(curDir, kw, found, 500);
        if (found.isEmpty()) {
            toast("未找到匹配文件");
            return;
        }
        final List<String> names = new ArrayList<>();
        for (File f : found) names.add(f.getName() + "\n  " + f.getParent());
        new MaterialAlertDialogBuilder(this)
                .setTitle("找到 " + found.size() + " 个")
                .setItems(names.toArray(new String[0]), (d, i) -> {
                    File f = found.get(i);
                    browse(f.getParentFile());
                    for (int j = 0; j < adapter.size(); j++) {
                        if (adapter.get(j).name.equals(f.getName())) {
                            b.recycler.smoothScrollToPosition(j);
                            toast("已定位: " + f.getName());
                            break;
                        }
                    }
                })
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private void searchRec(File dir, String kw, List<File> out, int limit) {
        if (dir == null || out.size() >= limit) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (out.size() >= limit) return;
            if (f.isDirectory()) searchRec(f, kw, out, limit);
            else if (f.getName().toLowerCase(Locale.getDefault()).contains(kw)) out.add(f);
        }
    }

    private void showBookmarks() {
        Set<String> marks = settings.bookmarks();
        if (marks.isEmpty()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.bookmarks)
                    .setMessage("当前无书签。点「加当前目录」把当前目录加入书签。")
                    .setPositiveButton("加当前目录", (d, w) -> addBookmark(curDir))
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return;
        }
        final List<String> list = new ArrayList<>(marks);
        Collections.sort(list);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.bookmarks)
                .setItems(list.toArray(new String[0]), (d, i) -> {
                    File f = new File(list.get(i));
                    if (f.isDirectory()) browse(f);
                    else toast("目录不存在");
                })
                .setPositiveButton("加当前目录", (d, w) -> addBookmark(curDir))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void addBookmark(File dir) {
        if (dir == null) return;
        Set<String> marks = settings.bookmarks();
        marks.add(dir.getAbsolutePath());
        settings.setBookmarks(marks);
        toast("已加书签: " + dir.getName());
    }

    private void pickDir() {
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_PICK_DIR);
        } catch (Exception e) {
            toast("无法打开目录选择器");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK_DIR || res != Activity.RESULT_OK || data == null || data.getData() == null) return;
        String path = safToPath(data.getData());
        if (path == null) toast("无法解析该目录（仅支持内部存储）");
        else browse(new File(path));
    }

    private String safToPath(Uri uri) {
        String s = uri.toString();
        int i = s.indexOf("primary:");
        if (i < 0) return null;
        String sub = s.substring(i + "primary:".length());
        String root = Environment.getExternalStorageDirectory().getAbsolutePath();
        return sub.isEmpty() ? root : root + "/" + sub;
    }

    // ----------------------------------------------------------- zip / dyn

    private void chooseZipAction(File zip) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(zip.getName())
                .setItems(new String[]{getString(R.string.anim_preview), getString(R.string.anim_edit)}, (d, i) -> {
                    if (i == 0) AnimPreviewActivity.start(this, zip);
                    else AnimEditor.start(this, zip);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void openDyn(final File dyn) {
        toast("正在解密 " + dyn.getName() + " …");
        new Thread(() -> {
            File tmp = tmpDir("dyn");
            try {
                byte[] dec = TexCodec.dynDecrypt(TexCodec.readFile(dyn));
                if (!TexCodec.isPk(dec)) {
                    ui.post(() -> toast("解密结果不是 zip（可能格式不符）"));
                    return;
                }
                File zip = new File(tmp, TexCodec.baseName(dyn) + ".zip");
                TexCodec.writeFile(zip, dec);
                final List<String> items = new ArrayList<>();
                long total = 0;
                java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zip);
                try {
                    java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
                    while (en.hasMoreElements()) {
                        java.util.zip.ZipEntry ze = en.nextElement();
                        if (ze.isDirectory()) continue;
                        items.add(ze.getName() + "    " + TexCodec.humanSize(ze.getSize()));
                        total += Math.max(0, ze.getSize());
                    }
                } finally {
                    zf.close();
                }
                final long tot = total;
                ui.post(() -> {
                    if (isFinishing()) return;
                    if (items.isEmpty()) {
                        toast("zip 内无文件");
                        return;
                    }
                    new MaterialAlertDialogBuilder(this)
                            .setTitle(dyn.getName() + "  解密后 " + items.size() + " 项 / " + TexCodec.humanSize(tot))
                            .setItems(items.toArray(new String[0]), null)
                            .setPositiveButton("导出 zip", (d, w) -> {
                                try {
                                    File out = uniqueFile(dyn.getParentFile(), TexCodec.baseName(dyn) + "_decoded.zip");
                                    TexCodec.copyFile(zip, out);
                                    toast("已导出: " + out.getName());
                                } catch (Exception e) {
                                    toast("导出失败: " + e.getMessage());
                                }
                            })
                            .setNeutralButton("重新加密", (d, w) -> {
                                try {
                                    File out = uniqueFile(dyn.getParentFile(), TexCodec.baseName(dyn) + "_re.dyn");
                                    TexCodec.writeFileAtomic(out, TexCodec.dynEncrypt(dec));
                                    toast("已写出: " + out.getName());
                                } catch (Exception e) {
                                    toast("加密失败: " + e.getMessage());
                                }
                            })
                            .setNegativeButton(R.string.close, null)
                            .show();
                });
            } catch (Exception e) {
                AppLog.e("dyn 解密失败", e);
                ui.post(() -> toast("dyn 解密失败: " + e.getMessage()));
            } finally {
                // cache 里的临时包不删也没事，但别积累
                // （导出/加密用的是同一个临时文件，所以只在对话框关闭后由系统 cache 治理）
            }
        }).start();
    }

    private File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        String base = name, ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        for (int i = 2; i < 1000; i++) {
            File c = new File(dir, base + "_" + i + ext);
            if (!c.exists()) return c;
        }
        return f;
    }

    // ---------------------------------------------------------------- 关于

    private void showAbout() {
        final String repo = "https://github.com/zhang323611/dst-tex-converter";
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.about)
                .setMessage("饥荒手机版纹理转换工具  v" + versionName() + "\n\n"
                        + "· DXT1/3/5 → ASTC(手机版)\n"
                        + "· PNG → ASTC\n"
                        + "· ASTC → PNG\n"
                        + "· zip 内批量转换（含嵌套）\n"
                        + "· .dyn 解密 / 重新加密\n"
                        + "· 动画预览与编辑\n\n"
                        + "ASTC 8x8(2bpp) 为手机版标准格式。\n\n"
                        + "GitHub：" + repo)
                .setPositiveButton("访问仓库", (d, w) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(repo)));
                    } catch (Exception e) {
                        toast("无法打开链接");
                    }
                })
                .setNeutralButton("复制链接", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("link", repo));
                    toast("已复制");
                })
                .setNegativeButton(R.string.close, null)
                .show();
    }

    // ---------------------------------------------------------------- 杂项

    @Override
    public void onBackPressed() {
        if (b.drawer.isDrawerOpen(b.nav)) {
            b.drawer.closeDrawer(b.nav);
        } else if (multiSelect) {
            exitSelection();
        } else if (curDir != null && curDir.getParentFile() != null) {
            upDir();
        } else {
            super.onBackPressed();
        }
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
