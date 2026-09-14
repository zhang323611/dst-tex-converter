package com.dsttex;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.List;
import java.util.ArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** DST 动画编辑器: 独立横屏界面, 带坐标轴与拖动定位 */
public class AnimEditor extends Activity {

    private AnimEditorView view;
    private TextView infoView;
    private int animIdx = 0, frameIdx = 0;
    private boolean playing = false;
    private final Handler handler = new Handler();
    private Runnable tick;

    private int dp(int v) { return Math.round(getResources().getDisplayMetrics().density * v); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (TexConverter.sBuild == null || TexConverter.sAnim == null
                || TexConverter.sAnim.anims.length == 0) {
            Toast.makeText(this, "无动画数据", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
                             android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);   // 左栏|右栏
        root.setBackgroundColor(0xFF1B1B1B);

        // ---------- 左栏: 通高元素列表 (与工具栏/帧条并列, 不会被遮挡) ----------
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setBackgroundColor(0xFF141414);

        TextView sideTitle = new TextView(this);
        sideTitle.setText("元素");
        sideTitle.setTextColor(0xFFAAAAAA);
        sideTitle.setTextSize(12);
        sideTitle.setPadding(dp(8), dp(6), dp(8), dp(4));
        side.addView(sideTitle);

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setPadding(dp(3), 0, dp(3), dp(3));
        sv.addView(listBox, new LinearLayout.LayoutParams(-1, -2));
        side.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        root.addView(side, new LinearLayout.LayoutParams(dp(104), -1));

        // ---------- 右栏: 工具栏 / 面板 / 画布 / 帧条 ----------
        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);

        // ---------- 顶栏 ----------
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF2B6CB0);
        bar.setPadding(dp(6), dp(4), dp(6), dp(4));
        bar.addView(barBtn("‹ 返回", v -> finish()));
        bar.addView(barBtn("动画", v -> pickAnim()));
        bar.addView(barBtn("播放", v -> togglePlay()));
        bar.addView(barBtn("复位", v -> { view.resetView(); view.invalidate(); }));
        bar.addView(barBtn("导入", v -> pickImport()));
        bar.addView(barBtn("编辑 ▾", v -> toggleEditPanel()));
        bar.addView(barBtn("保存", v -> save()));
        infoView = new TextView(this);
        infoView.setTextColor(0xFFFFFFFF);
        infoView.setTextSize(12);
        infoView.setPadding(dp(10), 0, 0, 0);
        bar.addView(infoView, new LinearLayout.LayoutParams(0, -2, 1));
        main.addView(bar);

        // ---------- 可展开的多层面板 ----------
        // 单行工具栏: 点「编辑 ▾」在下方展开一层按钮; 带 ▾ 的按钮再展开下一层。
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setBackgroundColor(0xFF1E5AA8);
        panel.setPadding(dp(6), dp(2), dp(6), dp(2));
        panel.setVisibility(View.GONE);
        main.addView(panel);

        // ---------- 主体: 左侧元素列表 + 画布 ----------
        view = new AnimEditorView();
        main.addView(view, new LinearLayout.LayoutParams(-1, 0, 1));

