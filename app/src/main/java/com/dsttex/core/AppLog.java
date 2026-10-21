package com.dsttex.core;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * 运行日志：内存环形缓冲 + 落盘（cacheDir/app.log）。
 * 批量转换会写几千行，所以落盘文件超过 2MB 自动清空重来，避免无限增长。
 */
public final class AppLog {

    private static final int MAX_LINES = 800;
    private static final long MAX_FILE = 2L * 1024 * 1024;
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static File file;

    private AppLog() { }

    public static void init(Context ctx) {
        file = new File(ctx.getCacheDir(), "app.log");
    }

    public static synchronized void i(String msg) {
        append(msg);
    }

    public static synchronized void e(String tag, Throwable t) {
        append("!! " + tag + ": " + t);
        if (t == null) return;
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        for (String line : sw.toString().split("\n")) append("    " + line.trim());
    }

    private static void append(String msg) {
        String line = FMT.format(new Date()) + "  " + msg;
        LINES.addLast(line);
        while (LINES.size() > MAX_LINES) LINES.removeFirst();
        if (file == null) return;
        try {
            if (file.length() > MAX_FILE) file.delete();
            FileWriter w = new FileWriter(file, true);
            try {
                w.write(line + "\n");
            } finally {
                w.close();
            }
        } catch (Throwable ignored) {
            // 日志失败不能影响主流程
        }
    }

    public static synchronized String text() {
        StringBuilder sb = new StringBuilder();
        for (String l : LINES) sb.append(l).append('\n');
        return sb.toString();
    }

    public static synchronized void clear() {
        LINES.clear();
        if (file != null) file.delete();
        append("日志已清空");
    }
}
