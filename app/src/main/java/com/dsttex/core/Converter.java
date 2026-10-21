package com.dsttex.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 纹理转换流水线：文件级 + zip 级（含嵌套 zip）。
 *
 * 与旧实现的差别（都是审查里发现的实际问题）：
 *  - 写盘全部原子替换 + 默认备份；
 *  - "跳过"用 {@link Skip} 表达，不再混进失败；
 *  - zip 内 TEX→PNG 会把条目名一起改成 .png（并去重）；
 *  - 无改动时不重写原 zip；临时包放原目录，rename 原子且不吃 cacheDir 配额。
 */
public final class Converter {

    /** 按当前模式这个文件不需要处理（不是失败） */
    public static final class Skip extends Exception {
        public Skip(String m) { super(m); }
    }

    private static final int MAX_ZIP_DEPTH = 8;

    private Converter() { }

    // ------------------------------------------------------------------ 文件级

    public static String convertFile(File f, File outputDir, File tmpDir,
                                     Settings.Mode mode, String quality, boolean backup)
            throws Skip, Exception {
        String n = f.getName().toLowerCase();
        AppLog.i("convertFile " + f.getName() + " (" + f.length() + "B) mode=" + mode.id);
        if (n.endsWith(".bak")) throw new Skip("备份文件，跳过");
        if (n.endsWith(".png")) {
            if (mode == Settings.Mode.TEX2PNG) throw new Skip("当前模式为 TEX→PNG，跳过 PNG");
            return pngToTex(f, outputDir, tmpDir, quality, backup);
        }
        if (n.endsWith(".tex")) {
            int comp = TexCodec.readTexCompression(f);
            if (mode == Settings.Mode.PNG2TEX) throw new Skip("当前模式为 PNG→TEX，跳过 TEX");
            if (mode == Settings.Mode.TEX2PNG) return texToPng(f, outputDir, tmpDir);
            if (mode == Settings.Mode.DXT2ASTC) {
                if (comp == 24) throw new Skip("已是 ASTC，跳过");
                return texToAstc(f, tmpDir, quality, backup);
            }
            // auto：ASTC 反解成 PNG，其余转 ASTC
            return comp == 24 ? texToPng(f, outputDir, tmpDir) : texToAstc(f, tmpDir, quality, backup);
        }
        throw new Skip("不支持的文件类型");
    }

    private static String pngToTex(File png, File outputDir, File tmpDir,
                                   String quality, boolean backup) throws Exception {
        File flip = new File(tmpDir, "flip.png");
        TexCodec.flipPngFile(png, flip);
        android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(flip.getAbsolutePath());
        if (bmp == null) throw new Exception("无法解码 PNG");
        byte[] ktex;
        try {
            ktex = TexCodec.bitmapToKtex(bmp, tmpDir, quality);
        } finally {
            bmp.recycle();
            flip.delete();
        }
        File out = new File(outputDir, TexCodec.baseName(png) + ".tex");
        if (out.exists() && backup) {
            TexCodec.backupOnce(out);
            AppLog.i("覆盖同名 tex（已备份）: " + out.getName());
        }
        TexCodec.writeFileAtomic(out, ktex);
        return out.getName();
    }

    private static String texToAstc(File tex, File tmpDir, String quality, boolean backup) throws Exception {
        File decoded = new File(tmpDir, "decoded.png");
        TexCodec.texToRawPng(tex, decoded);
        android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(decoded.getAbsolutePath());
        if (bmp == null) throw new Exception("无法解码中间 PNG");
        byte[] ktex;
        try {
            ktex = TexCodec.bitmapToKtex(bmp, tmpDir, quality);
        } finally {
            bmp.recycle();
            decoded.delete();
        }
        if (backup) TexCodec.backupOnce(tex);
        TexCodec.writeFileAtomic(tex, ktex);
        return backup ? "已替换，原文件→" + tex.getName() + ".bak" : "已替换（未备份）";
    }

    private static String texToPng(File tex, File outputDir, File tmpDir) throws Exception {
        byte[] png = texBytesToPng(TexCodec.readFile(tex), 24, tmpDir);
        File out = new File(outputDir, TexCodec.baseName(tex) + ".png");
        if (out.exists()) TexCodec.backupOnce(out);
        TexCodec.writeFileAtomic(out, png);
        return out.getName();
    }

