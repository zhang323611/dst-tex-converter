package com.dsttex.core;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;

/**
 * DST 动画二进制格式（build.bin / anim.bin）解析。
 * 格式依据 ktools/krane，已用真实样本逐字节验证（解析到文件末尾剩余 0）。
 *
 * 与旧实现的差别：所有读取都做长度校验。之前按文件里声明的顶点数直接 new 数组，
 * 一个伪造/半截文件就能把进程 OOM 掉。
 */
public final class AnimFormat {

    private AnimFormat() { }

    /** 越界即抛异常的字节读取器 */
    public static final class BinReader {
        final byte[] d;
        int p = 0;

        public BinReader(byte[] b) { d = b; }

        private void need(int n) throws Exception {
            if (p + n > d.length) throw new Exception("数据越界: 需要 " + n + " 字节, 剩余 " + (d.length - p));
        }

        public int u8() throws Exception { need(1); return d[p++] & 0xFF; }

        public int u32() throws Exception {
            need(4);
            int v = (d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8)
                    | ((d[p + 2] & 0xFF) << 16) | ((d[p + 3] & 0xFF) << 24);
            p += 4;
            return v;
        }

        public long u32l() throws Exception { return u32() & 0xFFFFFFFFL; }

        public float f32() throws Exception { return Float.intBitsToFloat(u32()); }

        public String str() throws Exception {
            int n = u32();
            if (n < 0 || p + n > d.length) throw new Exception("字符串长度异常: " + n);
            String s = new String(d, p, n, StandardCharsets.UTF_8);
            p += n;
            return s;
        }

        public int left() { return d.length - p; }
    }

    public static final class BFrame {
        public int alphaidx;      // 该帧顶点在全局顶点区中的起始下标
        public int framenum, duration;
        public float x, y, w, h;
        public int ntris;
        public float[] xyz;   // ntris*9：每三角形 3 顶点 × (x,y,z)
        public float[] uv;    // ntris*9：每三角形 3 顶点 × (u,v,w)
    }

    public static final class BSymbol {
        public long hash;
        public String name;
        public BFrame[] frames;
    }

    public static final class BuildFile {
        public String name;
        public String[] atlases;
        public final LinkedHashMap<Long, BSymbol> symbols = new LinkedHashMap<>();
    }

    public static final class AElement {
        public long hash;
        public int buildFrame;
        public long layer;
        public float a, b, c, d, tx, ty, z;
        public int txOffset = -1;   // tx 字段在 anim.bin 中的字节偏移（用于原地改写）
    }

    public static final class AFrame {
        public float x, y, w, h;
        public AElement[] elems;
    }

    public static final class AAnim {
        public String name;
        public int facing;
        public long bank;
        public float frameRate;
        public AFrame[] frames;
    }

    public static final class AnimFile {
        public AAnim[] anims;
    }

    // ---------------------------------------------------------------- build.bin

