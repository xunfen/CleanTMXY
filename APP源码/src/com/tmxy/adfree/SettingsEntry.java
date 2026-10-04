package com.tmxy.adfree;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * 首页右下角的设置入口（一个小圆钮）。
 *
 * <p>为什么是"注入一个按钮"而不是"隐藏手势"：去广告之后首页本来就空出来了，
 * 放一个小钮最直观，用户想调设置一眼就能找到。
 *
 * <p>为什么不用 Activity 承载设置界面：见 {@link SettingsPanel} —— 内嵌场景下
 * 天猫校园的清单改不了，只能弹窗。
 */
public final class SettingsEntry {

    /** 用 tag 做去重标记，避免每次 resume 都重复添加。 */
    private static final String TAG = "tmxy_settings_entry";

    private SettingsEntry() {}

    /** 在宿主 Activity 上挂入口。同一个 Activity 重复调用只会加一个。 */
    public static void attach(Activity host) {
        if (host == null) {
            return;
        }
        try {
            ViewGroup content = host.findViewById(android.R.id.content);
            if (content == null || content.findViewWithTag(TAG) != null) {
                return;
            }
            int size = dp(host, 40);

            TextView btn = new TextView(host);
            btn.setTag(TAG);
            btn.setText("净");
            btn.setTextSize(15f);
            btn.setTextColor(Color.WHITE);
            btn.setGravity(Gravity.CENTER);

            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(Color.parseColor("#CC1565C0"));   // 半透明蓝
            btn.setBackground(bg);
            btn.setAlpha(0.85f);
            btn.setElevation(dp(host, 6));

            FrameLayout.LayoutParams lp =
                    new FrameLayout.LayoutParams(size, size);
            lp.gravity = Gravity.BOTTOM | Gravity.END;
            lp.rightMargin = dp(host, 14);
            lp.bottomMargin = dp(host, 92);   // 抬到底部导航栏上方
            btn.setLayoutParams(lp);

            btn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    open(host);
                }
            });

            content.addView(btn);
            Logx.ok("SettingsEntry: 首页设置入口已挂上");
        } catch (Throwable t) {
            Logx.miss("SettingsEntry.attach 跳过: " + t);
        }
    }

    /**
     * 按当前模式打开设置 —— 两种模式的"正确写入位置"是相反的，必须分流：
     *
     * <ul>
     *   <li><b>内嵌（免 root）</b>：模块跑在宿主进程、读宿主 prefs，所以设置必须也跑在
     *       宿主进程里写宿主 prefs → 弹 Dialog。</li>
     *   <li><b>LSPosed（有 root）</b>：模块跑在宿主进程、但读的是【模块 App 的】prefs
     *       （LSPosed 的 xposedsharedprefs 开了口子），所以设置必须跑在<b>模块 App 的
     *       进程</b>里 → 启动模块自己的 SettingsActivity。
     *       在这里弹 Dialog 会写到宿主的 prefs，模块根本读不到（等于白改）。</li>
     * </ul>
     */
    private static void open(Activity host) {
        if (Config.isHostMode()) {
            SettingsPanel.showDialog(host);
            return;
        }
        try {
            android.content.Intent i = new android.content.Intent();
            i.setClassName(HookEntry.MODULE_PKG, HookEntry.MODULE_PKG + ".SettingsActivity");
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            host.startActivity(i);
        } catch (Throwable t) {
            // 启动不了（比如被包可见性挡了）就退回弹窗 —— 至少能看模块状态
            Logx.w("SettingsEntry: 启动模块设置界面失败，退回弹窗: " + t);
            SettingsPanel.showDialog(host);
        }
    }

    private static int dp(Activity a, int v) {
        return Math.round(a.getResources().getDisplayMetrics().density * v);
    }
}