    /** tex 字节 → 已翻正的 PNG 字节（ASTC 走 astcenc，其余走 tex2png） */
    static byte[] texBytesToPng(byte[] data, int comp, File tmpDir) throws Exception {
        int seq = (int) (System.nanoTime() & 0x7FFFFFFF);
        File t = new File(tmpDir, "c" + seq + ".tex");
        File p = new File(tmpDir, "c" + seq + ".png");
        File astc = new File(tmpDir, "c" + seq + ".astc");
        File flipped = new File(tmpDir, "c" + seq + "_f.png");
        try {
            if (comp == 24) {
                TexCodec.KtexInfo info = TexCodec.unpackKtex(data);
                byte[] full = new byte[info.raw.length + 16];
                TexCodec.writeAstcHeader(full, info.w, info.h);
                System.arraycopy(info.raw, 0, full, 16, info.raw.length);
                TexCodec.writeFile(astc, full);
                TexCodec.runAstcenc("-dl", astc.getAbsolutePath(), p.getAbsolutePath());
            } else {
                TexCodec.writeFile(t, data);
                TexCodec.texToRawPng(t, p);
            }
            // KTEX 是垂直翻转存储，导出前翻正（与文件路径 texToPng 行为一致）
            TexCodec.flipPngFile(p, flipped);
            return TexCodec.readFile(flipped);
        } finally {
            t.delete();
            p.delete();
            astc.delete();
            flipped.delete();
        }
    }

    /** tex 字节 → ASTC tex 字节 */
    static byte[] texBytesToAstc(byte[] data, File tmpDir, String quality) throws Exception {
        int seq = (int) (System.nanoTime() & 0x7FFFFFFF);
        File t = new File(tmpDir, "a" + seq + ".tex");
        File p = new File(tmpDir, "a" + seq + ".png");
        try {
            TexCodec.writeFile(t, data);
            TexCodec.texToRawPng(t, p);
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(p.getAbsolutePath());
            if (bmp == null) throw new Exception("无法解码中间 PNG");
            try {
                return TexCodec.bitmapToKtex(bmp, tmpDir, quality);
            } finally {
                bmp.recycle();
            }
        } finally {
            t.delete();
            p.delete();
        }
    }

    // ------------------------------------------------------------------ zip 级

    /** 一次 tex 转换的结果：数据 + 是否改动 + 需要改成的新条目名（null = 不改） */
    private static final class TexOut {
        final byte[] data;
        final boolean changed;
        final String name;

        TexOut(byte[] data, boolean changed, String name) {
            this.data = data;
            this.changed = changed;
            this.name = name;
        }
    }

    private static TexOut convertTexByMode(byte[] data, File tmpDir, String name,
                                           Settings.Mode mode, String quality, int[] skip) throws Exception {
        int comp = TexCodec.readTexCompression(data);
        AppLog.i("    tex " + name + " comp=" + comp + " mode=" + mode.id);
        if (mode == Settings.Mode.PNG2TEX) {
            skip[1]++;
            return new TexOut(data, false, null);
        }
        if (mode == Settings.Mode.DXT2ASTC && comp == 24) {
            skip[0]++;
            return new TexOut(data, false, null);
        }
        if (mode == Settings.Mode.TEX2PNG || (mode == Settings.Mode.AUTO && comp == 24)) {
            return new TexOut(texBytesToPng(data, comp, tmpDir), true, replaceExt(name, ".png"));
        }
        return new TexOut(texBytesToAstc(data, tmpDir, quality), true, null);
    }

    private static String replaceExt(String name, String newExt) {
        int slash = name.lastIndexOf('/');
        int dot = name.lastIndexOf('.');
        return dot <= slash ? name + newExt : name.substring(0, dot) + newExt;
    }

    private static String uniqueName(Set<String> used, String name) {
        if (used.add(name)) return name;
        String base = name, ext = "";
        int dot = name.lastIndexOf('.');
        int slash = name.lastIndexOf('/');
        if (dot > slash) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        for (int i = 1; i < 1000; i++) {
            String cand = base + "~" + i + ext;
            if (used.add(cand)) return cand;
        }
        return name;
    }

