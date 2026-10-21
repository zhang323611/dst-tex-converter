package com.dsttex;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.dsttex.core.Settings;
import com.dsttex.databinding.ActivitySettingsBinding;

/** 转换设置：改动即时生效并持久化（不再有"确定后才生效但重启丢失"的老问题） */
public class SettingsActivity extends AppCompatActivity {

    private ActivitySettingsBinding b;
    private Settings settings;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        b = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setSupportActionBar(b.toolbar);
        b.toolbar.setNavigationOnClickListener(v -> finish());

        settings = new Settings(this);

        // 转换方式
        switch (settings.mode()) {
            case PNG2TEX: b.rgMode.check(R.id.modePng2Tex); break;
            case TEX2PNG: b.rgMode.check(R.id.modeTex2Png); break;
            case DXT2ASTC: b.rgMode.check(R.id.modeDxt2Astc); break;
            default: b.rgMode.check(R.id.modeAuto); break;
        }
        b.rgMode.setOnCheckedChangeListener((g, id) -> {
            if (id == R.id.modePng2Tex) settings.setMode(Settings.Mode.PNG2TEX);
            else if (id == R.id.modeTex2Png) settings.setMode(Settings.Mode.TEX2PNG);
            else if (id == R.id.modeDxt2Astc) settings.setMode(Settings.Mode.DXT2ASTC);
            else settings.setMode(Settings.Mode.AUTO);
        });

        // 质量
        switch (settings.quality()) {
            case "fast": b.rgQuality.check(R.id.qFast); break;
            case "thorough": b.rgQuality.check(R.id.qThorough); break;
            case "exhaustive": b.rgQuality.check(R.id.qExhaustive); break;
            default: b.rgQuality.check(R.id.qMedium); break;
        }
        b.rgQuality.setOnCheckedChangeListener((g, id) -> {
            if (id == R.id.qFast) settings.setQuality("fast");
            else if (id == R.id.qThorough) settings.setQuality("thorough");
            else if (id == R.id.qExhaustive) settings.setQuality("exhaustive");
            else settings.setQuality("medium");
        });

        b.cbBackup.setChecked(settings.backup());
        b.cbBackup.setOnCheckedChangeListener((v, on) -> settings.setBackup(on));

        b.cbNested.setChecked(settings.nestedZip());
        b.cbNested.setOnCheckedChangeListener((v, on) -> settings.setNestedZip(on));

        b.cbDebug.setChecked(settings.debug());
        b.cbDebug.setOnCheckedChangeListener((v, on) -> settings.setDebug(on));

        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        b.tAuto.setText(getString(R.string.threads_auto, cores));
        int t = settings.threads();
        if (t == 2) b.rgThreads.check(R.id.t2);
        else if (t == 4) b.rgThreads.check(R.id.t4);
        else if (t == 8) b.rgThreads.check(R.id.t8);
        else if (t < 0) b.rgThreads.check(R.id.tAuto);
        else b.rgThreads.check(R.id.t1);
        b.rgThreads.setOnCheckedChangeListener((g, id) -> {
            if (id == R.id.t2) settings.setThreads(2);
            else if (id == R.id.t4) settings.setThreads(4);
            else if (id == R.id.t8) settings.setThreads(8);
            else if (id == R.id.tAuto) settings.setThreads(-1);
            else settings.setThreads(1);
        });
    }
}
