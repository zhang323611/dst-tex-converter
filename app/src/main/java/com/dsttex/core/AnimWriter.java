package com.dsttex.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** 动画写回：把编辑后的元素变换原地写进 anim.bin 字节，并替换压缩包内条目 */
public final class AnimWriter {

    private AnimWriter() { }

    /**
     * 把内存里的元素变换写回 anim.bin 字节。
     * 元素记录 40 字节：hash(4) buildFrame(4) layer(4) a,b,c,d(16) tx,ty,z(12)；
     * txOffset 指向 tx，因此除 hash 外都能原地改写。
     *
     * @return 实际改写的元素个数
     */
    public static int patch(byte[] anim, AnimFormat.AnimFile file) {
        if (anim == null || file == null) return 0;
        int patched = 0;
        for (AnimFormat.AAnim a : file.anims) {
            for (AnimFormat.AFrame f : a.frames) {
                for (AnimFormat.AElement e : f.elems) {
                    if (e.txOffset < 28 || e.txOffset + 12 > anim.length) continue;
                    putI(anim, e.txOffset - 24, e.buildFrame);
                    putF(anim, e.txOffset - 16, e.a);
                    putF(anim, e.txOffset - 12, e.b);
                    putF(anim, e.txOffset - 8, e.c);
                    putF(anim, e.txOffset - 4, e.d);
                    putF(anim, e.txOffset, e.tx);
                    putF(anim, e.txOffset + 4, e.ty);
                    putF(anim, e.txOffset + 8, e.z);
                    patched++;
                }
            }
        }
        return patched;
    }

    /** 用新的 anim.bin 替换压缩包内条目，其余条目原样复制 */
    public static void repack(File srcZip, byte[] anim, File dst) throws Exception {
        ZipFile zf = new ZipFile(srcZip);
        try {
            ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(dst));
            try {
                Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry ze = en.nextElement();
                    ZipEntry ne = new ZipEntry(ze.getName());
                    if (ze.getTime() > 0) ne.setTime(ze.getTime());
                    zos.putNextEntry(ne);
                    if (ze.getName().toLowerCase().endsWith("anim.bin")) {
                        zos.write(anim);
                    } else {
                        InputStream in = zf.getInputStream(ze);
                        try {
                            byte[] buf = new byte[65536];
                            int r;
                            while ((r = in.read(buf)) > 0) zos.write(buf, 0, r);
                        } finally {
                            in.close();
                        }
                    }
                    zos.closeEntry();
                }
            } finally {
                try { zos.close(); } catch (Throwable ignored) { }
            }
        } finally {
            try { zf.close(); } catch (Throwable ignored) { }
        }
    }

    private static void putI(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16);
        b[off + 3] = (byte) (v >> 24);
    }

    private static void putF(byte[] b, int off, float v) {
        int i = Float.floatToIntBits(v);
        b[off] = (byte) (i & 0xFF);
        b[off + 1] = (byte) ((i >> 8) & 0xFF);
        b[off + 2] = (byte) ((i >> 16) & 0xFF);
        b[off + 3] = (byte) ((i >> 24) & 0xFF);
    }
}
