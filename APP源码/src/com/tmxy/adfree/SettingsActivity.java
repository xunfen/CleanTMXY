package com.tmxy.adfree;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;

/**
 * 模块的桌面入口（有 root / LSPosed 场景用）。
 *
 * <p>界面代码全部在 {@link SettingsPanel} 里，这里只是把它塞进 Activity。
 * 免 root 的内嵌场景打不开这个 Activity（天猫校园的清单改不了），
 * 那边走 {@link SettingsEntry} 的首页小钮 → {@link SettingsPanel#showDialog}。
 */
public final class SettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // targetSdk 35 在 Android 15 上会被强制 edge-to-edge（内容画到状态栏底下）。
        // 关掉它，让系统恢复"内容排在状态栏/导航栏之内"的常规行为。
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(true);
        }
        setContentView(SettingsPanel.build(this));
    }
}