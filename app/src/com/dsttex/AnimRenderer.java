package com.dsttex;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;

/**
 * DST 动画渲染器 —— 预览界面与编辑器共用同一份实现。
 *
 * 背景：此前预览(AnimView)与编辑器(AnimEditorView)各写了一份几乎相同的绘制代码，
 * 项目里几乎所有 bug 都源于这种重复：UV 采样在一处硬编码翻转、坐标变换三处各写一份、
 * 选中框在一处用了错误的包围盒。合并后，改动只需做一次。
 *
 * 渲染方式：build 帧的精灵由若干三角形拼成，逐三角形用 setPolyToPoly 求
 * 图集->屏幕的仿射变换，再 clipPath + concat + drawBitmap 贴图。
 * 注意：不可用 BitmapShader.setLocalMatrix —— 硬件加速下它不会传播到 Paint 的缓存状态，
 * 会导致采样退化为画布坐标、填充全透明。
 */
public final class AnimRenderer {

    /** 动画坐标 -> 屏幕坐标。两个界面各自实现，渲染器不关心具体映射。 */
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

    // ---- 供诊断叠层读取的统计 ----
    public int tris, clipFail;
    public int elemsTotal, elemsMatched, elemsUnmatched;   // 诊断: 元素->符号 匹配情况
    public int lastHash, lastBF;                           // 诊断: 最后一个未匹配元素
    public boolean flipUv = false;                         // 供对照实验: 翻转 v 采样方向
    // 层序: 实测 z 大者先画(在底层), z 小者后画(覆盖在上) —— 默认降序。
    // (依据: abigail 资产在升序下头发盖住面部, 降序下与官方一致)
    public boolean zDescending = true;
    public float uvMnx, uvMny, uvMxx, uvMxy;

    public AnimRenderer() {
        paint.setFilterBitmap(true);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.5f);
        stroke.setColor(0xFF00FF88);
    }

    /**
     * 渲染一帧。
     * @param showStroke 是否描出三角形轮廓(仅调试模式)
     */
    public void draw(Canvas cv, Bitmap atlas, TexConverter.BuildFile build,
                     TexConverter.AAnim anim, int frame, Xform xf, boolean showStroke) {
        tris = 0; clipFail = 0;
        elemsTotal = 0; elemsMatched = 0; elemsUnmatched = 0;
        uvMnx = 1e9f; uvMny = 1e9f; uvMxx = -1e9f; uvMxy = -1e9f;
        if (atlas == null || anim == null || anim.frames.length == 0) return;

        final float aw = atlas.getWidth(), ah = atlas.getHeight();
        TexConverter.AFrame fr = anim.frames[Math.min(frame, anim.frames.length - 1)];

        // 绘制顺序由 z 决定(文件中的排列不是绘制顺序):
        // z 小者在后(先画), z 大者在前(后画覆盖)
        TexConverter.AElement[] order = fr.elems.clone();
        java.util.Arrays.sort(order, (x, y) ->
                zDescending ? Float.compare(y.z, x.z) : Float.compare(x.z, y.z));

        for (TexConverter.AElement e : order) {
            elemsTotal++;
            TexConverter.BSymbol sym = build.symbols.get(e.hash);
            if (sym == null || sym.frames.length == 0) {
                elemsUnmatched++;
                lastHash = (int) e.hash; lastBF = e.buildFrame;
                continue;
            }
            elemsMatched++;
            TexConverter.BFrame bf = sym.frames[Math.min(e.buildFrame, sym.frames.length - 1)];

            for (int t = 0; t < bf.ntris; t++) {
                for (int k = 0; k < 3; k++) {
                    float x = bf.xyz[t * 9 + k * 3];
                    float y = bf.xyz[t * 9 + k * 3 + 1];
                    float u = bf.uv[t * 9 + k * 3];
                    float v = bf.uv[t * 9 + k * 3 + 1];
                    // 元素矩阵: 局部坐标 -> 动画坐标
                    // ktools 约定 M[row][col]: x'=a*x+b*y+tx, y'=c*x+d*y+ty
                    // (此处曾把 b/c 写反; 单位矩阵的资产看不出问题, 带旋转的资产会错乱)
                    float ax = e.a * x + e.b * y + e.tx;
                    float ay = e.c * x + e.d * y + e.ty;
                    dst[k * 2]     = xf.x(ax);
                    dst[k * 2 + 1] = xf.y(ay);
                    // UV -> 图集像素。不翻转: 实测翻转后会落到图集完全空白的区域。
                    src[k * 2]     = u * aw;
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
     * 不可用 build 帧自带的 bbox —— 它并不包围全部三角形（实测左边界相差约 256 单位），
     * 用它会导致选中框/命中检测整体偏移。
     * @return {x0,y0,x1,y1}，元素无三角形时返回 null
     */
    public static float[] bounds(TexConverter.BuildFile build, TexConverter.AElement e, Xform xf) {
        TexConverter.BSymbol sym = build.symbols.get(e.hash);
        if (sym == null || sym.frames.length == 0) return null;
        TexConverter.BFrame bf = sym.frames[Math.min(e.buildFrame, sym.frames.length - 1)];
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
