package com.dsttex;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.dsttex.core.AnimFormat;
import com.dsttex.core.AnimLoader;
import com.dsttex.core.AppLog;
import com.dsttex.databinding.ActivityAnimPreviewBinding;

import java.io.File;

/** 动画预览：独立页面（原先是 AlertDialog + 权重画布，画布高度会被量成 0） */
public class AnimPreviewActivity extends AppCompatActivity {

    private static final String EXTRA_PATH = "zip";

    public static void start(Context ctx, File zip) {
        Intent i = new Intent(ctx, AnimPreviewActivity.class);
        i.putExtra(EXTRA_PATH, zip.getAbsolutePath());
        ctx.startActivity(i);
    }

    private ActivityAnimPreviewBinding b;
    private File zip;
    private boolean updatingBar = false;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        b = ActivityAnimPreviewBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setSupportActionBar(b.toolbar);
        b.toolbar.setNavigationOnClickListener(v -> finish());

        String path = getIntent().getStringExtra(EXTRA_PATH);
        if (path == null) {
            finish();
            return;
        }
        zip = new File(path);
        b.toolbar.setTitle(zip.getName());
        b.info.setText(R.string.loading);
        b.canvas.debug = new com.dsttex.core.Settings(this).debug();

        b.frameBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int v, boolean fromUser) {
                if (!updatingBar) {
                    b.canvas.setFrame(v);
                    updateInfo();
                }
            }

            public void onStartTrackingTouch(SeekBar s) { }

            public void onStopTrackingTouch(SeekBar s) { }
        });

        b.btnUv.setOnClickListener(v -> {
            b.canvas.toggleUv();
            b.btnUv.setText(getString(R.string.anim_flip_uv) + ": "
                    + (b.canvas.renderer.flipUv ? "开" : "关"));
        });
        b.btnDir.setOnClickListener(v -> {
            b.canvas.cycleDst();
            b.btnDir.setText("朝向: " + b.canvas.dstMode());
        });
        b.btnZ.setOnClickListener(v -> {
            b.canvas.toggleZOrder();
            b.btnZ.setText(getString(R.string.anim_zorder) + ": "
                    + (b.canvas.renderer.zDescending ? "降" : "升"));
        });
        b.btnEdit.setOnClickListener(v -> AnimEditor.start(this, zip));

        b.btnDir.setText("朝向: " + b.canvas.dstMode());
        b.btnZ.setText(getString(R.string.anim_zorder) + ": 降");

        load();
    }

    private void load() {
        new Thread(() -> {
            File tmp = new File(getCacheDir(), "anim");
            tmp.mkdirs();
            try {
                final AnimLoader.Loaded l = AnimLoader.load(zip, tmp);
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    AnimFormat.AAnim a = l.anim.anims[0];
                    b.canvas.setData(l.atlas, l.build, a);
                    updatingBar = true;
                    b.frameBar.setMax(Math.max(0, a.frames.length - 1));
                    b.frameBar.setProgress(0);
                    updatingBar = false;
                    b.info.setText("build " + l.build.name + " | " + a.name
                            + " | 帧 " + a.frames.length + " | " + a.frameRate + " fps"
                            + " | 图集 " + (l.atlas == null ? "无" : l.atlas.getWidth() + "x" + l.atlas.getHeight()));
                });
            } catch (Exception e) {
                AppLog.e("动画预览 " + zip.getName(), e);
                runOnUiThread(() -> {
                    b.info.setText("解析失败: " + e.getMessage());
                    Toast.makeText(this, "解析失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void updateInfo() {
        String s = b.canvas.infoText();
        if (s != null && !s.isEmpty()) {
            String cur = b.info.getText().toString();
            int bar = cur.indexOf(" | ");
            b.info.setText(s);
        }
    }
}
