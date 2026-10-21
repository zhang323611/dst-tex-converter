package com.dsttex.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import com.dsttex.core.AnimFormat;
import com.dsttex.core.AnimRenderer;

import java.util.ArrayDeque;

/**
 * 动画编辑器画布：网格/坐标轴 + 元素点选 + 拖动 + 双指缩放 + 撤销。
 *
 * 关键点：所有绘制/选中框/命中检测都走同一套 screenX/screenY（继承自 AnimRendererView），
 * 否则朝向变换只在一处生效，会出现"框不跟着转"的错位。
 */
public class AnimEditorView extends AnimRendererView {

    /** 交给宿主界面刷新列表/标题等 */
    public interface Listener {
        void onSelectionChanged();

        void onEdited();

        void onTip(String msg);
    }

    /** 撤销条目：必须带元素引用 —— 只存数值的话，切到别的元素后撤销会写到错误对象上 */
    private static final class Undo {
        final AnimFormat.AElement el;
        final float a, b, c, d, tx, ty, z;
        final int bf;

        Undo(AnimFormat.AElement e) {
            el = e;
            a = e.a;
            b = e.b;
            c = e.c;
            d = e.d;
            tx = e.tx;
            ty = e.ty;
            z = e.z;
            bf = e.buildFrame;
        }

        void apply() {
            el.a = a;
            el.b = b;
            el.c = c;
            el.d = d;
            el.tx = tx;
            el.ty = ty;
            el.z = z;
            el.buildFrame = bf;
        }
    }

    private final ArrayDeque<Undo> undoStack = new ArrayDeque<>();
    private Listener listener;

    private AnimFormat.AElement sel = null;
    private float downX, downY, startTx, startTy;
    private boolean panning = false;
    private boolean dragUndoPushed = false;

    private float originX, originY;
    private final Paint grid = new Paint();
    private final Paint axisX = new Paint();
    private final Paint axisY = new Paint();
    private final Paint selPaint = new Paint();
    private final Paint caption = new Paint();

    private final ScaleGestureDetector scaleDetector;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean playing = false;
    private Runnable tick;

    public AnimEditorView(Context c) {
        this(c, null);
    }

