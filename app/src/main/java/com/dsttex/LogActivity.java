package com.dsttex;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.dsttex.core.AppLog;
import com.dsttex.databinding.ActivityLogBinding;

import java.io.File;

/** 运行日志：独立页面（不再塞进 AlertDialog），支持复制 / 分享 / 清空 */
public class LogActivity extends AppCompatActivity {

    private ActivityLogBinding b;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        b = ActivityLogBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setSupportActionBar(b.toolbar);
        b.toolbar.setNavigationOnClickListener(v -> finish());
        reload();
    }

    private void reload() {
        String txt = AppLog.text();
        b.logText.setText(txt == null || txt.isEmpty() ? getString(R.string.log_empty) : txt);
        b.logScroll.post(() -> b.logScroll.fullScroll(android.view.View.FOCUS_DOWN));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_log, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_copy) {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("log", b.logText.getText().toString()));
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
            return true;
        } else if (id == R.id.action_share) {
            try {
                File f = new File(getCacheDir(), "app.log");
                com.dsttex.core.TexCodec.writeFile(f, b.logText.getText().toString().getBytes("UTF-8"));
                Intent it = new Intent(Intent.ACTION_SEND);
                it.setType("text/plain");
                it.putExtra(Intent.EXTRA_TEXT, b.logText.getText().toString());
                startActivity(Intent.createChooser(it, "分享日志"));
            } catch (Exception e) {
                Toast.makeText(this, "分享失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
            return true;
        } else if (id == R.id.action_clear) {
            AppLog.clear();
            reload();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
