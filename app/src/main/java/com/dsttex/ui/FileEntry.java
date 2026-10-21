package com.dsttex.ui;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 文件列表条目：browse 时一次性把元数据读进来。
 * 旧实现是在 getView 里每行做 isDirectory()/length()/lastModified() 三次 stat，
 * 长目录滚动会明显掉帧。
 */
public final class FileEntry {

    public final File file;
    public final String name;
    public final String lower;
    public final boolean dir;
    public final long size;
    public final long mtime;
    /** 目录内的条目数；-1 表示不可读 */
    public final int childCount;

    private FileEntry(File f) {
        file = f;
        name = f.getName();
        lower = name.toLowerCase();
        dir = f.isDirectory();
        size = dir ? 0 : f.length();
        mtime = f.lastModified();
        int count = -1;
        if (dir) {
            File[] ch = f.listFiles();
            count = ch == null ? -1 : ch.length;
        }
        childCount = count;
    }

    /** 读取目录内容（目录优先、然后按名字忽略大小写排序） */
    public static List<FileEntry> list(File dir) {
        File[] fs = dir == null ? null : dir.listFiles();
        if (fs == null) return new ArrayList<>();
        List<FileEntry> out = new ArrayList<>(fs.length);
        for (File f : fs) out.add(new FileEntry(f));
        Collections.sort(out, ORDER);
        return out;
    }

    private static final Comparator<FileEntry> ORDER = (a, b) -> {
        if (a.dir != b.dir) return a.dir ? -1 : 1;
        return a.name.compareToIgnoreCase(b.name);
    };

    public String ext() {
        int i = name.lastIndexOf('.');
        return i > 0 ? lower.substring(i) : "";
    }
}