    public static BuildFile parseBuild(byte[] d) throws Exception {
        if (d == null || d.length < 8
                || d[0] != 'B' || d[1] != 'I' || d[2] != 'L' || d[3] != 'D') {
            throw new Exception("不是 build.bin");
        }
        BinReader r = new BinReader(d);
        r.p = 4;
        r.u32();
        int nsym = r.u32();
        if (nsym < 0 || nsym > 100000) throw new Exception("符号数异常: " + nsym);
        r.u32();
        BuildFile b = new BuildFile();
        b.name = r.str();
        int natlas = r.u32();
        if (natlas < 0 || natlas > 10000) throw new Exception("图集数异常: " + natlas);
        b.atlases = new String[natlas];
        for (int i = 0; i < natlas; i++) b.atlases[i] = r.str();

        BSymbol[] syms = new BSymbol[nsym];
        for (int i = 0; i < nsym; i++) {
            BSymbol s = new BSymbol();
            s.hash = r.u32l();
            int nf = r.u32();
            if (nf < 0 || nf > 100000) throw new Exception("帧数异常: " + nf);
            s.frames = new BFrame[nf];
            for (int j = 0; j < nf; j++) {
                BFrame f = new BFrame();
                f.framenum = r.u32();
                f.duration = r.u32();
                f.x = r.f32();
                f.y = r.f32();
                f.w = r.f32();
                f.h = r.f32();
                f.alphaidx = r.u32();      // 顶点起始下标（丢弃它会导致顶点错配）
                f.ntris = r.u32() / 3;
                if (f.ntris < 0) throw new Exception("三角形数异常");
                s.frames[j] = f;
            }
            syms[i] = s;
        }

        int totalVerts = r.u32();
        if (totalVerts < 0) throw new Exception("顶点数异常: " + totalVerts);
        // 先校验剩余字节真的够，再分配：每顶点 6 个 float = 24 字节
        if ((long) totalVerts * 24 > r.left()) {
            throw new Exception("顶点数据长度不足: 声明 " + totalVerts + " 个顶点, 剩余 " + r.left() + " 字节");
        }
        float[] vx = new float[totalVerts], vy = new float[totalVerts], vz = new float[totalVerts];
        float[] vu = new float[totalVerts], vv = new float[totalVerts], vw = new float[totalVerts];
        for (int i = 0; i < totalVerts; i++) {
            vx[i] = r.f32();
            vy[i] = r.f32();
            vz[i] = r.f32();
            vu[i] = r.f32();
            vv[i] = r.f32();
            vw[i] = r.f32();
        }

        // 顶点区物理顺序与符号表顺序无关，必须用每帧的 alphaidx 定位
        for (BSymbol s : syms) {
            for (BFrame f : s.frames) {
                int n = f.ntris * 3;
                f.xyz = new float[n * 3];
                f.uv = new float[n * 3];
                for (int k = 0; k < n; k++) {
                    int idx = f.alphaidx + k;
                    if (idx < 0 || idx >= totalVerts) continue;   // 越界保持 0
                    f.xyz[k * 3] = vx[idx];
                    f.xyz[k * 3 + 1] = vy[idx];
                    f.xyz[k * 3 + 2] = vz[idx];
                    f.uv[k * 3] = vu[idx];
                    f.uv[k * 3 + 1] = vv[idx];
                    f.uv[k * 3 + 2] = vw[idx];
                }
            }
        }

        int htsz = r.u32();
        if (htsz < 0 || htsz > 100000) throw new Exception("名字表异常: " + htsz);
        for (int i = 0; i < htsz; i++) {
            long h = r.u32l();
            String nm = r.str();
            for (BSymbol s : syms) if (s.hash == h) s.name = nm;
        }
        for (BSymbol s : syms) b.symbols.put(s.hash, s);
        return b;
    }

    // ----------------------------------------------------------------- anim.bin

    public static AnimFile parseAnim(byte[] d) throws Exception {
        if (d == null || d.length < 8
                || d[0] != 'A' || d[1] != 'N' || d[2] != 'I' || d[3] != 'M') {
            throw new Exception("不是 anim.bin");
        }
        BinReader r = new BinReader(d);
        r.p = 4;
        r.u32();
        r.u32();
        r.u32();
        r.u32();
        int nanim = r.u32();
        if (nanim < 0 || nanim > 100000) throw new Exception("动画数异常: " + nanim);
        AnimFile a = new AnimFile();
        a.anims = new AAnim[nanim];
        for (int i = 0; i < nanim; i++) {
            AAnim an = new AAnim();
            an.name = r.str();
            an.facing = r.u8();          // 1 字节
            an.bank = r.u32l();
            an.frameRate = r.f32();
            int nf = r.u32();
            if (nf < 0 || nf > 100000) throw new Exception("帧数异常: " + nf);
            an.frames = new AFrame[nf];
            for (int j = 0; j < nf; j++) {
                AFrame f = new AFrame();
                f.x = r.f32();
                f.y = r.f32();
                f.w = r.f32();
                f.h = r.f32();
                int nev = r.u32();
                if (nev < 0 || nev > 100000) throw new Exception("事件数异常: " + nev);
                for (int k = 0; k < nev; k++) r.u32();
                int nel = r.u32();
                if (nel < 0 || nel > 100000) throw new Exception("元素数异常: " + nel);
                f.elems = new AElement[nel];
                for (int k = 0; k < nel; k++) {
                    AElement e = new AElement();
                    e.hash = r.u32l();
                    e.buildFrame = r.u32();
                    e.layer = r.u32l();
                    e.a = r.f32();
                    e.b = r.f32();
                    e.c = r.f32();
                    e.d = r.f32();
                    e.txOffset = r.p;          // 记录 tx 起始偏移，保存时原地改写
                    e.tx = r.f32();
                    e.ty = r.f32();
                    e.z = r.f32();
                    f.elems[k] = e;
                }
                an.frames[j] = f;
            }
            a.anims[i] = an;
        }
        return a;
    }
}