    public AnimEditorView(Context c, AttributeSet a) {
        super(c, a);
        fitFactor = 0.7f;
        grid.setColor(0xFF353535);
        grid.setStrokeWidth(1f);
        axisX.setColor(0xFFE05252);
        axisX.setStrokeWidth(2f);
        axisY.setColor(0xFF52C452);
        axisY.setStrokeWidth(2f);
        selPaint.setColor(0xFFFFCC33);
        selPaint.setStyle(Paint.Style.STROKE);
        selPaint.setStrokeWidth(3f);
        caption.setColor(0xFFAAAAAA);
        caption.setTextSize(30f);
        setFocusable(true);

        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                float f = d.getScaleFactor();
                float target = zoom * f;
                if (target < 0.2f) f = 0.2f / zoom;
                else if (target > 12f) f = 12f / zoom;
                zoom *= f;
                invalidate();
                return true;
            }
        });
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public AnimFormat.AElement selection() {
        return sel;
    }

    public void select(AnimFormat.AElement e) {
        sel = e;
        invalidate();
    }

    // ------------------------------------------------------------- 编辑操作

    private void pushUndo(AnimFormat.AElement e) {
        if (e == null) return;
        undoStack.push(new Undo(e));
        if (undoStack.size() > 80) undoStack.removeLast();
    }

    public void undo() {
        if (undoStack.isEmpty()) {
            tip("无可撤销");
            return;
        }
        Undo u = undoStack.pop();
        u.apply();
        sel = u.el;
        invalidate();
        if (listener != null) {
            listener.onEdited();
            listener.onSelectionChanged();
        }
    }

    private boolean needSel() {
        if (sel == null) {
            tip("请先点选一个元素");
            return true;
        }
        return false;
    }

    public void changeBuildFrame(int delta) {
        if (needSel()) return;
        AnimFormat.BSymbol sym = build == null ? null : build.symbols.get(sel.hash);
        if (sym == null || sym.frames.length <= 1) {
            tip("该元素只有一帧");
            return;
        }
        pushUndo(sel);
        int n = sym.frames.length;
        sel.buildFrame = ((sel.buildFrame + delta) % n + n) % n;
        invalidate();
        tip("build 帧 " + (sel.buildFrame + 1) + "/" + n);
        if (listener != null) listener.onEdited();
    }

    public void scaleSel(float f) {
        if (needSel()) return;
        pushUndo(sel);
        sel.a *= f;
        sel.b *= f;
        sel.c *= f;
        sel.d *= f;
        invalidate();
        if (listener != null) listener.onEdited();
    }

    /** 旋转元素（围绕自身原点）。M' = R * M, M = [[a,b],[c,d]] */
    public void rotateSel(float deg) {
        if (needSel()) return;
        pushUndo(sel);
        double r = Math.toRadians(deg), cs = Math.cos(r), sn = Math.sin(r);
        float a = sel.a, b = sel.b, c = sel.c, d = sel.d;
        sel.a = (float) (cs * a - sn * c);
        sel.b = (float) (cs * b - sn * d);
        sel.c = (float) (sn * a + cs * c);
        sel.d = (float) (sn * b + cs * d);
        invalidate();
        if (listener != null) listener.onEdited();
    }

    public void zSel(float dz) {
        if (needSel()) return;
        pushUndo(sel);
        sel.z += dz;
        invalidate();
        if (listener != null) listener.onEdited();
    }

    // ----------------------------------------------------------------- 播放

    public void togglePlay() {
        playing = !playing;
        if (playing) {
            final AnimFormat.AAnim a = anim;
            final float fps = a == null || a.frameRate <= 0 ? 30f : a.frameRate;
            final int delay = (int) Math.max(16, 1000f / fps);
            tick = () -> {
                if (!playing || anim == null) return;
                int n = anim.frames.length;
                setFrame((frame + 1) % Math.max(1, n));
                if (listener != null) listener.onSelectionChanged();
                handler.postDelayed(tick, delay);
            };
            handler.post(tick);
        } else if (tick != null) {
            handler.removeCallbacks(tick);
        }
        invalidate();
    }

    public boolean playing() {
        return playing;
    }

    @Override
    protected void onDetachedFromWindow() {
        playing = false;
        if (tick != null) handler.removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    // ----------------------------------------------------------------- 触摸

    public AnimFormat.AElement hitTest(float px, float py) {
        if (anim == null || build == null) return null;
        AnimFormat.AFrame fr = anim.frames[Math.min(Math.max(frame, 0), anim.frames.length - 1)];
        AnimRenderer.Xform xf = xform();
        AnimFormat.AElement best = null;
        float bestZ = -1e9f;
        for (AnimFormat.AElement e : fr.elems) {
            float[] bb = AnimRenderer.bounds(build, e, xf);
            if (bb == null) continue;
            if (px >= bb[0] && px <= bb[2] && py >= bb[1] && py <= bb[3] && e.z > bestZ) {
                bestZ = e.z;
                best = e;
            }
        }
        return best;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        scaleDetector.onTouchEvent(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downX = ev.getX();
                downY = ev.getY();
                dragUndoPushed = false;
                AnimFormat.AElement hit = hitTest(downX, downY);
                if (hit != null) {
                    sel = hit;
                    startTx = hit.tx;
                    startTy = hit.ty;
                    panning = false;
                } else {
                    sel = null;
                    panning = true;
                }
                invalidate();
                if (listener != null) listener.onSelectionChanged();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                float dx = ev.getX() - downX, dy = ev.getY() - downY;
                if (panning) {
                    panX += dx;
                    panY += dy;
                    downX = ev.getX();
                    downY = ev.getY();
                    invalidate();
                } else if (sel != null && !scaleDetector.isInProgress()) {
                    // 拖动也要能撤销：第一次真正改动前压栈（此时 tx/ty 仍是原值）
                    if (!dragUndoPushed) {
                        pushUndo(sel);
                        dragUndoPushed = true;
                    }
                    float s = scale() <= 0 ? 1f : scale();
                    float signX = (dstMode == 1 || dstMode == 3) ? -1f : 1f;
                    float signY = (dstMode == 1 || dstMode == 2) ? 1f : -1f;
                    sel.tx = startTx + dx / (s * signX);
                    sel.ty = startTy + dy / (s * signY);
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (sel != null && dragUndoPushed && listener != null) listener.onEdited();
                return true;
            default:
                return super.onTouchEvent(ev);
        }
    }

    // ----------------------------------------------------------------- 绘制

    @Override
    protected void onDraw(Canvas cv) {
        cv.drawColor(0xFF1B1B1B);
        if (atlas == null || anim == null || anim.frames.length == 0) return;

        // 网格与坐标轴
        float step = 50f;
        while (step * scale() < 40f) step *= 2f;
        int maxN = 40;
        for (int i = -maxN; i <= maxN; i++) {
            float gx = screenX(anCx + i * step);
            float gy = screenY(anCy + i * step);
            if (gx >= 0 && gx <= getWidth()) cv.drawLine(gx, 0, gx, getHeight(), grid);
            if (gy >= 0 && gy <= getHeight()) cv.drawLine(0, gy, getWidth(), gy, grid);
        }
        originX = screenX(0f);
        originY = screenY(0f);
        cv.drawLine(0, originY, getWidth(), originY, axisX);
        cv.drawLine(originX, 0, originX, getHeight(), axisY);
        cv.drawText("0", originX + 6, originY + 26, caption);

        renderer.draw(cv, atlas, build, anim, frame, xform(), false);

        if (sel != null) {
            float[] bb = AnimRenderer.bounds(build, sel, xform());
            if (bb != null) cv.drawRect(bb[0], bb[1], bb[2], bb[3], selPaint);
        }
        if (debug) {
            cv.drawText("zoom=" + String.format("%.2f", zoom)
                    + " matched=" + renderer.elemsMatched + "/" + renderer.elemsTotal,
                    12, 32, caption);
        }
    }

    private void tip(String msg) {
        if (listener != null) listener.onTip(msg);
    }
}
