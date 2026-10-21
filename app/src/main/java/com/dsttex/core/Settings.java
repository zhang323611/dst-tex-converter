package com.dsttex.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/** 用户设置（SharedPreferences 单一出口，避免各界面各写一份 key） */
public final class Settings {

    public enum Mode {
        AUTO("auto"), PNG2TEX("png2tex"), TEX2PNG("tex2png"), DXT2ASTC("dxt2astc");

        public final String id;

        Mode(String id) { this.id = id; }

        public static Mode of(String s) {
            for (Mode m : values()) if (m.id.equals(s)) return m;
            return AUTO;
        }
    }

    private static final String PREF = "texconv";

    private final SharedPreferences prefs;

    public Settings(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public Mode mode() { return Mode.of(prefs.getString("convertMode", "auto")); }

    public void setMode(Mode m) { prefs.edit().putString("convertMode", m.id).apply(); }

    public String quality() { return prefs.getString("quality", "medium"); }

    public void setQuality(String q) { prefs.edit().putString("quality", q).apply(); }

    /** 转换是原地替换，默认开启备份 */
    public boolean backup() { return prefs.getBoolean("autoBackup", true); }

    public void setBackup(boolean on) { prefs.edit().putBoolean("autoBackup", on).apply(); }

    public boolean nestedZip() { return prefs.getBoolean("nestedZip", true); }

    public void setNestedZip(boolean on) { prefs.edit().putBoolean("nestedZip", on).apply(); }

    public boolean debug() { return prefs.getBoolean("debugMode", false); }

    public void setDebug(boolean on) { prefs.edit().putBoolean("debugMode", on).apply(); }

    /** -1 = 自动（按 CPU 核数） */
    public int threads() { return prefs.getInt("threadCount", 1); }

    public void setThreads(int n) { prefs.edit().putInt("threadCount", n).apply(); }

    public int activeThreads() {
        int t = threads();
        return t < 0 ? Math.max(1, Runtime.getRuntime().availableProcessors()) : Math.max(1, t);
    }

    public Set<String> bookmarks() {
        Set<String> s = prefs.getStringSet("bookmarks", null);
        return s == null ? new HashSet<String>() : new HashSet<>(s);
    }

    public void setBookmarks(Set<String> s) {
        prefs.edit().putStringSet("bookmarks", new HashSet<>(s)).apply();
    }
}
