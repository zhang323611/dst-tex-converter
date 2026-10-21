package com.dsttex.core;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;

/**
 * DST 动画渲染器 —— 预览界面与编辑器共用同一份实现。
 *
 * 渲染方式：build 帧的精灵由若干三角形拼成，逐三角形用 setPolyToPoly 求图集→屏幕的仿射，
 * 再 clipPath + concat + drawBitmap 贴图。
 * 注意：不可用 BitmapShader.setLocalMatrix —— 硬件加速下它不会传播到 Paint 的缓存状态，
 * 会导致采样退化为画布坐标、填充全透明。
 */
public final class AnimRenderer {

    /** 动画坐标 → 屏幕坐标。两个界面各自实现，渲染器不关心具体映射。 */
    public interface Xform {
        float x(float ax);

        float y(float ay);
    }

    private final Paint paint = new Paint();
    private final Paint stroke = new Paint();
    private final Path tri = new Path();
    private final Matrix mtx = new Matrix();
    private final float[] src = new float[6];
    private final float[] dst = new float[6];

    // 诊断统计（调试模式下显示）
    public int tris, clipFail;
    public int elemsTotal, elemsMatched, elemsUnmatched;
    public int lastHash, lastBF;
    public boolean flipUv = false;
    /** 层序：实测 z 大者先画（在底层），z 小者后画（覆盖在上）—— 默认降序 */
    public boolean zDescending = true;
    public float uvMnx, uvMny, uvMxx, uvMxy;

    private AnimFormat.AElement[] order = new AnimFormat.AElement[0];

    public AnimRenderer() {
        paint.setFilterBitmap(true);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.5f);
        stroke.setColor(0xFF00FF88);
    }

    public void draw(Canvas cv, Bitmap atlas, AnimFormat.BuildFile build,
                     AnimFormat.AAnim anim, int frame, Xform xf, boolean showStroke) {
        tris = 0;
        clipFail = 0;
        elemsTotal = 0;
        elemsMatched = 0;
        elemsUnmatched = 0;
        uvMnx = 1e9f;
        uvMny = 1e9f;
        uvMxx = -1e9f;
        uvMxy = -1e9f;
        if (atlas == null || anim == null || anim.frames.length == 0) return;

        final float aw = atlas.getWidth(), ah = atlas.getHeight();
        AnimFormat.AFrame fr = anim.frames[Math.min(Math.max(frame, 0), anim.frames.length - 1)];

        if (order.length != fr.elems.length) order = new AnimFormat.AElement[fr.elems.length];
        System.arraycopy(fr.elems, 0, order, 0, fr.elems.length);
        java.util.Arrays.sort(order, (x, y) -> zDescending
                ? Float.compare(y.z, x.z) : Float.compare(x.z, y.z));

        for (AnimFormat.AElement e : order) {
            elemsTotal++;
            AnimFormat.BSymbol sym = build.symbols.get(e.hash);
            if (sym == null || sym.frames.length == 0) {
                elemsUnmatched++;
                lastHash = (int) e.hash;
                lastBF = e.buildFrame;
                continue;
            }
            elemsMatched++;
            AnimFormat.BFrame bf = sym.frames[Math.min(e.buildFrame, sym.frames.length - 1)];

            for (int t = 0; t < bf.ntris; t++) {
                for (int k = 0; k < 3; k++) {
                    float x = bf.xyz[t * 9 + k * 3];
                    float y = bf.xyz[t * 9 + k * 3 + 1];
                    float u = bf.uv[t * 9 + k * 3];
                    float v = bf.uv[t * 9 + k * 3 + 1];
                    // 元素矩阵：局部坐标 → 动画坐标（ktools 约定 x'=a*x+b*y+tx, y'=c*x+d*y+ty）
                    float ax = e.a * x + e.b * y + e.tx;
                    float ay = e.c * x + e.d * y + e.ty;
                    dst[k * 2] = xf.x(ax);
                    dst[k * 2 + 1] = xf.y(ay);
                    // UV → 图集像素。默认不翻转（实测翻转会落到图集空白区）
                    src[k * 2] = u * aw;
                    src[k * 2 + 1] = (flipUv ? (1f - v) : v) * ah;
                    if (u < uvMnx) uvMnx = u;
                    if (u > uvMxx) uvMxx = u;
                    if (v < uvMny) uvMny = v;
                    if (v > uvMxy) uvMxy = v;
                }
                if (!mtx.setPolyToPoly(src, 0, dst, 0, 3)) continue;
                tri.reset();
                tri.moveTo(dst[0], dst[1]);
                tri.lineTo(dst[2], dst[3]);
                tri.lineTo(dst[4], dst[5]);
                tri.close();
                cv.save();
                if (!cv.clipPath(tri)) clipFail++;
                cv.concat(mtx);
                cv.drawBitmap(atlas, 0, 0, paint);
                cv.restore();
                tris++;
                if (showStroke) cv.drawPath(tri, stroke);
            }
        }
    }

    /**
     * 用真实三角形顶点计算元素的屏幕包围盒。
     * 不可用 build 帧自带的 bbox —— 它并不包围全部三角形（实测左边界差约 256 单位），
     * 用它会导致选中框/命中检测整体偏移。
     */
    public static float[] bounds(AnimFormat.BuildFile build, AnimFormat.AElement e, Xform xf) {
        AnimFormat.BSymbol sym = build.symbols.get(e.hash);
        if (sym == null || sym.frames.length == 0) return null;
        AnimFormat.BFrame bf = sym.frames[Math.min(e.buildFrame, sym.frames.length - 1)];
        float x0 = 1e9f, y0 = 1e9f, x1 = -1e9f, y1 = -1e9f;
        for (int t = 0; t < bf.ntris; t++) {
            for (int k = 0; k < 3; k++) {
                float x = bf.xyz[t * 9 + k * 3];
                float y = bf.xyz[t * 9 + k * 3 + 1];
                float sx = xf.x(e.a * x + e.b * y + e.tx);
                float sy = xf.y(e.c * x + e.d * y + e.ty);
                if (sx < x0) x0 = sx;
                if (sx > x1) x1 = sx;
                if (sy < y0) y0 = sy;
                if (sy > y1) y1 = sy;
            }
        }
        return x0 > x1 ? null : new float[]{x0, y0, x1, y1};
    }
}
