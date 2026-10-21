package com.dsttex;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.dsttex.core.AppLog;
import com.dsttex.core.TexCodec;
import com.dsttex.databinding.ActivityPreviewBinding;

import java.io.File;

/** 纹理预览：独立页面 + 双指缩放（原来是塞进 AlertDialog 的 ImageView） */
public class PreviewActivity extends AppCompatActivity {

    private static final String EXTRA_PATH = "path";

    public static void start(Context ctx, File tex) {
        Intent i = new Intent(ctx, PreviewActivity.class);
        i.putExtra(EXTRA_PATH, tex.getAbsolutePath());
        ctx.startActivity(i);
    }

    private ActivityPreviewBinding b;
    private File file;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        b = ActivityPreviewBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setSupportActionBar(b.toolbar);
        b.toolbar.setNavigationOnClickListener(v -> finish());

        String path = getIntent().getStringExtra(EXTRA_PATH);
        if (path == null) {
            finish();
            return;
        }
        file = new File(path);
        b.toolbar.setTitle(file.getName());
        b.info.setText(R.string.loading);

        new Thread(() -> {
            File tmp = new File(getCacheDir(), "prev");
            tmp.mkdirs();
            try {
                final int comp = TexCodec.readTexCompression(file);
                final Bitmap bmp;
                if (comp == 24) {
                    bmp = TexCodec.texToBitmap(file, tmp);
                } else {
                    File png = new File(tmp, "raw.png");
                    TexCodec.texToRawPng(file, png);
                    Bitmap raw = BitmapFactory.decodeFile(png.getAbsolutePath());
                    png.delete();
                    if (raw == null) throw new Exception("PNG 解码失败");
                    // KTEX 为垂直翻转存储，预览需翻正
                    bmp = TexCodec.flipVertical(raw);
                    raw.recycle();
                }
                final String info = TexCodec.compLabel(comp) + "   "
                        + bmp.getWidth() + " x " + bmp.getHeight() + "   "
                        + TexCodec.humanSize(file.length());
                runOnUiThread(() -> {
                    if (isFinishing()) {
                        bmp.recycle();
                        return;
                    }
                    b.image.setImageBitmap(bmp);
                    b.info.setText(info);
                });
            } catch (Exception e) {
                AppLog.e("预览 " + file.getName(), e);
                runOnUiThread(() -> {
                    b.info.setText("预览失败: " + e.getMessage());
                    Toast.makeText(this, "预览失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }
}
