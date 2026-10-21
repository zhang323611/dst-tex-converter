package com.dsttex.core;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * KTEX(.tex) / .dyn / native 编解码。纯逻辑，不碰 UI —— 由 Converter / 各 Activity 调用。
 *
 * 关键约定：
 *  - DST 手游 .tex 的 compression=24 就是 ASTC 8x8；KTEX 头不携带块尺寸，
 *    所以块大小固定 8x8，header / pitch / datasz 三处必须成套。
 *  - KTEX 的像素是垂直翻转存储，导出成 PNG 前要翻正。
 */
public final class TexCodec {

    public static final String ASTC_BLOCK = "8x8";
    private static final int KTEX_BASE_FLAGS = 0xFFFC0380;

    private static final AtomicInteger TMP_SEQ = new AtomicInteger();
    private static Boolean nativesOk;

    private TexCodec() { }

    // ------------------------------------------------------------------ native

    private static native int nativeAstcenc(String[] args);

    private static native int nativeTex2png(String[] args);

    /** 引擎是否可用（库加载失败时所有转换入口应当禁用，而不是让用户点到崩） */
    public static synchronized boolean available() {
        if (nativesOk == null) {
            try {
                System.loadLibrary("astcenc");
                System.loadLibrary("tex2png");
                nativesOk = Boolean.TRUE;
            } catch (Throwable t) {
                AppLog.e("加载 native 库失败", t);
                nativesOk = Boolean.FALSE;
            }
        }
        return nativesOk;
    }

    public static void runAstcenc(String... args) throws Exception {
        int code = nativeAstcenc(args);
        if (code != 0) throw new Exception("astcenc 退出码 " + code);
    }

    public static void runTex2png(String... args) throws Exception {
        int code = nativeTex2png(args);
        if (code != 0) throw new Exception("tex2png 退出码 " + code + "（文件可能不是 KTEX 或已损坏）");
    }

    // -------------------------------------------------------------------- KTEX

    public static final class KtexInfo {
        public int w, h;
        public byte[] raw;      // 第 0 级 mip 的压缩数据
        public int compression;
        public int mipmaps;
    }

    public static int readTexCompression(byte[] b) throws Exception {
        if (b == null || b.length < 8 || b[0] != 'K' || b[1] != 'T' || b[2] != 'E' || b[3] != 'X') {
            throw new Exception("不是 KTEX 文件");
        }
        return (ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt(4) >> 4) & 0x1F;
    }

