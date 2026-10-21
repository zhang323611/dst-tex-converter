package com.dsttex;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.dsttex.core.AnimFormat;
import com.dsttex.core.AnimLoader;
import com.dsttex.core.AnimWriter;
import com.dsttex.core.AppLog;
import com.dsttex.core.Settings;
import com.dsttex.core.TexCodec;
import com.dsttex.databinding.ActivityAnimEditorBinding;
import com.dsttex.databinding.ItemElementBinding;
import com.dsttex.ui.AnimEditorView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** 动画编辑器（横屏）：元素列表 + 多操作面板 + 画布拖拽/缩放 + 撤销 + 原地写回 */
public class AnimEditor extends AppCompatActivity implements AnimEditorView.Listener {

    private static final String EXTRA_PATH = "zip";
    private static final int REQ_IMPORT = 4001;

    public static void start(Context ctx, File zip) {
        Intent i = new Intent(ctx, AnimEditor.class);
        i.putExtra(EXTRA_PATH, zip.getAbsolutePath());
        ctx.startActivity(i);
    }

    private ActivityAnimEditorBinding b;
    private AnimLoader.Loaded data;
    private File src;
    private int animIdx = 0;
    private boolean updatingBar = false;
    private ElementAdapter elemAdapter;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        b = ActivityAnimEditorBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setSupportActionBar(b.toolbar);
        b.toolbar.setNavigationOnClickListener(v -> finish());

        String path = getIntent().getStringExtra(EXTRA_PATH);
        if (path == null) {
            finish();
            return;
        }
        src = new File(path);
        b.toolbar.setTitle(src.getName());
        b.canvas.setListener(this);
        b.canvas.debug = new Settings(this).debug();

        elemAdapter = new ElementAdapter();
        b.elementList.setLayoutManager(new LinearLayoutManager(this));
        b.elementList.setAdapter(elemAdapter);