        // ---------- 底部帧条 ----------
        LinearLayout bot = new LinearLayout(this);
        bot.setOrientation(LinearLayout.HORIZONTAL);
        bot.setGravity(Gravity.CENTER_VERTICAL);
        bot.setBackgroundColor(0xFF262626);
        bot.setPadding(dp(8), dp(2), dp(8), dp(2));
        Button prev = barBtn("◀", v -> step(-1));
        Button next = barBtn("▶", v -> step(1));
        bot.addView(prev);
        final SeekBar sb = new SeekBar(this);
        sb.setMax(Math.max(0, curAnim().frames.length - 1));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int val, boolean u) { setFrame(val); }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        bot.addView(sb, new LinearLayout.LayoutParams(0, -2, 1));
        bot.addView(next);
        main.addView(bot);

        root.addView(main, new LinearLayout.LayoutParams(0, -1, 1));

        // 首次填充元素列表(此前这一行在布局重构时被误删, 导致列表为空)
        rebuildElemList();

        setContentView(root);
        updateInfo();
    }

    // ---- 左侧元素列表 ----
    private LinearLayout listBox;

    /** 重建元素列表; 高亮当前选中项 */
    private void rebuildElemList() {
        if (listBox == null) return;
        listBox.removeAllViews();
        TexConverter.AFrame fr = curAnim().frames[Math.min(frameIdx, curAnim().frames.length - 1)];
        for (int i = 0; i < fr.elems.length; i++) {
            final TexConverter.AElement e = fr.elems[i];
            String nm = elemName(e.hash);
            Button b = barBtn(i + "  " + nm, v -> { view.sel = e; view.invalidate(); rebuildElemList(); });
            b.setTextSize(11);
            b.setPadding(dp(4), dp(2), dp(4), dp(2));
            b.setTextColor(e == view.sel ? 0xFFFFCC33 : 0xFFDDDDDD);
            listBox.addView(b, new LinearLayout.LayoutParams(-1, -2));
        }
        if (fr.elems.length == 0) {
            Button b = barBtn("(空)", v -> {});
            b.setTextSize(11);
            listBox.addView(b, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    /** 元素 hash -> 可读名字(build 的名字表; 找不到则显示 hash) */
    private String elemName(long hash) {
        TexConverter.BSymbol sym = TexConverter.sBuild.symbols.get(hash);
        if (sym != null && sym.name != null && sym.name.length() > 0) return sym.name;
        return String.format("0x%08X", hash);
    }

    // ---- 导入 zip ----
    private static final int REQ_IMPORT = 4001;

    private void pickImport() {
        try {
            android.content.Intent it = new android.content.Intent(
                    android.content.Intent.ACTION_OPEN_DOCUMENT);
            it.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            it.setType("*/*");
            startActivityForResult(it, REQ_IMPORT);
        } catch (Throwable t) {
            toast("无法打开文件选择器: " + t.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_IMPORT || res != RESULT_OK || data == null || data.getData() == null) return;
        final android.net.Uri uri = data.getData();
        toast("正在载入 ...");
        new Thread(() -> {
            try {
                java.io.File tmp = new java.io.File(getCacheDir(), "imported.zip");
                java.io.InputStream in = getContentResolver().openInputStream(uri);
                java.io.FileOutputStream out = new java.io.FileOutputStream(tmp);
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close(); out.close();
                final boolean ok = TexConverter.loadAnimAsset(getApplicationContext(), tmp);
                runOnUiThread(() -> {
                    if (!ok) { toast("导入失败: 不是有效的动画压缩包"); return; }
                    frameIdx = 0; animIdx = 0;
                    view.sel = null;
                    rebuildElemList();
                    updateInfo();
                    view.invalidate();
                    toast("已导入 " + tmp.getName());
                });
            } catch (Throwable t) {
                runOnUiThread(() -> toast("导入失败: " + t.getMessage()));
            }
        }).start();
    }

    // ---- 多层展开面板 ----
    private LinearLayout panel;
    private int panelLevel = -1;        // -1 = 收起
    private static final int LV_ROOT = 0, LV_FRAME = 1, LV_XFORM = 2, LV_Z = 3;

    private void toggleEditPanel() {
        if (panel.getVisibility() == View.VISIBLE) { closePanel(); }
        else { showLevel(LV_ROOT); }
    }

    private void closePanel() {
        panel.setVisibility(View.GONE);
        panelLevel = -1;
    }

    private void showLevel(int lv) {
        panelLevel = lv;
        panel.removeAllViews();
        switch (lv) {
            case LV_ROOT:
                panel.addView(barBtn("换帧 ▾", v -> showLevel(LV_FRAME)));
                panel.addView(barBtn("变换 ▾", v -> showLevel(LV_XFORM)));
                panel.addView(barBtn("层级 ▾", v -> showLevel(LV_Z)));
                panel.addView(barBtn("撤销", v -> view.undo()));
                panel.addView(barBtn("✕", v -> closePanel()));
                break;
            case LV_FRAME:
                panel.addView(barBtn("换帧◀", v -> view.changeBuildFrame(-1)));
                panel.addView(barBtn("换帧▶", v -> view.changeBuildFrame(1)));
                panel.addView(barBtn("‹ 上层", v -> showLevel(LV_ROOT)));
                break;
            case LV_XFORM:
                panel.addView(barBtn("缩小", v -> view.scaleSel(1f / 1.1f)));
                panel.addView(barBtn("放大", v -> view.scaleSel(1.1f)));
                panel.addView(barBtn("左转", v -> view.rotateSel(-15f)));
                panel.addView(barBtn("右转", v -> view.rotateSel(15f)));
                panel.addView(barBtn("‹ 上层", v -> showLevel(LV_ROOT)));
                break;
            case LV_Z:
                panel.addView(barBtn("层-", v -> view.zSel(-1f)));
                panel.addView(barBtn("层+", v -> view.zSel(1f)));
                panel.addView(barBtn("‹ 上层", v -> showLevel(LV_ROOT)));
                break;
        }
        panel.setVisibility(View.VISIBLE);
    }

    private Button barBtn(String t, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t);
        b.setTextSize(12);
        b.setBackgroundColor(0x00000000);
        b.setTextColor(0xFFFFFFFF);
        b.setPadding(dp(8), dp(4), dp(8), dp(4));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setOnClickListener(l);
        return b;
    }

    private TexConverter.AAnim curAnim() {
        return TexConverter.sAnim.anims[Math.min(animIdx, TexConverter.sAnim.anims.length - 1)];
    }

    private void pickAnim() {
        final TexConverter.AAnim[] arr = TexConverter.sAnim.anims;
        String[] names = new String[arr.length];
        for (int i = 0; i < arr.length; i++)
            names[i] = arr[i].name + "  (" + arr[i].frames.length + " 帧, " + arr[i].frameRate + "fps)";
        new android.app.AlertDialog.Builder(this)
            .setTitle("选择动画")
            .setItems(names, (d, w) -> { animIdx = w; frameIdx = 0; view.sel = null;
                rebuildElemList(); view.invalidate(); updateInfo(); })
            .setNegativeButton("取消", null).show();
    }

    private void setFrame(int f) {
        frameIdx = Math.max(0, Math.min(f, curAnim().frames.length - 1));
        rebuildElemList();
        view.invalidate();
        updateInfo();
    }

    private void step(int d) { setFrame(frameIdx + d); }

    private void togglePlay() {
        playing = !playing;
        if (playing) {
            final float fps = curAnim().frameRate <= 0 ? 30f : curAnim().frameRate;
            final int delay = (int) Math.max(16, 1000f / fps);
            tick = new Runnable() {
                public void run() {
                    if (!playing) return;
                    int n = curAnim().frames.length;
                    setFrame((frameIdx + 1) % n);
                    handler.postDelayed(tick, delay);
                }
            };
            handler.post(tick);
        } else {
            handler.removeCallbacks(tick);
        }
        updateInfo();
    }

    private void updateInfo() {
        TexConverter.AAnim a = curAnim();
        infoView.setText(a.name + "  " + (frameIdx + 1) + "/" + a.frames.length
            + "  " + a.frameRate + "fps  " + (playing ? "播放中" : "暂停")
            + "  缩放 " + String.format("%.2f", view.scale / view.baseFit));
    }

    @Override
    protected void onDestroy() {
        playing = false;
        handler.removeCallbacks(tick);
        super.onDestroy();
    }

    // ================= 画布 =================
    private class AnimEditorView extends View {
        float zoom = 1f, panX = 0, panY = 0;
        float baseFit = 1f, scale = 1f;
        float originX, originY;
        float ctrX = 0f, ctrY = 0f;      // 动画包围盒中心(用于 180 度旋转)
        TexConverter.AElement sel = null;
        float downX, downY, startTx, startTy;
        boolean panning = false;

        final Paint fill = new Paint();
        final Paint lineP = new Paint();
        final Paint axisX = new Paint();
        final Paint axisY = new Paint();
        final Paint gridP = new Paint();
        final Paint selP = new Paint();
        final Paint txtP = new Paint();
        final Path tri = new Path();
        final Matrix sm = new Matrix();
        final float[] src = new float[6];
        final float[] dst = new float[6];

        AnimEditorView() {
            super(AnimEditor.this);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);   // 避开 HW 加速对 clipPath 的限制
            lineP.setStyle(Paint.Style.STROKE);
            lineP.setStrokeWidth(1.5f);
            axisX.setColor(0xFFE05252); axisX.setStrokeWidth(2f);
            axisY.setColor(0xFF52C452); axisY.setStrokeWidth(2f);
            gridP.setColor(0xFF353535); gridP.setStrokeWidth(1f);
            selP.setColor(0xFFFFCC33); selP.setStyle(Paint.Style.STROKE);
            selP.setStrokeWidth(2f);
            txtP.setColor(0xFFAAAAAA); txtP.setTextSize(20f);
            setFocusable(true);
        }

        final AnimRenderer rnd = new AnimRenderer();   // 与预览界面共用同一份绘制实现

        void resetView() { zoom = 1f; panX = 0; panY = 0; sel = null; }

        // ---- 元素编辑 ----
        // 撤销栈: 每次修改前压入 {a,b,c,d,tx,ty,z,buildFrame}
        private final java.util.ArrayDeque<float[]> undoStack = new java.util.ArrayDeque<>();

        private void pushUndo(TexConverter.AElement e) {
            if (e == null) return;
            undoStack.push(new float[]{e.a, e.b, e.c, e.d, e.tx, e.ty, e.z, e.buildFrame});
            if (undoStack.size() > 80) undoStack.removeLast();
        }

        void undo() {
            TexConverter.AElement e = sel;
            if (e == null || undoStack.isEmpty()) { toast("无可撤销"); return; }
            float[] v = undoStack.pop();
            e.a = v[0]; e.b = v[1]; e.c = v[2]; e.d = v[3];
            e.tx = v[4]; e.ty = v[5]; e.z = v[6]; e.buildFrame = (int) v[7];
            invalidate();
        }

        private boolean needSel() {
            if (sel == null) { toast("请先点选一个元素"); return true; }
            return false;
        }

        /** 切换 build 帧(换造型) */
        void changeBuildFrame(int delta) {
            if (needSel()) return;
            TexConverter.BSymbol sym = TexConverter.sBuild.symbols.get(sel.hash);
            if (sym == null || sym.frames.length <= 1) { toast("该元素只有一帧"); return; }
            pushUndo(sel);
            int n = sym.frames.length;
            sel.buildFrame = ((sel.buildFrame + delta) % n + n) % n;
            invalidate();
            toast("build 帧 " + (sel.buildFrame + 1) + "/" + n);
        }

        /** 缩放元素(围绕自身原点) */
        void scaleSel(float f) {
            if (needSel()) return;
            pushUndo(sel);
            sel.a *= f; sel.b *= f; sel.c *= f; sel.d *= f;
            invalidate();
        }

        /** 旋转元素(围绕自身原点)。M' = R * M, M=[[a,c],[b,d]] */
        void rotateSel(float deg) {
            if (needSel()) return;
            pushUndo(sel);
            double r = Math.toRadians(deg), cs = Math.cos(r), sn = Math.sin(r);
            float a = sel.a, b = sel.b, c = sel.c, d = sel.d;
            // M = [[a,b],[c,d]] (ktools 约定), M' = R*M
            sel.a = (float) (cs * a - sn * c);
            sel.b = (float) (cs * b - sn * d);
            sel.c = (float) (sn * a + cs * c);
            sel.d = (float) (sn * b + cs * d);
            invalidate();
        }

        /** 调整层级(z 越大越靠前) */
        void zSel(float dz) {
            if (needSel()) return;
            pushUndo(sel);
            sel.z += dz;
            invalidate();
        }

        private void recompute() {
            TexConverter.AAnim a = curAnim();
            float mnx = Float.MAX_VALUE, mny = Float.MAX_VALUE;
            float mxx = -Float.MAX_VALUE, mxy = -Float.MAX_VALUE;
            for (TexConverter.AFrame f : a.frames) {
                mnx = Math.min(mnx, f.x); mny = Math.min(mny, f.y);
                mxx = Math.max(mxx, f.x + f.w); mxy = Math.max(mxy, f.y + f.h);
            }
            if (mnx > mxx) { mnx = 0; mny = 0; mxx = 100; mxy = 100; }
            ctrX = (mnx + mxx) / 2f;
            ctrY = (mny + mxy) / 2f;
            float sw = Math.max(1f, mxx - mnx), sh = Math.max(1f, mxy - mny);
            baseFit = Math.min(getWidth() / sw, getHeight() / sh) * 0.7f;
            scale = baseFit * zoom;
            originX = getWidth() / 2f + panX - (mnx + mxx) / 2f * scale;
            originY = getHeight() / 2f + panY + (mny + mxy) / 2f * scale;
        }

        // 动画坐标 -> 屏幕坐标。统一出口: 所有绘制/选中框/命中检测都必须走这里,
        // 否则朝向变换只在一处生效, 会出现"框不跟着转"的错位。
        // 与预览"朝向=2(上下翻转)"完全一致:
        //   screenX = W/2 + panX + (ax - ctrX) * scale
        //   screenY = H/2 + panY + (ay - ctrY) * scale   (y 不取负, 即补偿 KTEX 垂直翻转存储)
        private float sxf(float ax) { return getWidth() / 2f + panX + (ax - ctrX) * scale; }
        private float syf(float ay) { return getHeight() / 2f + panY + (ay - ctrY) * scale; }

        /** 渲染器用的坐标映射, 与 sxf/syf 完全一致 */
        private AnimRenderer.Xform xform() {
            return new AnimRenderer.Xform() {
                public float x(float ax) { return sxf(ax); }
                public float y(float ay) { return syf(ay); }
            };
        }

        @Override
        protected void onDraw(Canvas cv) {
            cv.drawColor(0xFF1B1B1B);
            if (TexConverter.sAtlas == null) return;
            recompute();

            // ---- 坐标轴与网格 ----
            float step = 50f;
            while (step * scale < 40f) step *= 2f;
            int maxN = 40;
            for (int i = -maxN; i <= maxN; i++) {
                float gx = sxf(i * step);
                float gy = syf(i * step);
                if (gx >= 0 && gx <= getWidth()) cv.drawLine(gx, 0, gx, getHeight(), gridP);
                if (gy >= 0 && gy <= getHeight()) cv.drawLine(0, gy, getWidth(), gy, gridP);
            }
            cv.drawLine(0, originY, getWidth(), originY, axisX);   // X 轴
            cv.drawLine(originX, 0, originX, getHeight(), axisY);  // Y 轴
            cv.drawText("X+", getWidth() - 40, originY - 8, txtP);
            cv.drawText("Y+", originX + 8, 26, txtP);
            cv.drawText("0", originX + 6, originY + 22, txtP);

            // ---- 绘制三角形: 委托给 AnimRenderer, 与预览界面同一份代码 ----
            AnimRenderer.Xform xf = xform();
            rnd.draw(cv, TexConverter.sAtlas, TexConverter.sBuild, curAnim(), frameIdx, xf, false);

            // ---- 选中元素高亮 ----
            TexConverter.AFrame fr = curAnim().frames[Math.min(frameIdx, curAnim().frames.length - 1)];
            for (TexConverter.AElement e : fr.elems) {
                if (e == sel) {
                    float[] bb = AnimRenderer.bounds(TexConverter.sBuild, e, xf);
                    if (bb != null) cv.drawRect(bb[0], bb[1], bb[2], bb[3], selP);
                }
            }
        }

        /** 用真实三角形顶点计算元素的屏幕包围盒。
         *  注意: build 帧自带的 bbox 并不包围全部三角形(实测左边界差约 256 单位),
         *  所以选中框/命中检测必须基于三角形, 不能用 bf.x/bf.y/bf.w/bf.h。 */
        private float[] boundsOf(TexConverter.AElement e) {
            return AnimRenderer.bounds(TexConverter.sBuild, e, xform());
        }

        private TexConverter.AElement hitTest(float px, float py) {
            TexConverter.AFrame fr = curAnim().frames[Math.min(frameIdx, curAnim().frames.length - 1)];
            TexConverter.AElement best = null;
            float bestZ = -1e9f;
            for (TexConverter.AElement e : fr.elems) {
                TexConverter.BSymbol sym = TexConverter.sBuild.symbols.get(e.hash);
                if (sym == null || sym.frames.length == 0) continue;
                float[] bb = boundsOf(e);
                if (bb == null) continue;
                if (px >= bb[0] && px <= bb[2] && py >= bb[1] && py <= bb[3]) {
                    if (e.z > bestZ) { bestZ = e.z; best = e; }
                }
            }
            return best;
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    downX = ev.getX(); downY = ev.getY();
                    TexConverter.AElement hit = hitTest(downX, downY);
                    if (hit != null) {
                        sel = hit;
                        startTx = hit.tx; startTy = hit.ty;
                        panning = false;
                    } else {
                        sel = null;
                        panning = true;
                    }
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    float dx = ev.getX() - downX, dy = ev.getY() - downY;
                    if (panning) {
                        panX += dx; panY += dy;
                        downX = ev.getX(); downY = ev.getY();
                        invalidate();
                    } else if (sel != null) {
                        float s = scale <= 0 ? 1f : scale;
                        // sxf/syf 两个方向导数均为 +scale
                        sel.tx = startTx + dx / s;
                        sel.ty = startTy + dy / s;
                        invalidate();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    // 双指缩放由按钮/拖动替代, 此处仅结束
                    return true;
                }
            }
            return super.onTouchEvent(ev);
        }
    }

    // ================= 保存 =================
    /** 把编辑后的元素变换写回 anim.bin(原地改写 tx/ty 字节), 并替换 zip 内条目 */
    private void save() {
        try {
            byte[] anim = TexConverter.sAnimBytes;
            if (anim == null) { toast("无 anim.bin 数据"); return; }
            int patched = 0;
            for (TexConverter.AAnim a : TexConverter.sAnim.anims) {
                for (TexConverter.AFrame f : a.frames) {
                    for (TexConverter.AElement e : f.elems) {
                        if (e.txOffset < 28 || e.txOffset + 12 > anim.length) continue;
                        // 元素记录 40 字节, txOffset 指向 tx; 除 hash 外全部可原地改写
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
            File zip = TexConverter.sAnimZip;
            File tmp = new File(getCacheDir(), "edited.zip");
            ZipFile zf = new ZipFile(zip);
            ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tmp));
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
                    byte[] buf = new byte[65536];
                    int r;
                    while ((r = in.read(buf)) > 0) zos.write(buf, 0, r);
                    in.close();
                }
                zos.closeEntry();
            }
            zos.close();
            zf.close();
            File bak = new File(zip.getAbsolutePath() + ".bak");
            if (!bak.exists()) copyFile(zip, bak);
            copyFile(tmp, zip);
            tmp.delete();
            toast("已保存: 改写 " + patched + " 个元素, 原包备份 .bak");
        } catch (Exception e) {
            toast("保存失败: " + e.getMessage());
        }
    }

    private static void putI(byte[] b, int off, int v) {
        b[off] = (byte) v; b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16); b[off + 3] = (byte) (v >> 24);
    }

    private static void putF(byte[] b, int off, float v) {
        int i = Float.floatToIntBits(v);
        b[off] = (byte) (i & 0xFF);
        b[off + 1] = (byte) ((i >> 8) & 0xFF);
        b[off + 2] = (byte) ((i >> 16) & 0xFF);
        b[off + 3] = (byte) ((i >> 24) & 0xFF);
    }

    private static void copyFile(File src, File dst) throws Exception {
        InputStream in = new java.io.FileInputStream(src);
        OutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[65536];
        int r;
        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        in.close();
        out.close();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