    public static int readTexCompression(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] head = new byte[8];
            int n = in.read(head);
            if (n < 8) throw new Exception("文件过短");
            return readTexCompression(head);
        } finally {
            in.close();
        }
    }

    public static String compLabel(int c) {
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

    /** 解出第 0 级 mip（预览/反解都只用第 0 级） */
    public static KtexInfo unpackKtex(byte[] ktex) throws Exception {
        if (ktex == null || ktex.length < 18
                || ktex[0] != 'K' || ktex[1] != 'T' || ktex[2] != 'E' || ktex[3] != 'X') {
            throw new Exception("不是 KTEX 文件");
        }
        ByteBuffer bb = ByteBuffer.wrap(ktex).order(ByteOrder.LITTLE_ENDIAN);
        int hdr = bb.getInt(4);
        int compression = (hdr >> 4) & 0x1F;
        int mip = (hdr >> 13) & 0x1F;
        if (compression != 24) throw new Exception("仅支持 ASTC(comp=24)，当前 comp=" + compression);
        if (mip < 1) throw new Exception("无 mipmap");
        int w = bb.getShort(8) & 0xFFFF;
        int h = bb.getShort(10) & 0xFFFF;
        int datasz = bb.getInt(14);
        int dataOff = 8 + mip * 10;
        if (datasz < 0 || dataOff + datasz > ktex.length) throw new Exception("数据长度不匹配");
        byte[] raw = new byte[datasz];
        System.arraycopy(ktex, dataOff, raw, 0, datasz);
        KtexInfo info = new KtexInfo();
        info.w = w;
        info.h = h;
        info.raw = raw;
        info.compression = compression;
        info.mipmaps = mip;
        return info;
    }

    /** ASTC 通用头：固定 8x8 块、1 plane、每块 1 texel */
    public static void writeAstcHeader(byte[] buf, int w, int h) {
        buf[0] = 0x13;
        buf[1] = (byte) 0xAB;
        buf[2] = (byte) 0xA1;
        buf[3] = 0x5C;
        buf[4] = 8;
        buf[5] = 8;
        buf[6] = 1;
        buf[7] = (byte) (w & 0xFF);
        buf[8] = (byte) ((w >> 8) & 0xFF);
        buf[9] = (byte) ((w >> 16) & 0xFF);
        buf[10] = (byte) (h & 0xFF);
        buf[11] = (byte) ((h >> 8) & 0xFF);
        buf[12] = (byte) ((h >> 16) & 0xFF);
        buf[13] = 1;
    }

    public static byte[] stripAstcHeader(byte[] astc) throws Exception {
        if (astc.length < 16) throw new Exception("ASTC 数据异常");
        byte[] raw = new byte[astc.length - 16];
        System.arraycopy(astc, 16, raw, 0, raw.length);
        return raw;
    }

    public static byte[] packKtexLevels(List<int[]> dims, List<byte[]> datas) {
        int n = dims.size();
        int total = 8 + n * 10;
        for (byte[] p : datas) total += p.length;
        ByteBuffer bb = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        bb.put((byte) 'K').put((byte) 'T').put((byte) 'E').put((byte) 'X');
        bb.putInt(KTEX_BASE_FLAGS | (n << 13));
        for (int i = 0; i < n; i++) {
            int w = dims.get(i)[0], h = dims.get(i)[1];
            int pitch = ((w + 7) / 8) * 16;          // 8x8 块
            bb.putShort((short) w).putShort((short) h).putShort((short) pitch)
                    .putInt(datas.get(i).length);
        }
        for (byte[] p : datas) bb.put(p);
        return bb.array();
    }

    /**
     * 编码成完整 mip 链的 KTEX。只写 1 级 mip 会让启用 mipmap 过滤的机型采到黑图。
     */
    public static byte[] bitmapToKtex(Bitmap bmp, File tmpDir, String quality) throws Exception {
        List<int[]> dims = new ArrayList<>();
        List<byte[]> datas = new ArrayList<>();
        Bitmap cur = bmp;
        int w = cur.getWidth(), h = cur.getHeight();
        try {
            for (int level = 0; level < 20; level++) {
                File in = new File(tmpDir, "mip" + level + ".png");
                File out = new File(tmpDir, "mip" + level + ".astc");
                FileOutputStream fos = new FileOutputStream(in);
                try {
                    cur.compress(Bitmap.CompressFormat.PNG, 100, fos);
                } finally {
                    fos.close();
                }
                runAstcenc("-cl", in.getAbsolutePath(), out.getAbsolutePath(), ASTC_BLOCK, "-" + quality);
                dims.add(new int[]{w, h});
                datas.add(stripAstcHeader(readFile(out)));
                in.delete();
                out.delete();
                if (w == 1 && h == 1) break;
                int nw = Math.max(1, w / 2), nh = Math.max(1, h / 2);
                if (nw == w && nh == h) break;
                Bitmap next = Bitmap.createScaledBitmap(cur, nw, nh, true);
                if (cur != bmp) cur.recycle();
                cur = next;
                w = nw;
                h = nh;
            }
        } finally {
            if (cur != null && cur != bmp && !cur.isRecycled()) cur.recycle();
        }
        return packKtexLevels(dims, datas);
    }

    /** .tex(ASTC) → Bitmap（预览 / 图集解码） */
    public static Bitmap texToBitmap(File tex, File tmpDir) throws Exception {
        byte[] data = readFile(tex);
        KtexInfo info = unpackKtex(data);
        File astc = new File(tmpDir, "t" + TMP_SEQ.incrementAndGet() + ".astc");
        File png = new File(tmpDir, "t" + TMP_SEQ.incrementAndGet() + ".png");
        try {
            byte[] full = new byte[info.raw.length + 16];
            writeAstcHeader(full, info.w, info.h);
            System.arraycopy(info.raw, 0, full, 16, info.raw.length);
            writeFile(astc, full);
            runAstcenc("-dl", astc.getAbsolutePath(), png.getAbsolutePath());
            Bitmap bmp = BitmapFactory.decodeFile(png.getAbsolutePath());
            if (bmp == null) throw new Exception("PNG 解码失败");
            return bmp;
        } finally {
            astc.delete();
            png.delete();
        }
    }

    /** .tex(DXT/RGBA) → PNG 文件（原样存储顺序，未翻正） */
    public static void texToRawPng(File tex, File pngOut) throws Exception {
        runTex2png(tex.getAbsolutePath(), pngOut.getAbsolutePath());
    }

    /** 垂直翻转：KTEX 存储方向 ↔ 正常图像方向 */
    public static Bitmap flipVertical(Bitmap src) {
        Matrix m = new Matrix();
        m.preScale(1, -1);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
    }

    public static void flipPngFile(File src, File dst) throws Exception {
        Bitmap bmp = BitmapFactory.decodeFile(src.getAbsolutePath());
        if (bmp == null) throw new Exception("无法解码 PNG");
        Bitmap flipped = flipVertical(bmp);
        try {
            FileOutputStream out = new FileOutputStream(dst);
            try {
                flipped.compress(Bitmap.CompressFormat.PNG, 100, out);
            } finally {
                out.close();
            }
        } finally {
            if (flipped != bmp) flipped.recycle();
            bmp.recycle();
        }
    }

    // --------------------------------------------------------------------- dyn

    private static final int[] DYN_IDX = {5, 3, 6, 7, 4, 2, 0, 1};

    public static boolean isPk(byte[] b) {
        return b != null && b.length >= 2 && b[0] == 'P' && b[1] == 'K';
    }

    /** 8 字节分块：置换 + XOR。out[i] = in[idx[i]] ^ (0x8D + i) */
    public static byte[] dynDecrypt(byte[] in) {
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

    /** out[idx[i]] = in[i] ^ (0x8D + i) */
    public static byte[] dynEncrypt(byte[] in) {
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

    // ------------------------------------------------------------- file 工具

    public static byte[] readFile(File f) throws Exception {
        return readAll(new FileInputStream(f));
    }

    public static byte[] readAll(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            try { in.close(); } catch (Throwable ignored) { }
        }
    }

    public static void writeFile(File f, byte[] data) throws Exception {
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }

    /**
     * 原子写：同目录 .tmp → fsync → rename。
     * 中途失败（存储满 / 进程被杀 / OOM）时目标文件保持原样，不会变成半截垃圾。
     */
    public static void writeFileAtomic(File target, byte[] data) throws Exception {
        File dir = target.getParentFile();
        if (dir == null || !dir.isDirectory()) {
            writeFile(target, data);
            return;
        }
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
                AppLog.i("!! 原子 rename 失败，退化为直接写入: " + target.getName());
                writeFile(target, data);
            }
        } finally {
            if (tmp.exists()) tmp.delete();
        }
    }

    public static void copyFile(File src, File dst) throws Exception {
        InputStream in = new FileInputStream(src);
        OutputStream out = new FileOutputStream(dst);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            try { in.close(); } catch (Throwable ignored) { }
            try { out.close(); } catch (Throwable ignored) { }
        }
    }

    /** 备份为 &lt;name&gt;.bak；已存在则保留最早那份 */
    public static void backupOnce(File f) throws Exception {
        File bak = new File(f.getAbsolutePath() + ".bak");
        if (!bak.exists()) copyFile(f, bak);
    }

    /** 递归删除目录内容（不抛出） */
    public static void purgeDir(File dir) {
        if (dir == null) return;
        File[] fs = dir.listFiles();
        if (fs != null) {
            for (File c : fs) {
                if (c.isDirectory()) purgeDir(c);
                else c.delete();
            }
        }
        dir.delete();
    }

    public static String baseName(File f) {
        String n = f.getName();
        int i = n.lastIndexOf('.');
        return i > 0 ? n.substring(0, i) : n;
    }

    public static String humanSize(long b) {
        if (b < 0) return "?";
        if (b < 1024) return b + " B";
        if (b < 1048576) return String.format("%.1f KB", b / 1024.0);
        return String.format("%.1f MB", b / 1048576.0);
    }
}