    /** 转换 zip；返回给用户看的摘要；原 zip 原地替换（原子 + 备份） */
    public static String convertZip(File zip, File tmpDir, Settings.Mode mode,
                                    String quality, boolean backup, boolean nested) throws Exception {
        AppLog.i("convertZip " + zip.getName() + " (" + zip.length() + "B) nested=" + nested);
        int[] skip = new int[2];
        int[] ok = new int[1];

        File tmp = new File(zip.getParentFile(), zip.getName() + ".tmp" + System.nanoTime());
        ZipFile zf = null;
        try {
            zf = new ZipFile(zip);
            ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tmp));
            try {
                java.util.Enumeration<? extends ZipEntry> en = zf.entries();
                Set<String> used = new HashSet<>();
                while (en.hasMoreElements()) {
                    ZipEntry ze = en.nextElement();
                    String entryName = ze.getName();
                    byte[] data = null;
                    if (!ze.isDirectory()) {
                        String ln = entryName.toLowerCase();
                        if (ln.endsWith(".tex")) {
                            byte[] raw = TexCodec.readAll(zf.getInputStream(ze));
                            try {
                                TexOut o = convertTexByMode(raw, tmpDir, entryName, mode, quality, skip);
                                data = o.data;
                                if (o.changed) ok[0]++;
                                if (o.name != null) entryName = o.name;
                            } catch (Exception ex) {
                                AppLog.e("跳过 " + entryName, ex);
                                data = raw;
                            }
                        } else if (ln.endsWith(".zip") && nested) {
                            byte[] inner = TexCodec.readAll(zf.getInputStream(ze));
                            Object[] r = convertZipBytes(inner, tmpDir, 0, mode, quality, skip);
                            data = (byte[]) r[0];
                            ok[0] += (Integer) r[1];
                        }
                    }
                    ZipEntry ne = new ZipEntry(uniqueName(used, entryName));
                    if (ze.getTime() > 0) ne.setTime(ze.getTime());
                    zos.putNextEntry(ne);
                    if (data != null) {
                        zos.write(data);
                    } else {
                        copy(zf.getInputStream(ze), zos);
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
            tmp.delete();
            AppLog.i("convertZip 无改动, 保持原包 " + zip.getName() + tail);
            return "zip 内无可转换的 tex（原包未改动）" + tail;
        }
        if (backup) TexCodec.backupOnce(zip);
        if (!tmp.renameTo(zip)) {
            AppLog.i("!! zip 原子替换失败，退化为拷贝覆盖: " + zip.getName());
            TexCodec.copyFile(tmp, zip);
            tmp.delete();
        }
        AppLog.i("convertZip 完成 " + zip.getName() + " -> " + ok[0] + tail);
        return "zip 内 " + ok[0] + " 个 tex 已转换" + tail
                + (backup ? "（原包→" + zip.getName() + ".bak）" : "");
    }

    private static Object[] convertZipBytes(byte[] zipData, File tmpDir, int depth,
                                            Settings.Mode mode, String quality, int[] skip) throws Exception {
        if (depth > MAX_ZIP_DEPTH) return new Object[]{zipData, 0};
        int seq = (int) (System.nanoTime() & 0x7FFFFFFF);
        File in = new File(tmpDir, "in" + seq + ".zip");
        File out = new File(tmpDir, "out" + seq + ".zip");
        TexCodec.writeFile(in, zipData);
        int ok = 0;
        ZipFile zf = null;
        try {
            zf = new ZipFile(in);
            ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out));
            try {
                java.util.Enumeration<? extends ZipEntry> en = zf.entries();
                Set<String> used = new HashSet<>();
                while (en.hasMoreElements()) {
                    ZipEntry ze = en.nextElement();
                    String entryName = ze.getName();
                    String n = entryName.toLowerCase();
                    byte[] data = null;
                    if (!ze.isDirectory()) {
                        if (n.endsWith(".tex")) {
                            byte[] raw = TexCodec.readAll(zf.getInputStream(ze));
                            try {
                                TexOut o = convertTexByMode(raw, tmpDir, entryName, mode, quality, skip);
                                data = o.data;
                                if (o.changed) ok++;
                                if (o.name != null) entryName = o.name;
                            } catch (Exception ex) {
                                AppLog.e("跳过 " + entryName, ex);
                                data = raw;
                            }
                        } else if (n.endsWith(".zip")) {
                            byte[] inner = TexCodec.readAll(zf.getInputStream(ze));
                            Object[] r = convertZipBytes(inner, tmpDir, depth + 1, mode, quality, skip);
                            data = (byte[]) r[0];
                            ok += (Integer) r[1];
                        }
                    }
                    ZipEntry ne = new ZipEntry(uniqueName(used, entryName));
                    if (ze.getTime() > 0) ne.setTime(ze.getTime());
                    zos.putNextEntry(ne);
                    if (data != null) zos.write(data);
                    else copy(zf.getInputStream(ze), zos);
                    zos.closeEntry();
                }
            } finally {
                try { zos.close(); } catch (Throwable ignored) { }
            }
        } finally {
            if (zf != null) { try { zf.close(); } catch (Throwable ignored) { } }
        }
        byte[] res = TexCodec.readFile(out);
        in.delete();
        out.delete();
        return new Object[]{res, ok};
    }

    private static void copy(InputStream in, ZipOutputStream zos) throws Exception {
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
        } finally {
            try { in.close(); } catch (Throwable ignored) { }
        }
    }
}
