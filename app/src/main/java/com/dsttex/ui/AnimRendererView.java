package com.dsttex.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import com.dsttex.core.AnimFormat;
import com.dsttex.core.AnimRenderer;

/**
 * 动画预览画布：只负责把一帧画出来（+ 可选诊断叠层）。
 * 编辑器界面继承它，复用同一套坐标映射与渲染器，避免"两处各写一份变换"的老问题。
 */
public class AnimRendererView extends View {

    protected Bitmap atlas;
    protected AnimFormat.BuildFile build;
    protected AnimFormat.AAnim anim;
    protected int frame = 0;

    protected float zoom = 1f;
    protected float panX = 0f, panY = 0f;
    protected float anCx = 0f, anCy = 0f, spanW = 1f, spanH = 1f;
    protected float fitFactor = 0.85f;

    /** 目标朝向：0=原样 1=旋转180 2=上下翻转 3=左右翻转。默认 2（补偿 KTEX 垂直翻转存储） */
    protected int dstMode = 2;

    public boolean debug = false;

    public final AnimRenderer renderer = new AnimRenderer();

    private final Paint bg = new Paint();
    private final Paint dbg = new Paint();

    public AnimRendererView(Context c) {
        this(c, null);
    }

    public AnimRendererView(Context c, AttributeSet a) {
        super(c, a);
        bg.setColor(0xFF303030);
        dbg.setColor(0xFFFFEE55);
        dbg.setTextSize(28f);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);   // 绕开硬件加速对 clipPath 的差异
    }

    public void setData(Bitmap atlas, AnimFormat.BuildFile build, AnimFormat.AAnim anim) {
        this.atlas = atlas;
        this.build = build;
        this.anim = anim;
        this.frame = 0;
        resetBounds();
        invalidate();
    }

    protected void resetBounds() {
        if (anim == null || anim.frames.length == 0) return;
        float mnx = Float.MAX_VALUE, mny = Float.MAX_VALUE;
        float mxx = -Float.MAX_VALUE, mxy = -Float.MAX_VALUE;
        for (AnimFormat.AFrame f : anim.frames) {
            mnx = Math.min(mnx, f.x);
            mny = Math.min(mny, f.y);
            mxx = Math.max(mxx, f.x + f.w);
            mxy = Math.max(mxy, f.y + f.h);
        }
        if (mnx > mxx) {
            mnx = 0;
            mny = 0;
            mxx = 100;
            mxy = 100;
        }
        anCx = (mnx + mxx) / 2f;
        anCy = (mny + mxy) / 2f;
        spanW = Math.max(1f, mxx - mnx);
        spanH = Math.max(1f, mxy - mny);
    }

    public void setFrame(int f) {
        if (anim == null || anim.frames.length == 0) return;
        frame = Math.max(0, Math.min(f, anim.frames.length - 1));
        invalidate();
    }

    public int frame() {
        return frame;
    }

    public AnimFormat.AAnim anim() {
        return anim;
    }

    public void cycleDst() {
        dstMode = (dstMode + 1) % 4;
        invalidate();
    }

    public int dstMode() {
        return dstMode;
    }

    public void toggleUv() {
        renderer.flipUv = !renderer.flipUv;
        invalidate();
    }

    public void toggleZOrder() {
        renderer.zDescending = !renderer.zDescending;
        invalidate();
    }

    public void resetView() {
        zoom = 1f;
        panX = 0f;
        panY = 0f;
        invalidate();
    }

    protected float fit() {
        if (getWidth() == 0 || getHeight() == 0) return 1f;
        return Math.min(getWidth() / spanW, getHeight() / spanH) * fitFactor;
    }

    protected float scale() {
        return fit() * zoom;
    }

    protected float screenX(float ax) {
        float e = (ax - anCx) * scale();
        return getWidth() / 2f + panX + ((dstMode == 1 || dstMode == 3) ? -e : e);
    }

    protected float screenY(float ay) {
        float e = (ay - anCy) * scale();
        return getHeight() / 2f + panY + ((dstMode == 1 || dstMode == 2) ? e : -e);
    }

    protected AnimRenderer.Xform xform() {
        return new AnimRenderer.Xform() {
            public float x(float ax) { return screenX(ax); }

            public float y(float ay) { return screenY(ay); }
        };
    }

    @Override
    protected void onDraw(Canvas cv) {
        cv.drawColor(bg.getColor());
        if (atlas == null || anim == null || anim.frames.length == 0) return;
        renderer.draw(cv, atlas, build, anim, frame, xform(), debug);
        if (debug) drawDebug(cv);
    }

    private void drawDebug(Canvas cv) {
        float y = 32f;
        cv.drawText("atlas=" + atlas.getWidth() + "x" + atlas.getHeight(), 12, y, dbg);
        y += 30;
        cv.drawText("anim=" + anim.name + " frames=" + anim.frames.length
                + " tris=" + renderer.tris, 12, y, dbg);
        y += 30;
        cv.drawText("elems " + renderer.elemsMatched + "/" + renderer.elemsTotal
                + " unmatched=" + renderer.elemsUnmatched, 12, y, dbg);
    }

    /** 供界面显示的信息串 */
    public String infoText() {
        if (anim == null) return "";
        return anim.name + "  " + (frame + 1) + "/" + anim.frames.length
                + "  " + anim.frameRate + "fps";
    }
}
