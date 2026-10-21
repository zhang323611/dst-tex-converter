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
    // DST 手游 .tex 的 comp=24 语义就是「ASTC 8x8」，KTEX 头不携带块尺寸信息。
    // 之前设置里提供了 4x4/6x6/10x10/12x12 五档，但 writeAstcHeader() 与 pitch/datasz
    // 全部按 8x8 写死 —— 选非 8x8 会产出「头部声称 8x8、数据是别的块尺寸」的废纹理，
    // 连本应用自己都读不回来。故收敛为唯一值，不再暴露无效选项。
    private static final String ASTC_BLOCK = "8x8";
    private String quality = "medium";
    private String convertMode = "auto"; // auto / png2tex / tex2png / dxt2astc
    // 转换是原地替换，没有备份就没有后悔药 —— 默认开启
    private boolean autoBackup = true;
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
    // 调试模式: 控制预览画布上的诊断叠层(诊断代码全部保留, 仅按开关显示)
    private boolean debugMode = false;

    // 处理压缩包内嵌的压缩包(逐层递归)
    private boolean nestedZip = true;

    // 跳过统计通过参数传递(见 int[] skip), 不用实例字段 —— 批量转换为多线程,
    // 实例字段会被并发调用互相覆盖, 导致提示数字与实际不符。

    // ---------- 运行日志(便于排查转换失败) ----------
    private static final int LOG_MAX = 500;
    private static final long LOG_FILE_MAX = 2L * 1024 * 1024;   // 落盘日志上限 2MB
    private static final java.util.ArrayDeque<String> LOG = new java.util.ArrayDeque<>();
    private static File logFile;

    /** 追加一行日志: 内存环形缓冲 + 落盘(cacheDir/dsh_tex.log) */
    static synchronized void log(String msg) {
        String line = new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS",
                java.util.Locale.US).format(new java.util.Date()) + "  " + msg;
        LOG.addLast(line);
        while (LOG.size() > LOG_MAX) LOG.removeFirst();
        if (logFile != null) {
            try {
                // 防止日志在 cacheDir 里无限增长(批量转换会写几千行)
                if (logFile.length() > LOG_FILE_MAX) logFile.delete();
                java.io.FileWriter w = new java.io.FileWriter(logFile, true);
                w.write(line + "\n");
                w.close();
            } catch (Throwable ignored) { }
        }
    }

    /** 记录异常(含调用栈), 供日志查看 */
    static void logErr(String tag, Throwable t) {
        log("!! " + tag + ": " + t);
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        for (String ln : sw.toString().split("\n")) log("    " + ln.trim());
    }

    static synchronized String logText() {
        StringBuilder sb = new StringBuilder();
        for (String l : LOG) sb.append(l).append('\n');
        return sb.toString();
    }

    static synchronized void clearLog() {
        LOG.clear();
        if (logFile != null) logFile.delete();
        log("日志已清空");
    }

    // 临时文件全局序号。
    // 说明: tempWorkingDir() 已按任务/线程隔离目录(如 task_stzip_<n>), 目录内操作又是顺序的,
    // 因此固定文件名在批量路径下本是安全的。这里仍加唯一序号, 是为了防御:
    //   (a) 同一目录被复用的新调用路径(如 task_prev / task_anim 是固定名, 跨会话共享)
    //   (b) 将来若把递归改为并行, 固定名会立刻变成数据竞争
    // 代价仅是一个 AtomicInteger 自增。
    private static final java.util.concurrent.atomic.AtomicInteger TMP_SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    // 并发转换线程数(1=单线程, 2/4=固定, -1=自动检测CPU核心数)
    private int threadCount = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("texconv", MODE_PRIVATE);
        autoBackup = prefs.getBoolean("autoBackup", true);
        threadCount = prefs.getInt("threadCount", 1);
        debugMode = prefs.getBoolean("debugMode", false);
        nestedZip = prefs.getBoolean("nestedZip", true);
        // 之前这三项只改内存字段、从不落盘，重启后静默回到默认值
        convertMode = prefs.getString("convertMode", "auto");
        quality = prefs.getString("quality", "medium");
        logFile = new File(getCacheDir(), "dsh_tex.log");
        log("==== 启动 v1.4  threadCount=" + threadCount + " mode=" + convertMode
            + " nestedZip=" + nestedZip + " backup=" + autoBackup + " ====");

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
                .setPositiveButton("去授权", (d, w) -> {
                    try {
                        startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        // 部分 ROM 没有这个 Activity，退化到应用详情页
                        try {
                            startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName())));
                        } catch (Exception e2) { toast("无法打开设置页，请手动授予存储权限"); }
                    }
                })
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
    // 返回 "OK" / "SKIP" / null(失败)，供调用方分别计数
    private String runConvertTaskMT(final File input, final File outputDir,
            final java.util.List<String> results) {
        final String tid = String.valueOf(Thread.currentThread().getId());
        try {
            File dir = tempWorkingDir(tid);
            try {
                String r = convertFile(input, outputDir, dir);
                synchronized (results) { results.add("OK   " + input.getName() + " -> " + r); }
                return "OK";
            } finally {
                purgeDir(dir);
            }
        } catch (SkipException se) {
            synchronized (results) { results.add("SKIP " + input.getName() + ": " + se.getMessage()); }
            return "SKIP";
        } catch (Exception e) {
            synchronized (results) {
                results.add("FAIL " + input.getName() + ": " + e.getMessage());
                logErr("转换 " + input.getName(), e);
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
                synchronized (results) { results.add("OK   " + zip.getName() + " -> " + r); }
                return "OK";
            } finally {
                purgeDir(dir);
            }
        } catch (Exception e) {
            synchronized (results) {
                results.add("FAIL " + zip.getName() + ": " + e.getMessage());
                logErr("转换 " + zip.getName(), e);
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
        popup.getMenu().add("查看日志");
        popup.getMenu().add("关于");
        popup.setOnMenuItemClickListener(item -> {
            String t = item.getTitle().toString();
            if (t.equals("转换模式设置")) showSettings();
            else if (t.equals("搜索文件")) showSearch();
            else if (t.equals("书签")) showBookmarks();
            else if (t.equals("选目录")) pickDir();
            else if (t.equals("查看日志")) showLog();
            else if (t.equals("关于")) showAbout();
            return true;
        });
        popup.show();
    }

    private void showLog() {
        log("---- 打开日志查看 ----");
        final String txt = logText();
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(txt);
        tv.setTextSize(10);
        tv.setTextIsSelectable(true);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setPadding(dp(8), dp(8), dp(8), dp(8));
        sv.addView(tv);
        new AlertDialog.Builder(this)
            .setTitle("运行日志 (" + txt.split("\n").length + " 行)")
            .setView(sv)
            .setPositiveButton("复制", (d, w) -> {
                android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("log", txt));
                toast("日志已复制到剪贴板");
            })
            .setNeutralButton("分享", (d, w) -> {
                try {
                    File lf = new File(getCacheDir(), "dsh_tex.log");
                    writeFile(lf, txt.getBytes("UTF-8"));
                    android.content.Intent it = new android.content.Intent(android.content.Intent.ACTION_SEND);
                    it.setType("text/plain");
                    it.putExtra(android.content.Intent.EXTRA_TEXT, txt);
                    startActivity(android.content.Intent.createChooser(it, "分享日志"));
                } catch (Throwable t2) { toast("分享失败: " + t2.getMessage()); }
            })
            .setNegativeButton("清空", (d, w) -> { clearLog(); toast("日志已清空"); })
            .show();
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

        // 块大小（固定 8x8：KTEX 头不携带块尺寸，其它档位会产出废纹理，故不给选项）
        TextView t1 = new TextView(this);
        t1.setText("ASTC 块大小：" + ASTC_BLOCK + "（手机版标准，2bpp；KTEX 格式不支持其它块尺寸）");
        t1.setTextSize(13);
        t1.setTextColor(C_TEXT_SUB);
        t1.setPadding(0, 12, 0, 0);
        panel.addView(t1);

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
        cbBackup.setText("自动备份 .bak（转换前备份原文件，默认开启）");
        cbBackup.setChecked(autoBackup);
        cbBackup.setPadding(0, 12, 0, 0);
        panel.addView(cbBackup);

        // 调试模式
        final CheckBox cbDebug = new CheckBox(this);
        cbDebug.setText("调试模式（预览画布上显示诊断信息、三角形描边与图集缩略图）");
        cbDebug.setChecked(debugMode);
        cbDebug.setPadding(0, 12, 0, 0);
        panel.addView(cbDebug);

        // 处理内嵌压缩包
        final CheckBox cbNested = new CheckBox(this);
        cbNested.setText("处理压缩包内的压缩包（内层若有 tex 也一并转换，逐层递归）");
        cbNested.setChecked(nestedZip);
        cbNested.setPadding(0, 12, 0, 0);
        panel.addView(cbNested);

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
                int q = rgQual.getCheckedRadioButtonId();
                if (m >= 0 && m < modeVals.length) convertMode = modeVals[m];
                if (q >= 0 && q < qualVals.length) quality = qualVals[q];
                autoBackup = cbBackup.isChecked();
                debugMode = cbDebug.isChecked();
                nestedZip = cbNested.isChecked();
                // 一次性提交，避免 5 次 apply 各自触发监听
                prefs.edit()
                     .putString("convertMode", convertMode)
                     .putString("quality", quality)
                     .putBoolean("autoBackup", autoBackup)
                     .putBoolean("nestedZip", nestedZip)
                     .putBoolean("debugMode", debugMode)
                     .apply();
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
        statusView.setText("方式 " + modeLabel() + "  " + ASTC_BLOCK + "/" + quality
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
                try {
                    java.util.Enumeration<? extends ZipEntry> en = zf.entries();
                    while (en.hasMoreElements()) {
                        ZipEntry ze = en.nextElement();
                        if (ze.isDirectory()) continue;
                        items.add(ze.getName() + "    " + humanSize(ze.getSize()));
                        total += Math.max(0, ze.getSize());
                    }
                } finally {
                    zf.close();
                }
                final long tot = total;
                runOnUiThread(() -> {
                    if (items.isEmpty()) { toast("zip 内无文件"); return; }
                    new AlertDialog.Builder(this)
                        .setTitle(dyn.getName() + "  解密后 " + items.size() + " 项 / " + humanSize(tot))
                        .setItems(items.toArray(new String[0]), null)
                        .setPositiveButton("导出 zip", (d, w) -> {
                            try {
                                // 不要直接写 baseName+".zip": DST 模组里 xxx.dyn 与 xxx.zip 往往同时存在,
                                // 直接覆盖会毁掉真实文件
                                File outZip = new File(dyn.getParentFile(), baseName(dyn) + "_decoded.zip");
                                for (int i = 2; outZip.exists() && i < 100; i++)
                                    outZip = new File(dyn.getParentFile(), baseName(dyn) + "_decoded_" + i + ".zip");
                                copyFile(zip, outZip);
                                toast("已导出: " + outZip.getName());
                            } catch (Exception e) { toast("导出失败: " + e.getMessage()); }
                        })
                        .setNeutralButton("重新加密", (d, w) -> {
                            try {
                                File outDyn = new File(dyn.getParentFile(), baseName(dyn) + "_re.dyn");
                                for (int i = 2; outDyn.exists() && i < 100; i++)
                                    outDyn = new File(dyn.getParentFile(), baseName(dyn) + "_re_" + i + ".dyn");
                                writeFileAtomic(outDyn, dynEncrypt(dec));
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
            File tmpDir = tempWorkingDir("prev_" + TMP_SEQ.incrementAndGet());
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


    // ================= DST 动画 (anim.bin / build.bin) =================
    // 格式依据 ktools/krane, 已用真实样本逐字节验证(解析到文件末尾剩余 0)

    static class BinReader {
        final byte[] d; int p = 0;
        BinReader(byte[] b) { d = b; }
        int u8() { return d[p++] & 0xFF; }
        int u32() {
            int v = (d[p] & 0xFF) | ((d[p+1] & 0xFF) << 8)
                  | ((d[p+2] & 0xFF) << 16) | ((d[p+3] & 0xFF) << 24);
            p += 4; return v;
        }
        long u32l() { return u32() & 0xFFFFFFFFL; }
        float f32() { return Float.intBitsToFloat(u32()); }
        String str() {
            int n = u32();
            String s = new String(d, p, n, java.nio.charset.StandardCharsets.UTF_8);
            p += n; return s;
        }
        int left() { return d.length - p; }
    }

    static class BFrame {
        int alphaidx;      // 该帧顶点在全局顶点区中的起始下标
        int framenum, duration;
        float x, y, w, h;
        int ntris;
        float[] xyz;   // ntris*9: 每三角形 3 顶点 × (x,y,z)
        float[] uv;    // ntris*9: 每三角形 3 顶点 × (u,v,w)
    }
    static class BSymbol { long hash; String name; BFrame[] frames; }
    static class BuildFile {
        String name; String[] atlases;
        final java.util.LinkedHashMap<Long, BSymbol> symbols = new java.util.LinkedHashMap<>();
    }
    static class AElement {
        long hash; int buildFrame; long layer;
        float a, b, c, d, tx, ty, z;
        int txOffset = -1;   // tx 字段在 anim.bin 中的字节偏移(用于原地改写)
    }
    static class AFrame { float x, y, w, h; AElement[] elems; }
    static class AAnim {
        String name; int facing; long bank; float frameRate; AFrame[] frames;
    }
    static class AnimFile { AAnim[] anims; }

    static BuildFile parseBuild(byte[] d) throws Exception {
        if (d.length < 8 || d[0] != 'B' || d[1] != 'I' || d[2] != 'L' || d[3] != 'D')
            throw new Exception("不是 build.bin");
        BinReader r = new BinReader(d);
        r.p = 4; r.u32();
        BuildFile b = new BuildFile();
        int nsym = r.u32();
        r.u32();
        b.name = r.str();
        int natlas = r.u32();
        b.atlases = new String[natlas];
        for (int i = 0; i < natlas; i++) b.atlases[i] = r.str();
        BSymbol[] syms = new BSymbol[nsym];
        for (int i = 0; i < nsym; i++) {
            BSymbol s = new BSymbol();
            s.hash = r.u32l();
            int nf = r.u32();
            s.frames = new BFrame[nf];
            for (int j = 0; j < nf; j++) {
                BFrame f = new BFrame();
                f.framenum = r.u32(); f.duration = r.u32();
                f.x = r.f32(); f.y = r.f32(); f.w = r.f32(); f.h = r.f32();
                f.alphaidx = r.u32();      // 顶点起始下标(此前被丢弃 -> 顶点错配)
                f.ntris = r.u32() / 3;
                s.frames[j] = f;
            }
            syms[i] = s;
        }
        // 顶点区: 物理顺序与符号表顺序无关, 必须用每帧的 alphaidx 定位。
        // (alchmy 那种单符号资产顶点恰好顺序排列, 顺序读法碰巧正确;
        //  多符号角色的顶点区被打乱重排, 顺序读法会把别的帧的顶点配错。)
        int totalVerts = r.u32();
        if (totalVerts < 0) throw new Exception("顶点数异常: " + totalVerts);
        float[] vx = new float[totalVerts], vy = new float[totalVerts], vz = new float[totalVerts];
        float[] vu = new float[totalVerts], vv = new float[totalVerts], vw = new float[totalVerts];
        for (int i = 0; i < totalVerts; i++) {
            vx[i] = r.f32(); vy[i] = r.f32(); vz[i] = r.f32();
            vu[i] = r.f32(); vv[i] = r.f32(); vw[i] = r.f32();
        }
        for (BSymbol s : syms) {
            for (BFrame f : s.frames) {
                int n = f.ntris * 3;
                f.xyz = new float[n * 3];
                f.uv  = new float[n * 3];
                for (int k = 0; k < n; k++) {
                    int idx = f.alphaidx + k;
                    if (idx < 0 || idx >= totalVerts) continue;   // 越界保持 0
                    f.xyz[k*3    ] = vx[idx];
                    f.xyz[k*3 + 1] = vy[idx];
                    f.xyz[k*3 + 2] = vz[idx];
                    f.uv [k*3    ] = vu[idx];
                    f.uv [k*3 + 1] = vv[idx];
                    f.uv [k*3 + 2] = vw[idx];
                }
            }
        }
        int htsz = r.u32();
        for (int i = 0; i < htsz; i++) {
            long h = r.u32l();
            String nm = r.str();
            for (BSymbol s : syms) if (s.hash == h) s.name = nm;
        }
        for (BSymbol s : syms) b.symbols.put(s.hash, s);
        return b;
    }

    static AnimFile parseAnim(byte[] d) throws Exception {
        if (d.length < 8 || d[0] != 'A' || d[1] != 'N' || d[2] != 'I' || d[3] != 'M')
            throw new Exception("不是 anim.bin");
        BinReader r = new BinReader(d);
        r.p = 4; r.u32();
        r.u32(); r.u32(); r.u32();
        int nanim = r.u32();
        AnimFile a = new AnimFile();
        a.anims = new AAnim[nanim];
        for (int i = 0; i < nanim; i++) {
            AAnim an = new AAnim();
            an.name = r.str();
            an.facing = r.u8();          // 注意: 1 字节
            an.bank = r.u32l();
            an.frameRate = r.f32();
            int nf = r.u32();
            an.frames = new AFrame[nf];
            for (int j = 0; j < nf; j++) {
                AFrame f = new AFrame();
                f.x = r.f32(); f.y = r.f32(); f.w = r.f32(); f.h = r.f32();
                int nev = r.u32();
                for (int k = 0; k < nev; k++) r.u32();
                int nel = r.u32();
                f.elems = new AElement[nel];
                for (int k = 0; k < nel; k++) {
                    AElement e = new AElement();
                    e.hash = r.u32l();
                    e.buildFrame = r.u32();
                    e.layer = r.u32l();
                    e.a = r.f32(); e.b = r.f32(); e.c = r.f32();
                    e.d = r.f32();
                    e.txOffset = r.p;          // 记录 tx 起始偏移
                    e.tx = r.f32(); e.ty = r.f32();
                    e.z = r.f32();
                    f.elems[k] = e;
                }
                an.frames[j] = f;
            }
            a.anims[i] = an;
        }
        return a;
    }

    /**
     * 从压缩包载入动画资产(供编辑器「导入」使用)。
     * 解析 build.bin / anim.bin 并解码图集, 结果写入共享静态字段。
     * @return 成功与否
     */
    static boolean loadAnimAsset(android.content.Context ctx, java.io.File zip) {
        try {
            java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zip);
            byte[] animD = null, buildD = null, atlasD = null;
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry ze = en.nextElement();
                String ln = ze.getName().toLowerCase();
                if (ln.endsWith("anim.bin")) animD = readAllStatic(zf.getInputStream(ze));
                else if (ln.endsWith("build.bin")) buildD = readAllStatic(zf.getInputStream(ze));
                else if (ln.endsWith(".tex") && atlasD == null) atlasD = readAllStatic(zf.getInputStream(ze));
            }
            zf.close();
            if (animD == null || buildD == null || atlasD == null) return false;

            BuildFile build = parseBuild(buildD);
            AnimFile anim = parseAnim(animD);
            if (anim.anims.length == 0) return false;

            // 解码图集: KTEX(ASTC) -> PNG -> Bitmap
            java.io.File dir = new java.io.File(ctx.getCacheDir(), "import");
            dir.mkdirs();
            java.io.File astc = new java.io.File(dir, "a.astc");
            java.io.File png = new java.io.File(dir, "a.png");
            int comp = readTexCompressionStatic(atlasD);
            if (comp == 24) {
                KtexInfo info = unpackKtex(atlasD);
                byte[] full = new byte[info.raw.length + 16];
                writeAstcHeader(full, info.w, info.h);
                System.arraycopy(info.raw, 0, full, 16, info.raw.length);
                java.io.FileOutputStream fo = new java.io.FileOutputStream(astc);
                fo.write(full); fo.close();
                runAstcenc("-dl", astc.getAbsolutePath(), png.getAbsolutePath());
            } else {
                return false;   // 仅支持 ASTC 图集
            }
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(png.getAbsolutePath());
            if (bmp == null) return false;

            sBuild = build;
            sAnim = anim;
            sAtlas = bmp;
            sAnimBytes = animD;
            sAnimZip = zip;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static byte[] readAllStatic(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toByteArray();
    }

    private static int readTexCompressionStatic(byte[] b) throws Exception {
        if (b.length < 8 || b[0] != 'K' || b[1] != 'T' || b[2] != 'E' || b[3] != 'X')
            throw new Exception("不是 KTEX 文件");
        int hdr = ((b[7] & 0xFF) << 24) | ((b[6] & 0xFF) << 16) | ((b[5] & 0xFF) << 8) | (b[4] & 0xFF);
        return (hdr >> 4) & 0x1F;
    }

    // ---- 供 AnimEditor(独立编辑界面) 共享的数据 ----
    static BuildFile sBuild;
    static AnimFile sAnim;
    static Bitmap sAtlas;
    static byte[] sAnimBytes;
    static File sAnimZip;      // 当前正在编辑的 zip（"导入"路径下这是 cacheDir 里的副本）
    static File sAnimSrc;      // 用户的原文件；为 null 表示导入自 SAF、拿不到真实路径

    // 跳到独立编辑界面(横屏)
    private void launchAnimEditor(File zip, BuildFile build, AnimFile anim, Bitmap atlas, byte[] animBytes) {
        sBuild = build; sAnim = anim; sAtlas = atlas; sAnimBytes = animBytes;
        sAnimZip = zip;
        sAnimSrc = zip;                 // 从文件浏览器打开时, zip 就是用户的真实文件
        startActivity(new Intent(this, AnimEditor.class));
    }

    // ---- 动画渲染视图: 按三角形逐块贴图 ----
    // 关键点:
    //  1) 显式做坐标变换, 不用 pre/post 语义(易错)
    //  2) 锚点取整个动画的包围盒中心, 避免逐帧抖动
    //  3) 每三角形用 setPolyToPoly 求仿射, 不依赖四边形配对
    private class AnimView extends View {
        Bitmap atlas;
        BuildFile build;
        AAnim anim;
        int frame = 0;
        float zoom = 1f;
        boolean flipV = false;
        // 目标朝向: 0=原样 1=旋转180 2=上下翻转 3=左右翻转
        // 默认 2: KTEX 为垂直翻转存储, 需要且只需要补偿一个垂直镜像。
        // (曾误设为 1(旋转180), 那是水平+垂直都翻 -> 表现为左右相反)
        int dstMode = 2;
        float anCx = 0f, anCy = 0f, spanW = 1f, spanH = 1f;
        final android.graphics.Paint paint = new android.graphics.Paint();
        final android.graphics.Path tri = new android.graphics.Path();
        final float[] srcPts = new float[6];
        final float[] dstPts = new float[6];

        AnimView(android.content.Context c) {
            super(c);
            setOnLongClickListener(v -> { toggleUv(); toast("UV 翻转: " + (rnd.flipUv ? "开" : "关")); return true; });
            // 硬件加速下 BitmapShader.setLocalMatrix 与 clipPath 都不可靠,
            // 改用软件渲染(图层), 两者均恢复完整语义
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        void setData(Bitmap a, BuildFile b, AAnim an) {
            atlas = a; build = b; anim = an; frame = 0;
            dbgStroke.setStyle(android.graphics.Paint.Style.STROKE);
            dbgStroke.setStrokeWidth(1.5f);
            dbgStroke.setColor(0xFF00FF88);
            float mnx = Float.MAX_VALUE, mny = Float.MAX_VALUE;
            float mxx = -Float.MAX_VALUE, mxy = -Float.MAX_VALUE;
            for (AFrame f : an.frames) {
                mnx = Math.min(mnx, f.x);
                mny = Math.min(mny, f.y);
                mxx = Math.max(mxx, f.x + f.w);
                mxy = Math.max(mxy, f.y + f.h);
            }
            if (mnx > mxx) { mnx = 0; mny = 0; mxx = 1; mxy = 1; }
            anCx = (mnx + mxx) / 2f;
            anCy = (mny + mxy) / 2f;
            spanW = Math.max(1f, mxx - mnx);
            spanH = Math.max(1f, mxy - mny);
            invalidate();
        }

        void setFrame(int f) { frame = f; invalidate(); }
        void toggleFlip() { flipV = !flipV; invalidate(); }
        void cycleDst() { dstMode = (dstMode + 1) % 4; invalidate(); }
        void toggleUv() { rnd.flipUv = !rnd.flipUv; invalidate(); }
        void toggleZOrder() { rnd.zDescending = !rnd.zDescending; invalidate(); }

        String dbgErr = null;
        final AnimRenderer rnd = new AnimRenderer();
        final android.graphics.Paint dbgStroke = new android.graphics.Paint();

        private void dbgText(android.graphics.Canvas cv, String t, float y) {
            android.graphics.Paint p = new android.graphics.Paint();
            p.setColor(0xFFFFEE55);
            p.setTextSize(20f);
            cv.drawText(t, 10, y, p);
        }

        @Override protected void onDraw(android.graphics.Canvas cv) {
            cv.drawColor(0xFF303030);
            dbgErr = null;
            try {
                drawScene(cv);
            } catch (Throwable t) {
                dbgErr = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            // 诊断叠层: 仅在调试模式下绘制(代码保留)
            if (TexConverter.this.debugMode) drawDebugOverlay(cv);
        }

        private void drawDebugOverlay(android.graphics.Canvas cv) {
            float y = 26;
            dbgText(cv, "BUILD=v32-tapzip zDesc=" + (rnd.zDescending ? 1 : 0), y); y += 24;
            dbgText(cv, "atlas=" + (atlas == null ? "null" : atlas.getWidth() + "x" + atlas.getHeight()), y); y += 24;
            dbgText(cv, "anim=" + (anim == null ? "null" : anim.name)
                       + " frames=" + (anim == null ? 0 : anim.frames.length)
                       + " elems0=" + (anim == null || anim.frames.length == 0 ? 0 : anim.frames[0].elems.length), y); y += 24;
            dbgText(cv, "view=" + getWidth() + "x" + getHeight(), y); y += 24;
            dbgText(cv, "elems " + rnd.elemsMatched + "/" + rnd.elemsTotal
                       + " unmatched=" + rnd.elemsUnmatched
                       + " lastBad=0x" + Integer.toHexString(rnd.lastHash).toUpperCase()
                       + " bf=" + rnd.lastBF, y); y += 24;
            dbgText(cv, "tris=" + rnd.tris + " clipFail=" + rnd.clipFail, y); y += 24;
            dbgText(cv, "satlas_idx=" + (build == null ? 0 : build.symbols.size()), y); y += 24;
            if (dbgErr != null) dbgText(cv, "ERR " + dbgErr, y);
            // 图集缩略图(左下角) + 像素统计
            try {
                int tw = 140, th = 140;
                int tx = 10, ty2 = getHeight() - th - 10;
                if (ty2 > 0) {
                    android.graphics.Rect sr = new android.graphics.Rect(0, 0, atlas.getWidth(), atlas.getHeight());
                    android.graphics.Rect dr = new android.graphics.Rect(tx, ty2, tx + tw, ty2 + th);
                    android.graphics.Paint ip = new android.graphics.Paint();
                    ip.setFilterBitmap(true);
                    cv.drawBitmap(atlas, sr, dr, ip);
                    android.graphics.Paint bd = new android.graphics.Paint();
                    bd.setStyle(android.graphics.Paint.Style.STROKE);
                    bd.setColor(0xFFFF0000);
                    cv.drawRect(dr, bd);
                    // 统计非透明像素比例
                    int nz = 0, tot = 0;
                    for (int yy = 0; yy < atlas.getHeight(); yy += 32) {
                        for (int xx = 0; xx < atlas.getWidth(); xx += 32) {
                            int px = atlas.getPixel(xx, yy);
                            tot++;
                            if ((px >>> 24) > 8) nz++;
                        }
                    }
                    dbgText(cv, "atlasAlpha>8: " + nz + "/" + tot, ty2 - 8);
                    // 在 UV 区域内采样: 分别按 不翻转 / 翻转 两种 v 方向统计非透明像素
                    if (rnd.uvMxx > rnd.uvMnx && rnd.uvMxy > rnd.uvMny) {
                        int n0 = 0, n1 = 0, tt = 0;
                        for (int i = 0; i <= 8; i++) {
                            for (int j = 0; j <= 8; j++) {
                                float uu = rnd.uvMnx + (rnd.uvMxx - rnd.uvMnx) * i / 8f;
                                float vv = rnd.uvMny + (rnd.uvMxy - rnd.uvMny) * j / 8f;
                                int xa = (int)(uu * atlas.getWidth());
                                int ya = (int)(vv * atlas.getHeight());
                                int yb = (int)((1f - vv) * atlas.getHeight());
                                xa = Math.max(0, Math.min(atlas.getWidth() - 1, xa));
                                ya = Math.max(0, Math.min(atlas.getHeight() - 1, ya));
                                yb = Math.max(0, Math.min(atlas.getHeight() - 1, yb));
                                tt++;
                                if ((atlas.getPixel(xa, ya) >>> 24) > 8) n0++;
                                if ((atlas.getPixel(xa, yb) >>> 24) > 8) n1++;
                            }
                        }
                        dbgText(cv, "uvRegion NOFLIP=" + n0 + "/" + tt + "  FLIP=" + n1 + "/" + tt, y);
                    }
                    // 在缩略图上标出当前帧 UV 覆盖的范围(黄框)
                    if (rnd.uvMxx > rnd.uvMnx) {
                        android.graphics.Paint uvP = new android.graphics.Paint();
                        uvP.setStyle(android.graphics.Paint.Style.STROKE);
                        uvP.setStrokeWidth(2f);
                        uvP.setColor(0xFFFFFF00);
                        float rx0 = tx + rnd.uvMnx * tw, rx1 = tx + rnd.uvMxx * tw;
                        float ry0 = ty2 + rnd.uvMny * th, ry1 = ty2 + rnd.uvMxy * th;
                        cv.drawRect(Math.min(rx0,rx1), Math.min(ry0,ry1),
                                    Math.max(rx0,rx1), Math.max(ry0,ry1), uvP);
                        dbgText(cv, "uv=(" + String.format("%.3f", rnd.uvMnx) + "," + String.format("%.3f", rnd.uvMny)
                                   + ")-(" + String.format("%.3f", rnd.uvMxx) + "," + String.format("%.3f", rnd.uvMxy) + ")", y);
                    }
                }
            } catch (Throwable t) {
                dbgText(cv, "THUMB_ERR " + t, 200);
            }
        }

        private void drawScene(android.graphics.Canvas cv) {
            if (atlas == null || anim == null || anim.frames.length == 0) return;
            final float fit = Math.min(getWidth() / spanW, getHeight() / spanH) * 0.85f;
            final float sc = fit * zoom;
            final float ox = getWidth() / 2f, oy = getHeight() / 2f;
            final int m = dstMode;
            // 动画坐标 -> 屏幕坐标; 朝向由 dstMode 决定(0原样 1旋转180 2上下 3左右)
            AnimRenderer.Xform xf = new AnimRenderer.Xform() {
                public float x(float ax) {
                    float e = (ax - anCx) * sc;
                    return ox + ((m == 1 || m == 3) ? -e : e);
                }
                public float y(float ay) {
                    float f = (ay - anCy) * sc;
                    return oy + ((m == 1 || m == 2) ? f : -f);
                }
            };
            rnd.draw(cv, atlas, build, anim, frame, xf, TexConverter.this.debugMode);
        }
    }

    private void openAnimZip(final File zip) { openAnimZip(zip, false); }

    /** @param editDirectly true 则解析完成后直接进入横屏编辑器, 不弹预览框 */
    private void openAnimZip(final File zip, final boolean editDirectly) {
        toast("正在解析 " + zip.getName() + " ...");
        new Thread(() -> {
            File tmpDir = tempWorkingDir("anim_" + TMP_SEQ.incrementAndGet());
            try {
                ZipFile zf = new ZipFile(zip);
                byte[] animD = null, buildD = null, atlasD = null;
                java.util.Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry ze = en.nextElement();
                    String ln = ze.getName().toLowerCase();
                    if (ln.endsWith("anim.bin")) animD = readAll(zf.getInputStream(ze));
                    else if (ln.endsWith("build.bin")) buildD = readAll(zf.getInputStream(ze));
                    else if (ln.endsWith(".tex") && atlasD == null) atlasD = readAll(zf.getInputStream(ze));
                }
                zf.close();
                if (animD == null || buildD == null) throw new Exception("zip 内缺 anim.bin 或 build.bin");
                final BuildFile build = parseBuild(buildD);
                final AnimFile anim = parseAnim(animD);
                Bitmap atlas = null;
                if (atlasD != null) {
                    File tf = new File(tmpDir, "a.tex");
                    writeFile(tf, atlasD);
                    int comp = readTexCompression(atlasD);
                    File png = new File(tmpDir, "a.png");
                    if (comp == 24) {
                        KtexInfo info = unpackKtex(atlasD);
                        File astc = new File(tmpDir, "a.astc");
                        byte[] full = new byte[info.raw.length + 16];
                        writeAstcHeader(full, info.w, info.h);
                        System.arraycopy(info.raw, 0, full, 16, info.raw.length);
                        writeFile(astc, full);
                        runAstcenc("-dl", astc.getAbsolutePath(), png.getAbsolutePath());
                    } else {
                        runTex2png(tf.getAbsolutePath(), png.getAbsolutePath());
                    }
                    atlas = BitmapFactory.decodeFile(png.getAbsolutePath());
                }
                final Bitmap fa = atlas;
                final byte[] fanimD = animD;
                runOnUiThread(() -> {
                    if (editDirectly) launchAnimEditor(zip, build, anim, fa, fanimD);
                    else showAnimDialog(zip, zip.getName(), build, anim, fa, fanimD);
                });
            } catch (Exception e) {
                runOnUiThread(() -> toast("动画解析失败: " + e.getMessage()));
            }
        }).start();
    }

    private void showAnimDialog(File zip, String title, BuildFile build, AnimFile anim, Bitmap atlas, byte[] animBytes) {
        if (anim.anims.length == 0) { toast("无动画"); return; }
        final AAnim an = anim.anims[0];
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView info = new TextView(this);
        info.setTextSize(12);
        info.setTextColor(C_TEXT_SUB);
        info.setPadding(dp(12), dp(8), dp(12), dp(4));
        info.setText("build " + build.name + " | 动画 " + an.name
            + " | 帧 " + an.frames.length + " | " + an.frameRate + " fps"
            + " | 图集 " + (atlas == null ? "无" : atlas.getWidth() + "x" + atlas.getHeight()));
        col.addView(info);
        final AnimView av = new AnimView(this);
        if (atlas != null) av.setData(atlas, build, an);
        col.addView(av, new LinearLayout.LayoutParams(-1, 0, 1));
        final android.widget.SeekBar sb = new android.widget.SeekBar(this);
        sb.setMax(Math.max(0, an.frames.length - 1));
        sb.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar s, int v, boolean u) { av.setFrame(v); }
            public void onStartTrackingTouch(android.widget.SeekBar s) {}
            public void onStopTrackingTouch(android.widget.SeekBar s) {}
        });
        col.addView(sb);

        // ---- 工具按钮行(直接可点, 不依赖长按) ----
        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        final android.widget.Button btnUv = new android.widget.Button(this);
        btnUv.setText("翻转UV: " + (av.rnd.flipUv ? "开" : "关"));
        btnUv.setOnClickListener(v -> {
            av.toggleUv();
            btnUv.setText("翻转UV: " + (av.rnd.flipUv ? "开" : "关"));
        });
        tools.addView(btnUv, new LinearLayout.LayoutParams(0, -2, 1));
        final android.widget.Button btnDir = new android.widget.Button(this);
        btnDir.setText("朝向: " + av.dstMode);
        btnDir.setOnClickListener(v -> {
            av.cycleDst();
            btnDir.setText("朝向: " + av.dstMode);
        });
        tools.addView(btnDir, new LinearLayout.LayoutParams(0, -2, 1));
        final android.widget.Button btnZ = new android.widget.Button(this);
        btnZ.setText("层序: " + (av.rnd.zDescending ? "降" : "升"));
        btnZ.setOnClickListener(v -> {
            av.toggleZOrder();
            btnZ.setText("层序: " + (av.rnd.zDescending ? "降" : "升"));
        });
        tools.addView(btnZ, new LinearLayout.LayoutParams(0, -2, 1));
        col.addView(tools);

        new AlertDialog.Builder(this).setTitle(title).setView(col)
            .setNeutralButton(null, null)
            .setPositiveButton("编辑动画(横屏)", (d, w) -> launchAnimEditor(zip, build, anim, atlas, animBytes))
            .setNegativeButton("关闭", null).show();
    }

    // ---------- zip 浏览 ----------
    private void openZip(File zip) {
        // 点按 zip: 不再列出内部所有纹理文件, 只提供动画的两个入口
        new AlertDialog.Builder(this)
            .setTitle(zip.getName())
            .setMessage("选择操作")
            .setPositiveButton("预览动画", (d, w) -> openAnimZip(zip, false))
            .setNeutralButton("编辑动画", (d, w) -> openAnimZip(zip, true))
            .setNegativeButton("取消", null)
            .show();
    }

    // ---------- 批量转换 ----------
    private static final int RESULT_LINES_MAX = 40;   // 结果框最多列多少条(其余指向日志)

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
        final boolean[] cancelled = {false};

        final ProgressDialog pd = new ProgressDialog(this);
        pd.setTitle(N > 1 ? "转换中 (" + N + " 线程)" : "转换中");
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(total);
        pd.setCancelable(true);              // 大包/exhaustive 可能跑很久，必须能取消
        pd.setCanceledOnTouchOutside(false);
        pd.setOnCancelListener(d -> cancelled[0] = true);
        pd.setMessage("准备中...");
        pd.show();

        new Thread(() -> {
            final java.util.List<String> results =
                    java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
            int ok = 0, fail = 0, skip = 0;
            final long[] lastUpd = {0};

            try {
                if (N <= 1) {
                    // ---------- 单线程路径 ----------
                    int done = 0;
                    for (File f : texFiles) {
                        if (cancelled[0]) break;
                        File tmpDir = tempWorkingDir("st_" + done);
                        try {
                            String r = convertFile(f, f.getParentFile(), tmpDir);
                            ok++; results.add("OK   " + f.getName() + " -> " + r);
                        } catch (SkipException se) {
                            skip++; results.add("SKIP " + f.getName() + ": " + se.getMessage());
                        } catch (Exception e) {
                            fail++; results.add("FAIL " + f.getName() + ": " + e.getMessage());
                            logErr("转换 " + f.getName(), e);
                        } finally {
                            purgeDir(tmpDir);       // listFiles() 可能返回 null，旧代码在这里会 NPE
                        }
                        done++;
                        postProgress(pd, cancelled, lastUpd, done, total,
                                "转换中 " + done + "/" + total + "  成功 " + ok + "  跳过 " + skip + "  失败 " + fail);
                    }
                    for (File z : zipFiles) {
                        if (cancelled[0]) break;
                        File zdir = tempWorkingDir("stzip_" + done);
                        try {
                            String r = convertZipReplace(z, zdir);
                            ok++; results.add("OK   " + z.getName() + " -> " + r);
                        } catch (Exception e) {
                            fail++; results.add("FAIL " + z.getName() + ": " + e.getMessage());
                            logErr("批量转换 " + z.getName(), e);
                        } finally {
                            purgeDir(zdir);
                        }
                        done++;
                        postProgress(pd, cancelled, lastUpd, done, total,
                                "转换中 " + done + "/" + total + "  成功 " + ok + "  跳过 " + skip + "  失败 " + fail);
                    }
                } else {
                    // ---------- 多线程路径 ----------
                    java.util.concurrent.ExecutorService executor =
                        java.util.concurrent.Executors.newFixedThreadPool(N);
                    final int[] done = {0};
                    java.util.List<java.util.concurrent.Future<String>> futures = new java.util.ArrayList<>();
                    try {
                        for (final File f : texFiles) {
                            futures.add(executor.submit(() -> {
                                String kind = runConvertTaskMT(f, f.getParentFile(), results);
                                synchronized (done) { done[0]++; }
                                postProgress(pd, cancelled, lastUpd, done[0], total,
                                        "转换中 " + done[0] + "/" + total + "  线程 " + N);
                                return kind;
                            }));
                        }
                        for (final File z : zipFiles) {
                            futures.add(executor.submit(() -> {
                                String kind;
                                try {
                                    kind = runZipTaskMT(z, results);
                                } finally {
                                    synchronized (done) { done[0]++; }
                                }
                                postProgress(pd, cancelled, lastUpd, done[0], total,
                                        "转换中 " + done[0] + "/" + total + "  线程 " + N);
                                return kind;
                            }));
                        }
                    } finally {
                        executor.shutdown();
                    }
                    try {
                        while (!executor.awaitTermination(200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                            if (cancelled[0]) { executor.shutdownNow(); break; }
                        }
                    } catch (InterruptedException ie) {
                        executor.shutdownNow();
                        Thread.currentThread().interrupt();
                    }
                    for (java.util.concurrent.Future<String> f : futures) {
                        String kind;
                        try {
                            kind = f.get();
                        } catch (Exception e) {
                            if (e instanceof java.util.concurrent.CancellationException) { skip++; continue; }
                            fail++; continue;
                        }
                        if ("OK".equals(kind)) ok++;
                        else if ("SKIP".equals(kind)) skip++;
                        else fail++;
                    }
                }
            } catch (Throwable t) {
                // 兜底：任何未预料的异常都必须让进度框关掉，否则界面永久卡死
                logErr("批量转换中断", t);
                results.add("!! 中断: " + t);
            }

            final int fok = ok, ffail = fail, fskip = skip;
            runOnUiThread(() -> {
                try { pd.dismiss(); } catch (Throwable ignored) { }
                String body = resultBody(results);
                new AlertDialog.Builder(this)
                    .setTitle(cancelled[0] ? "已取消" : "转换完成")
                    .setMessage("成功 " + fok + "，跳过 " + fskip + "，失败 " + ffail
                        + (N > 1 ? " (" + N + " 线程)" : "")
                        + "\n" + (autoBackup ? "（已替换原文件，备份 .bak）" : "（已替换原文件，未备份 —— 设置里可开启 .bak）")
                        + "\n\n" + body)
                    .setPositiveButton("确定", null)
                    .show();
            });
        }).start();
    }

    /** 结果框只列前 RESULT_LINES_MAX 条，避免几千条把对话框撑爆 */
    private String resultBody(java.util.List<String> results) {
        if (results.isEmpty()) return "（无输出）";
        if (results.size() <= RESULT_LINES_MAX) return String.join("\n", results);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < RESULT_LINES_MAX; i++) sb.append(results.get(i)).append('\n');
        sb.append("… 其余 ").append(results.size() - RESULT_LINES_MAX).append(" 条见「菜单 → 查看日志」");
        return sb.toString();
    }

    /** 进度更新节流：每个文件都 runOnUiThread 会把主线程消息队列塞满 */
    private void postProgress(ProgressDialog pd, boolean[] cancelled, long[] lastUpd,
                              int done, int total, String msg) {
        if (cancelled[0]) return;
        long now = System.currentTimeMillis();
        if (now - lastUpd[0] < 120 && done < total) return;
        lastUpd[0] = now;
        runOnUiThread(() -> {
            try { pd.setProgress(done); pd.setMessage(msg); } catch (Throwable ignored) { }
        });
    }

    /** 递归删除临时目录内容（含子目录） */
    private void purgeDir(File dir) {
        if (dir == null) return;
        File[] fs = dir.listFiles();
        if (fs != null) for (File c : fs) { if (c.isDirectory()) purgeDir(c); else c.delete(); }
        dir.delete();
    }

    // 递归收集文件夹内所有 .tex / .png / .zip（.bak 等一律不碰）
    private void collectTexRecursive(File dir, List<File> texFiles, List<File> zipFiles) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) { collectTexRecursive(f, texFiles, zipFiles); continue; }
            String n = f.getName().toLowerCase();
            if (n.endsWith(".bak")) continue;
            if (n.endsWith(".zip")) zipFiles.add(f);
            else if (n.endsWith(".tex") || n.endsWith(".png")) texFiles.add(f);
        }
    }

    /** 「按当前模式这个文件不需要处理」—— 跳过不是失败，不计入 fail */
    static class SkipException extends Exception {
        SkipException(String m) { super(m); }
    }

    private String convertFile(File f, File outputDir, File tmpDir) throws Exception {
        String n = f.getName().toLowerCase();
        log("convertFile " + f.getName() + " (" + f.length() + "B) mode=" + convertMode);
        if (n.endsWith(".bak")) throw new SkipException("备份文件，跳过");
        if (n.endsWith(".png")) {
            if (convertMode.equals("tex2png")) throw new SkipException("当前模式为 TEX→PNG，跳过 PNG");
            return pngToTex(f, outputDir, tmpDir);
        } else if (n.endsWith(".tex")) {
            int comp = readTexCompression(f);
            if (convertMode.equals("png2tex")) throw new SkipException("当前模式为 PNG→TEX，跳过 TEX");
            if (convertMode.equals("tex2png")) return texToPng(f, outputDir, tmpDir);
            if (convertMode.equals("dxt2astc")) {
                if (comp == 24) throw new SkipException("已是 ASTC，跳过");
                return texToAstc(f, outputDir, tmpDir);
            }
            // auto
            return comp == 24 ? texToPng(f, outputDir, tmpDir) : texToAstc(f, outputDir, tmpDir);
        }
        throw new SkipException("不支持的文件类型");
    }

    private String pngToTex(File png, File outputDir, File tmpDir) throws Exception {
        File flip = new File(tmpDir, "flip.png");
        flipPng(png, flip);
        byte[] ktex = buildAstcKtex(flip, tmpDir);
        File out = new File(outputDir, baseName(png) + ".tex");
        // 同名 .tex 已存在 → 备份后再写, 否则会无提示覆盖掉用户的原纹理
        if (out.exists() && !out.equals(png)) {
            if (autoBackup) backupOnce(out);
            log("!! 覆盖同名 tex: " + out.getName() + (autoBackup ? "(已备份 .bak)" : "(未备份)"));
        }
        writeFileAtomic(out, ktex);
        return out.getName();
    }

    private String texToAstc(File tex, File outputDir, File tmpDir) throws Exception {
        File flipPng = new File(tmpDir, "decoded.png");
        runTex2png(tex.getAbsolutePath(), flipPng.getAbsolutePath());
        byte[] ktex = buildAstcKtex(flipPng, tmpDir);
        // 先备份再原子替换: 任何一步失败, 原文件都还在
        if (autoBackup) backupOnce(tex);
        writeFileAtomic(tex, ktex);
        return autoBackup ? "已替换，原文件→" + tex.getName() + ".bak" : "已替换（未备份）";
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
        // 同名 png 已存在时不要直接覆盖(可能是用户自己的图)
        if (out.exists() && !out.equals(tex) && autoBackup) backupOnce(out);
        flipPng(flipPng, out);
        return out.getName();
    }

    // zip 内 .tex 批量转换（替换 zip 内条目，原 zip 备份 .bak）
    private String convertZipReplace(File zip, File tmpDir) throws Exception {
        log("convertZip " + zip.getName() + " (" + zip.length() + "B) nested=" + nestedZip);
        final int[] skip = new int[2];      // [0]=已是ASTC, [1]=模式不符
        final int[] ok = new int[1];
        // 临时包放在原文件同目录: (a) 不吃 cacheDir 配额 (b) rename 同分区原子且无需整包再拷贝
        File tmp = new File(zip.getParentFile(), zip.getName() + ".tmp" + TMP_SEQ.incrementAndGet());
        ZipFile zf = null;
        try {
            zf = new ZipFile(zip);
            ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tmp));
            try {
                java.util.Enumeration<? extends ZipEntry> en = zf.entries();
                java.util.Set<String> used = new java.util.HashSet<>();
                while (en.hasMoreElements()) {
                    ZipEntry ze = en.nextElement();
                    String entryName = ze.getName();
                    byte[] data = null;          // null = 走流式拷贝(不整块读入内存)
                    if (!ze.isDirectory()) {
                        String ln = entryName.toLowerCase();
                        if (ln.endsWith(".tex")) {
                            byte[] raw = readAll(zf.getInputStream(ze));
                            try {
                                TexConvOut o = convertTexByMode(raw, tmpDir, entryName, skip);
                                data = o.data;
                                if (o.changed) ok[0]++;
                                if (o.name != null) entryName = o.name;   // 如 TEX→PNG 需改扩展名
                            } catch (Exception ex) {
                                log("    跳过 " + entryName + ": " + ex.getMessage());
                                data = raw;
                            }
                        } else if (ln.endsWith(".zip") && nestedZip) {
                            // 内嵌压缩包: 递归处理其中(及其内层)的 tex
                            byte[] inner = readAll(zf.getInputStream(ze));
                            Object[] r = convertZipBytes(inner, tmpDir, skip);
                            data = (byte[]) r[0];
                            ok[0] += (Integer) r[1];
                        }
                    }
                    entryName = uniqueName(used, entryName);
                    ZipEntry ne = new ZipEntry(entryName);
                    if (ze.getTime() > 0) ne.setTime(ze.getTime());
                    zos.putNextEntry(ne);
                    if (data != null) {
                        zos.write(data);
                    } else {
                        InputStream in = zf.getInputStream(ze);
                        byte[] buf = new byte[65536];
                        int r;
                        while ((r = in.read(buf)) > 0) zos.write(buf, 0, r);
                        in.close();
                    }
                    zos.closeEntry();
                }
            } finally {
                try { zos.close(); } catch (Throwable ignored) { }
            }
        } finally {
            if (zf != null) { try { zf.close(); } catch (Throwable ignored) { } }
        }

        String tail = "";
        if (skip[0] > 0) tail += ", " + skip[0] + " 个已是 ASTC 跳过";
        if (skip[1] > 0) tail += ", " + skip[1] + " 个模式不符跳过";

        if (ok[0] == 0) {
            // 没有任何改动就不要重写原包: 否则会白改压缩率/时间戳, 还平白冒一次写坏的风险
            tmp.delete();
            log("convertZip 无改动, 保持原包 " + zip.getName() + tail);
            return "zip 内无可转换的 tex（原包未改动）" + tail;
        }
        if (autoBackup) backupOnce(zip);
        // 原子替换: rename 失败(跨分区/被占用)时退化为拷贝覆盖
        if (!tmp.renameTo(zip)) {
            log("!! zip 原子替换失败, 退化为拷贝覆盖: " + zip.getName());
            copyFile(tmp, zip);
            tmp.delete();
        }
        log("convertZip 完成 " + zip.getName() + " -> 转换 " + ok[0] + tail);
        return "zip 内 " + ok[0] + " 个 tex 已转换" + tail
             + (autoBackup ? "（原包→" + zip.getName() + ".bak）" : "");
    }

    /** zip 条目名去重: TEX→PNG 后可能与已有 png 撞名, 撞了就加序号 */
    private static String uniqueName(java.util.Set<String> used, String name) {
        if (used.add(name)) return name;
        String base = name, ext = "";
        int dot = name.lastIndexOf('.');
        int slash = name.lastIndexOf('/');
        if (dot > slash) { base = name.substring(0, dot); ext = name.substring(dot); }
        for (int i = 1; i < 1000; i++) {
            String cand = base + "~" + i + ext;
            if (used.add(cand)) return cand;
        }
        return name;
    }

    /**
     * 递归处理 zip 字节流: 转换其中以及内嵌 zip(可多层)中的全部非 ASTC tex。
     * @return {处理后的 zip 字节, 累计转换个数}
     */
    private static final int MAX_ZIP_DEPTH = 8;

    private Object[] convertZipBytes(byte[] zipData, File tmpDir, int[] skip) throws Exception {
        return convertZipBytes(zipData, tmpDir, 0, skip);
    }

    private Object[] convertZipBytes(byte[] zipData, File tmpDir, int depth, int[] skip) throws Exception {
        if (depth > MAX_ZIP_DEPTH) return new Object[]{zipData, 0};   // 超深则原样返回
        final int seq = TMP_SEQ.incrementAndGet();
        log("  内嵌 zip depth=" + depth + " " + zipData.length + "B");
        java.util.HashMap<String,Integer> extCnt = new java.util.HashMap<>();
        int entryTotal = 0, nameSample = 0;
        StringBuilder nameBuf = new StringBuilder();
        File in = new File(tmpDir, "inner_" + seq + "_in.zip");
        File out = new File(tmpDir, "inner_" + seq + "_out.zip");
        writeFile(in, zipData);
        int ok = 0;
        ZipFile zf = null;
        try {
            zf = new ZipFile(in);
            ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out));
            try {
                java.util.Enumeration<? extends ZipEntry> en = zf.entries();
                java.util.Set<String> used = new java.util.HashSet<>();
                while (en.hasMoreElements()) {
                    ZipEntry ze = en.nextElement();
                    String entryName = ze.getName();
                    String n = entryName.toLowerCase();
                    byte[] data = null;
                    // 统计: 记录条目类型分布, 便于判断"为什么没找到 tex"
                    if (!ze.isDirectory()) {
                        entryTotal++;
                        int dot = n.lastIndexOf('.');
                        String ext = dot >= 0 ? n.substring(dot) : "(无扩展名)";
                        Integer c = extCnt.get(ext);
                        extCnt.put(ext, c == null ? 1 : c + 1);
                        if (nameSample < 3) { nameBuf.append('\n').append("      ").append(entryName); nameSample++; }
                        if (n.endsWith(".tex")) {
                            byte[] raw = readAll(zf.getInputStream(ze));
                            try {
                                TexConvOut o = convertTexByMode(raw, tmpDir, entryName, skip);
                                data = o.data;
                                if (o.changed) ok++;
                                if (o.name != null) entryName = o.name;
                            } catch (Exception ex) {
                                log("    跳过 " + entryName + ": " + ex.getMessage());
                                data = raw;
                            }
                        } else if (n.endsWith(".zip")) {
                            byte[] inner = readAll(zf.getInputStream(ze));
                            Object[] r = convertZipBytes(inner, tmpDir, depth + 1, skip);
                            data = (byte[]) r[0];
                            ok += (Integer) r[1];
                        }
                    }
                    entryName = uniqueName(used, entryName);
                    ZipEntry ne = new ZipEntry(entryName);
                    if (ze.getTime() > 0) ne.setTime(ze.getTime());
                    zos.putNextEntry(ne);
                    if (data != null) {
                        zos.write(data);
                    } else {
                        InputStream ins = zf.getInputStream(ze);
                        byte[] buf = new byte[65536];
                        int r;
                        while ((r = ins.read(buf)) > 0) zos.write(buf, 0, r);
                        ins.close();
                    }
                    zos.closeEntry();
                }
            } finally {
                try { zos.close(); } catch (Throwable ignored) { }
            }
        } finally {
            if (zf != null) { try { zf.close(); } catch (Throwable ignored) { } }
        }
        // 输出条目类型分布(仅顶层 depth, 避免日志爆量)
        if (entryTotal > 0 && depth == 0) {
            log("    条目 " + entryTotal + " 个, 类型: " + extCnt);
            log("    名称样例:" + nameBuf);
        } else if (entryTotal == 0) {
            log("    !! 该 zip 内没有文件条目");
        }
        byte[] res = readAll(new FileInputStream(out));
        in.delete(); out.delete();
        return new Object[]{res, ok};
    }

    /** 一次 tex 转换的结果：数据 + 是否真的改动了 + 需要改的条目名(null=不改) */
    static class TexConvOut {
        byte[] data; boolean changed; String name;
        TexConvOut(byte[] d, boolean c, String n) { data = d; changed = c; name = n; }
    }

    /** 改扩展名，保留 zip 内的目录前缀 */
    private static String replaceExt(String name, String newExt) {
        int slash = name.lastIndexOf('/');
        int dot = name.lastIndexOf('.');
        if (dot <= slash) return name + newExt;
        return name.substring(0, dot) + newExt;
    }

    /**
     * 按当前转换模式处理一份 tex 字节(顶层与内嵌压缩包共用, 保证行为一致)。
     * 注意 TEX→PNG 时条目名必须一起改成 .png —— 之前只换内容不换名,
     * 会在包内留下「扩展名是 .tex、内容是 PNG」的假纹理。
     */
    private TexConvOut convertTexByMode(byte[] data, File tmpDir, String name, int[] skip) throws Exception {
        int comp = readTexCompression(data);
        log("    tex " + name + " comp=" + comp + " mode=" + convertMode);
        if (convertMode.equals("png2tex")) {
            skip[1]++;
            log("    跳过(模式不符) " + name);
            return new TexConvOut(data, false, null);
        }
        if (convertMode.equals("dxt2astc") && comp == 24) {
            skip[0]++;
            log("    跳过(已是 ASTC) " + name);
            return new TexConvOut(data, false, null);
        }
        if (convertMode.equals("tex2png") || (convertMode.equals("auto") && comp == 24)) {
            // 注意: ASTC(comp=24) 必须走 astcenc, tex2png 只认 DXT/RGBA ——
            // 原来这里一律调 runTex2png, 导致 TEX→PNG 模式下遇到 ASTC 纹理全部失败。
            return new TexConvOut(texBytesToPng(data, comp, tmpDir), true, replaceExt(name, ".png"));
        }
        if (convertMode.equals("dxt2astc")) {
            return new TexConvOut(convertTexBytes(data, tmpDir), true, null);
        }
        // auto: 非 ASTC -> ASTC
        return new TexConvOut(convertTexBytes(data, tmpDir), true, null);
    }

    /** tex 字节 → png 字节（comp=24 走 astcenc 反解，其余走 tex2png） */
    private byte[] texBytesToPng(byte[] data, int comp, File tmpDir) throws Exception {
        final int seq = TMP_SEQ.incrementAndGet();
        File t = new File(tmpDir, "m_" + seq + ".tex");
        File p = new File(tmpDir, "m_" + seq + ".png");
        File astc = new File(tmpDir, "m_" + seq + ".astc");
        try {
            if (comp == 24) {
                KtexInfo info = unpackKtex(data);
                byte[] full = new byte[info.raw.length + 16];
                writeAstcHeader(full, info.w, info.h);
                System.arraycopy(info.raw, 0, full, 16, info.raw.length);
                writeFile(astc, full);
                runAstcenc("-dl", astc.getAbsolutePath(), p.getAbsolutePath());
            } else {
                writeFile(t, data);
                runTex2png(t.getAbsolutePath(), p.getAbsolutePath());
            }
            return readAll(new FileInputStream(p));
        } finally {
            t.delete(); p.delete(); astc.delete();
        }
    }

    // 内存中把 DXT/RGBA tex 字节转成 ASTC tex 字节
    private byte[] convertTexBytes(byte[] texData, File tmpDir) throws Exception {
        final int seq = TMP_SEQ.incrementAndGet();
        File tmpTex = new File(tmpDir, "tmp_" + seq + ".tex");
        writeFile(tmpTex, texData);
        File png = new File(tmpDir, "tmp_" + seq + ".png");
        runTex2png(tmpTex.getAbsolutePath(), png.getAbsolutePath());
        try {
            return buildAstcKtex(png, tmpDir);
        } finally {
            tmpTex.delete();
            png.delete();
        }
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
                runAstcenc("-cl", in.getAbsolutePath(), out.getAbsolutePath(), ASTC_BLOCK, "-" + quality);
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

    static KtexInfo unpackKtex(byte[] ktex) throws Exception {
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

    // ASTC 通用头 —— 固定 8x8 块(1 plane, 1 texel/块)。
    // 这里与 packKtexLevels 的 pitch/datasz 必须成套: 任何一方改动都要同步，
    // 否则会出现「头部说 8x8、数据是 4x4」的废纹理。
    static void writeAstcHeader(byte[] buf, int w, int h) {
        buf[0] = 0x13; buf[1] = (byte) 0xAB; buf[2] = (byte) 0xA1; buf[3] = 0x5C;
        buf[4] = 8; buf[5] = 8; buf[6] = 1;
        buf[7] = (byte) (w & 0xFF); buf[8] = (byte) ((w >> 8) & 0xFF); buf[9] = (byte) ((w >> 16) & 0xFF);
        buf[10] = (byte) (h & 0xFF); buf[11] = (byte) ((h >> 8) & 0xFF); buf[12] = (byte) ((h >> 16) & 0xFF);
        buf[13] = 1;
    }

    // ---------- native ----------
    static void runAstcenc(String... args) throws Exception {
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

    /**
     * 原子写: 同目录写 .tmp → fsync → rename 覆盖目标。
     * rename 在同一文件系统上是原子的, 所以中途失败(存储满/进程被杀/OOM)时
     * 目标文件保持原样, 不会变成半截垃圾。
     */
    private void writeFileAtomic(File target, byte[] data) throws Exception {
        File dir = target.getParentFile();
        if (dir == null || !dir.isDirectory()) { writeFile(target, data); return; }
        File tmp = new File(dir, target.getName() + ".tmp" + TMP_SEQ.incrementAndGet());
        try {
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(data);
                out.flush();
                out.getFD().sync();
            } finally {
                try { out.close(); } catch (Throwable ignored) { }
            }
            if (!tmp.renameTo(target)) {
                // 个别文件系统裸 rename 会失败: 退化为直接覆盖(仍是老行为, 但至少不丢数据)
                log("!! 原子 rename 失败, 退化为直接写入: " + target.getName());
                writeFile(target, data);
            }
        } finally {
            if (tmp.exists()) tmp.delete();
        }
    }

    /** 备份为 <name>.bak; 已存在则保留最早那份(避免二次转换把原始文件覆盖掉) */
    private void backupOnce(File f) throws Exception {
        File bak = new File(f.getAbsolutePath() + ".bak");
        if (!bak.exists()) copyFile(f, bak);
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