        b.frameBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int v, boolean fromUser) {
                if (!updatingBar) {
                    b.canvas.setFrame(v);
                    rebuildElements();
                    updateSubtitle();
                }
            }

            public void onStartTrackingTouch(SeekBar s) { }

            public void onStopTrackingTouch(SeekBar s) { }
        });
        b.btnPrev.setOnClickListener(v -> stepFrame(-1));
        b.btnNext.setOnClickListener(v -> stepFrame(1));

        buildPanel();
        load(src);
    }

    // --------------------------------------------------------------- 载入

    private void load(final File zip) {
        toast("正在解析 " + zip.getName() + " …");
        new Thread(() -> {
            File tmp = new File(getCacheDir(), "editor");
            tmp.mkdirs();
            try {
                final AnimLoader.Loaded l = AnimLoader.load(zip, tmp);
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    data = l;
                    src = zip;
                    animIdx = 0;
                    b.canvas.setData(l.atlas, l.build, l.anim.anims[0]);
                    syncFrameBar();
                    rebuildElements();
                    updateSubtitle();
                    invalidateOptionsMenu();
                });
            } catch (Exception e) {
                AppLog.e("编辑器载入失败", e);
                runOnUiThread(() -> {
                    toast("载入失败: " + e.getMessage());
                    finish();
                });
            }
        }).start();
    }

    private AnimFormat.AAnim curAnim() {
        if (data == null || data.anim.anims.length == 0) return null;
        return data.anim.anims[Math.min(animIdx, data.anim.anims.length - 1)];
    }

    private void syncFrameBar() {
        AnimFormat.AAnim a = curAnim();
        if (a == null) return;
        updatingBar = true;
        b.frameBar.setMax(Math.max(0, a.frames.length - 1));
        b.frameBar.setProgress(Math.min(b.canvas.frame(), Math.max(0, a.frames.length - 1)));
        updatingBar = false;
    }

    private void stepFrame(int d) {
        AnimFormat.AAnim a = curAnim();
        if (a == null) return;
        b.canvas.setFrame(b.canvas.frame() + d);
        syncFrameBar();
        rebuildElements();
        updateSubtitle();
    }

    private void updateSubtitle() {
        AnimFormat.AAnim a = curAnim();
        if (a == null) {
            b.toolbar.setSubtitle(null);
            return;
        }
        b.toolbar.setSubtitle(a.name + "  " + (b.canvas.frame() + 1) + "/" + a.frames.length);
    }

    // --------------------------------------------------------------- 元素列表

    private void rebuildElements() {
        AnimFormat.AAnim a = curAnim();
        if (a == null) {
            elemAdapter.submit(new ArrayList<>());
            return;
        }
        AnimFormat.AFrame fr = a.frames[Math.min(b.canvas.frame(), a.frames.length - 1)];
        List<AnimFormat.AElement> list = new ArrayList<>();
        for (AnimFormat.AElement e : fr.elems) list.add(e);
        elemAdapter.submit(list);
    }

    private String elemName(AnimFormat.AElement e) {
        AnimFormat.BSymbol s = data == null ? null : data.build.symbols.get(e.hash);
        String name = s == null ? null : s.name;
        return (name == null || name.isEmpty()) ? String.format("0x%08X", e.hash) : name;
    }

    private final class ElementAdapter extends RecyclerView.Adapter<ElementAdapter.VH> {

        private final List<AnimFormat.AElement> items = new ArrayList<>();

        void submit(List<AnimFormat.AElement> list) {
            items.clear();
            items.addAll(list);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(ItemElementBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            AnimFormat.AElement e = items.get(position);
            h.label.setText(position + "  " + elemName(e));
            AnimFormat.AElement sel = b.canvas.selection();
            h.label.setTextColor(sel == e ? 0xFFFFCC33 : 0xFFDDDDDD);
            h.label.setOnClickListener(v -> {
                b.canvas.select(e);
                notifyDataSetChanged();
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        final class VH extends RecyclerView.ViewHolder {
            final TextView label;

            VH(ItemElementBinding binding) {
                super(binding.getRoot());
                label = binding.label;
            }
        }
    }

    // ---------------------------------------------------------------- 面板

    private void buildPanel() {
        panelButton("换帧◀", () -> b.canvas.changeBuildFrame(-1));
        panelButton("换帧▶", () -> b.canvas.changeBuildFrame(1));
        panelButton("缩小", () -> b.canvas.scaleSel(1f / 1.1f));
        panelButton("放大", () -> b.canvas.scaleSel(1.1f));
        panelButton("左转", () -> b.canvas.rotateSel(-15f));
        panelButton("右转", () -> b.canvas.rotateSel(15f));
        panelButton("层-", () -> b.canvas.zSel(-1f));
        panelButton("层+", () -> b.canvas.zSel(1f));
        panelButton("撤销", () -> b.canvas.undo());
    }

    private void panelButton(String text, Runnable action) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTextColor(0xFFFFFFFF);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        int p = dp(10);
        t.setPadding(p, dp(8), p, dp(8));
        TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)) {
            t.setBackgroundResource(tv.resourceId);
        }
        t.setOnClickListener(v -> action.run());
        b.panelRow.addView(t);
    }

    // ---------------------------------------------------------------- 菜单

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_editor, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem play = menu.findItem(R.id.action_play);
        if (play != null) play.setTitle(b.canvas.playing() ? R.string.anim_pause : R.string.anim_play);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_play) {
            b.canvas.togglePlay();
            invalidateOptionsMenu();
            return true;
        } else if (id == R.id.action_anim_pick) {
            pickAnim();
            return true;
        } else if (id == R.id.action_reset) {
            b.canvas.resetView();
            return true;
        } else if (id == R.id.action_import) {
            pickImport();
            return true;
        } else if (id == R.id.action_save) {
            save();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void pickAnim() {
        if (data == null) return;
        final AnimFormat.AAnim[] arr = data.anim.anims;
        String[] names = new String[arr.length];
        for (int i = 0; i < arr.length; i++) {
            names[i] = arr[i].name + "  (" + arr[i].frames.length + " 帧, " + arr[i].frameRate + "fps)";
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.title_anim_preview)
                .setItems(names, (d, i) -> {
                    animIdx = i;
                    b.canvas.setData(data.atlas, data.build, arr[i]);
                    syncFrameBar();
                    rebuildElements();
                    updateSubtitle();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void pickImport() {
        try {
            Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            it.addCategory(Intent.CATEGORY_OPENABLE);
            it.setType("*/*");
            startActivityForResult(it, REQ_IMPORT);
        } catch (Exception e) {
            toast("无法打开文件选择器: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent intent) {
        super.onActivityResult(req, res, intent);
        if (req != REQ_IMPORT || res != RESULT_OK || intent == null || intent.getData() == null) return;
        final android.net.Uri uri = intent.getData();
        toast("正在载入 …");
        new Thread(() -> {
            try {
                File tmp = new File(getCacheDir(), "imported.zip");
                InputStream in = getContentResolver().openInputStream(uri);
                if (in == null) throw new Exception("无法读取所选文件");
                try {
                    java.io.FileOutputStream out = new java.io.FileOutputStream(tmp);
                    try {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    } finally {
                        out.close();
                    }
                } finally {
                    in.close();
                }
                final File real = resolveRealFile(uri);
                runOnUiThread(() -> {
                    // 拿不到真实路径时，保存会写回这个缓存副本；这里明确告知用户
                    if (real != null) toast("已导入: " + real.getName());
                    else toast("已导入（无法定位原文件，保存将写回缓存副本）");
                    load(real != null ? real : tmp);
                });
            } catch (Exception e) {
                AppLog.e("导入失败", e);
                runOnUiThread(() -> toast("导入失败: " + e.getMessage()));
            }
        }).start();
    }

    private File resolveRealFile(android.net.Uri uri) {
        try {
            String p = uri.getPath();
            if (p == null) return null;
            int i = p.indexOf("/storage/");
            if (i < 0) i = p.indexOf("/sdcard/");
            if (i < 0) return null;
            File f = new File(p.substring(i));
            return f.isFile() ? f : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 保存

    private void save() {
        if (data == null || data.animBytes == null) {
            toast("无 anim.bin 数据");
            return;
        }
        final File target = src;
        if (target == null || !target.isFile()) {
            toast("找不到原压缩包，无法写回");
            return;
        }
        toast("正在写回 …");
        new Thread(() -> {
            try {
                final int patched = AnimWriter.patch(data.animBytes, data.anim);
                File tmp = new File(target.getParentFile(), target.getName() + ".tmpSave");
                AnimWriter.repack(target, data.animBytes, tmp);
                TexCodec.backupOnce(target);
                if (!tmp.renameTo(target)) {
                    TexCodec.copyFile(tmp, target);
                    tmp.delete();
                }
                runOnUiThread(() -> toast("已保存: 改写 " + patched + " 个元素（原包备份 .bak）"));
            } catch (Exception e) {
                AppLog.e("保存失败", e);
                runOnUiThread(() -> toast("保存失败: " + e.getMessage()));
            }
        }).start();
    }

    // ------------------------------------------------------- AnimEditorView 回调

    @Override
    public void onSelectionChanged() {
        elemAdapter.notifyDataSetChanged();
        updateSubtitle();
    }

    @Override
    public void onEdited() {
        updateSubtitle();
    }

    @Override
    public void onTip(String msg) {
        toast(msg);
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
