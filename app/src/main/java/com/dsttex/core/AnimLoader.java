package com.dsttex.core;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 从动画压缩包（mod 的 anim 包）载入 build.bin / anim.bin / 图集。
 * 旧实现把解析结果塞进 Activity 的 static 字段，进程被回收后编辑器直接白屏；
 * 现在由各界面按路径自己加载。
 */
public final class AnimLoader {

    public static final class Loaded {
        public File src;
        public byte[] animBytes;
        public AnimFormat.BuildFile build;
        public AnimFormat.AnimFile anim;
        public Bitmap atlas;
    }

    private AnimLoader() { }

    public static Loaded load(File zip, File tmpDir) throws Exception {
        byte[] animD = null, buildD = null, atlasD = null;
        String atlasName = null;
        ZipFile zf = new ZipFile(zip);
        try {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                String ln = ze.getName().toLowerCase();
                if (ln.endsWith("anim.bin")) animD = TexCodec.readAll(zf.getInputStream(ze));
                else if (ln.endsWith("build.bin")) buildD = TexCodec.readAll(zf.getInputStream(ze));
                else if (ln.endsWith(".tex") && atlasD == null) {
                    atlasD = TexCodec.readAll(zf.getInputStream(ze));
                    atlasName = ze.getName();
                }
            }
        } finally {
            zf.close();
        }
        if (animD == null || buildD == null) throw new Exception("压缩包内缺 anim.bin 或 build.bin");

        Loaded out = new Loaded();
        out.src = zip;
        out.animBytes = animD;
        out.build = AnimFormat.parseBuild(buildD);
        out.anim = AnimFormat.parseAnim(animD);
        if (out.anim.anims.length == 0) throw new Exception("压缩包内没有动画");

        if (atlasD != null) {
            AppLog.i("动画图集: " + atlasName + " (" + atlasD.length + "B)");
            File tf = new File(tmpDir, "atlas" + System.nanoTime() + ".tex");
            try {
                TexCodec.writeFile(tf, atlasD);
                int comp = TexCodec.readTexCompression(atlasD);
                if (comp == 24) {
                    // ASTC 图集直接解码；UV 映射已处理朝向，图集本身不翻转
                    out.atlas = TexCodec.texToBitmap(tf, tmpDir);
                } else {
                    File png = new File(tmpDir, "atlas" + System.nanoTime() + ".png");
                    try {
                        TexCodec.texToRawPng(tf, png);
                        out.atlas = BitmapFactory.decodeFile(png.getAbsolutePath());
                    } finally {
                        png.delete();
                    }
                }
            } finally {
                tf.delete();
            }
        }
        return out;
    }
}
