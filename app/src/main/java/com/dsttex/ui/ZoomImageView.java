package com.dsttex.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import androidx.appcompat.widget.AppCompatImageView;

/**
 * 可缩放/拖动的图片视图（纹理预览用）。
 * 双击复位、双指缩放、单指拖动；缩放范围 [1x, 8x]（相对适配后的基准比例）。
 */
public class ZoomImageView extends AppCompatImageView {

    private final Matrix matrix = new Matrix();
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;

    private float baseScale = 1f;
    private float scale = 1f;
    private float lastX, lastY;
    private float sumX, sumY;

    public ZoomImageView(Context c) {
        this(c, null);
    }

    public ZoomImageView(Context c, AttributeSet a) {
        super(c, a);
        setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                float factor = d.getScaleFactor();
                float target = scale * factor;
                if (target < 1f) factor = 1f / scale;
                else if (target > 8f) factor = 8f / scale;
                scale *= factor;
                matrix.postScale(factor, factor, d.getFocusX(), d.getFocusY());
                setImageMatrix(matrix);
                return true;
            }
        });
        gestureDetector = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                reset();
                return true;
            }
        });
    }

    @Override
    public void setImageBitmap(Bitmap bm) {
        super.setImageBitmap(bm);
        post(this::reset);
    }

    /** 适配居中（等比缩放到控件内） */
    public void reset() {
        if (getDrawable() == null || getWidth() == 0 || getHeight() == 0) return;
        float iw = getDrawable().getIntrinsicWidth();
        float ih = getDrawable().getIntrinsicHeight();
        if (iw <= 0 || ih <= 0) return;
        baseScale = Math.min(getWidth() / iw, getHeight() / ih);
        scale = 1f;
        matrix.reset();
        matrix.postScale(baseScale, baseScale);
        sumX = (getWidth() - iw * baseScale) / 2f;
        sumY = (getHeight() - ih * baseScale) / 2f;
        matrix.postTranslate(sumX, sumY);
        setImageMatrix(matrix);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        scaleDetector.onTouchEvent(ev);
        gestureDetector.onTouchEvent(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = ev.getX();
                lastY = ev.getY();
                getParent().requestDisallowInterceptTouchEvent(true);
                break;
            case MotionEvent.ACTION_MOVE:
                if (!scaleDetector.isInProgress() && ev.getPointerCount() == 1) {
                    float dx = ev.getX() - lastX;
                    float dy = ev.getY() - lastY;
                    lastX = ev.getX();
                    lastY = ev.getY();
                    matrix.postTranslate(dx, dy);
                    setImageMatrix(matrix);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                break;
            default:
                break;
        }
        return true;
    }
}
