package com.tmxy.adfree;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** 模块设置界面（纯代码构建，不依赖任何资源文件）。 */
public final class SettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // targetSdk 35 在 Android 15 上会被强制 edge-to-edge（内容画到状态栏底下）。
        // 关掉它，让系统恢复"内容排在状态栏/导航栏之内"的常规行为。
        // API 30 以下本来就是 fits 行为，不用管。
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(true);
        }
        setContentView(build());
    }

    /**
     * 状态卡：显示模块版本、安装时间、天猫校园版本。
     *
     * <p>只显示能可靠拿到的事实。<b>曾经想显示「是否已生效」，做不了</b>：
     * 注入端跑在天猫校园进程里，而天猫校园 targetSdk 30、清单里没有 &lt;queries&gt;
     * 声明能看见 com.tmxy.adfree —— Android 11+ 的包可见性会把注入端对模块 App 的
     * 所有访问都挡掉。实测两条路都不通：
     * <ul>
     *   <li>显式广播 → 静默丢弃，系统日志零痕迹</li>
     *   <li>ContentProvider → IllegalArgumentException: Unknown authority</li>
     * </ul>
     * 前提是改天猫校园的清单，而那是阿里的 APK，LSPosed / LSPatch 都不会加 &lt;queries&gt;。
     * 所以生效与否让用户看日志：adb logcat -s TmxyAdFree:I
     */
    private View buildStatusCard() {
        String target = "未安装";
        try {
            android.content.pm.PackageInfo pi =
                    getPackageManager().getPackageInfo(HookEntry.TARGET_PKG, 0);
            target = pi.versionName + " (" + pi.versionCode + ")";
        } catch (Throwable ignored) {
        }
        // 模块自己的安装时间 —— 版本号相同的情况下，靠它区分是哪一次构建。
        // （之前 22:47 和 23:00 两次构建都叫 1.0.8，看版本号根本分不出来。）
        String myBuild = "—";
        try {
            android.content.pm.PackageInfo me =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            myBuild = android.text.format.DateFormat
                    .format("MM-dd HH:mm", me.lastUpdateTime).toString();
        } catch (Throwable ignored) {
        }

        StringBuilder sb = new StringBuilder();
        sb.append("模块版本    ").append(HookEntry.VERSION).append('\n');
        sb.append("安装于      ").append(myBuild).append('\n');
        sb.append("天猫校园    ").append(target).append('\n');;

        TextView tv = new TextView(this);
        tv.setTextSize(12.5f);
        tv.setTextColor(Color.parseColor("#1B4F8A"));
        tv.setBackgroundColor(Color.parseColor("#EAF1FA"));
        tv.setPadding(dp(12), dp(11), dp(12), dp(11));
        tv.setText("【模块状态】\n" + sb);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        tv.setLayoutParams(lp);
        return tv;
    }
    private View build() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.parseColor("#F5F6F8"));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("天猫校园净化");
        title.setTextSize(22f);
        title.setTextColor(Color.parseColor("#15181D"));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setTextSize(12f);
        sub.setTextColor(Color.parseColor("#5A6270"));
        sub.setPadding(0, dp(6), 0, dp(14));
        sub.setText("作用域：com.tmall.campus.and（天猫校园 5.7.2）\n"
                + "在 LSPosed 中启用本模块并勾选天猫校园后，强制停止 App 再打开即可生效。\n"
                + "改动开关后同样需要强制停止天猫校园。");
        root.addView(sub);

        root.addView(buildStatusCard());

        // ---------------- 版权与防倒卖声明 ----------------
        // 放在最上面：点开设置第一眼就看到。这个模块会被 LSPatch 打进 APK 分发，
        // 有人拿去收费卖的情况确实存在，所以声明必须显眼、必须能一眼看到。
        TextView notice = new TextView(this);
        notice.setTextSize(12.5f);
        notice.setTextColor(Color.parseColor("#8A2020"));
        notice.setBackgroundColor(Color.parseColor("#FDEBEB"));
        notice.setPadding(dp(12), dp(11), dp(12), dp(11));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nlp.bottomMargin = dp(14);
        notice.setLayoutParams(nlp);
        notice.setText("【免费开源 · 谨防被骗】\n"
                + "本软件完全免费、开源，唯一发布地址：\n"
                + HookEntry.HOMEPAGE + "\n\n"
                + "如果你是通过付费购买得到的，那你被骗了 —— 本项目没有任何收费版本，"
                + "也不会通过任何渠道出售。请到上面的地址免费获取。");
        root.addView(notice);

        section(root, "去广告");
        toggle(root, "kill_ad_sdk", "掐断广告 SDK（TopOn 聚合 + 基准，走 App 自己的入口）", true);
        toggle(root, "kill_sdk_direct", "【激进】直接掐第三方 SDK 自身 init（可能半初始化，默认关）", false);
        toggle(root, "kill_splash", "跳过冷启动开屏广告", true);
        toggle(root, "kill_hot_splash", "跳过热启动广告", true);
        toggle(root, "block_update", "屏蔽强制更新弹窗", true);
        toggle(root, "hide_home_ads", "隐藏首页广告容器", true);
        toggle(root, "hide_web_ads", "隐藏开门页/H5 广告容器", true);
        toggle(root, "hide_bottom_tab", "隐藏底部导航栏", false);
        toggle(root, "block_shake_ad", "关闭摇一摇广告（精准挂 App 自己的开关，不动系统传感器）", true);

        section(root, "首页精简（这些区块里全是淘宝客推广，不是广告 SDK 素材）");
        toggle(root, "hide_home_feed", "去掉整块淘宝客商品流（含 9.9元秒杀 那排导购 tab）", true);
        toggle(root, "hide_home_member_card", "去掉「升级会员 享受专享权益」卡片", true);
        toggle(root, "hide_home_flower_card", "去掉「小红花 / 每日领水滴」卡片", true);
        toggle(root, "hide_home_ai_entry", "去掉首页悬浮 AI 入口（长按说话）", true);

        section(root, "开门辅助");
        toggle(root, "bypass_automation_guard", "绕过自动化开锁防护（5.7.2 必需）", true);
        toggle(root, "auto_enter_door", "启动后自动进入开门页", true);
        toggle(root, "auto_tap_unlock", "自动点击「点击开锁」", true);
        toggle(root, "exit_after_unlock", "开锁成功后自动退出 App", true);

        section(root, "钥匙导出");
        toggle(root, "export_key", "把门锁参数导出给独立 App「校园钥匙」", true);

        TextView tip = new TextView(this);
        tip.setTextSize(12f);
        tip.setTextColor(Color.parseColor("#5A6270"));
        tip.setPadding(0, dp(16), 0, 0);
        tip.setText("「校园钥匙」是一个不带广告的独立开门 App。\n"
                + "装上它、并在天猫校园里正常开一次门，本模块就会把 MAC 与 activationKey 送过去，"
                + "之后可以完全脱离天猫校园，用桌面图标或下拉通知栏磁贴一键开门。\n\n"
                + "也可以在 logcat（tag: TmxyAdFree）里看到完整参数，手动填进校园钥匙。");
        root.addView(tip);

        Button forceStop = new Button(this);
        forceStop.setText("强制停止天猫校园");
        forceStop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    android.app.ActivityManager am =
                            (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                    am.killBackgroundProcesses(HookEntry.TARGET_PKG);
                    android.widget.Toast.makeText(SettingsActivity.this,
                            "已请求停止，请重新打开天猫校园", android.widget.Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    android.widget.Toast.makeText(SettingsActivity.this,
                            String.valueOf(t.getMessage()), android.widget.Toast.LENGTH_SHORT).show();
                }
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        root.addView(forceStop, lp);

        return scroll;
    }

    private void section(LinearLayout root, String name) {
        TextView tv = new TextView(this);
        tv.setText(name);
        tv.setTextSize(14f);
        tv.setTextColor(Color.parseColor("#1565C0"));
        tv.setGravity(Gravity.START);
        tv.setPadding(0, dp(16), 0, dp(4));
        root.addView(tv);
    }

    private void toggle(LinearLayout root, final String key, String label, boolean def) {
        Switch sw = new Switch(this);
        sw.setText(label);
        sw.setTextSize(14f);
        sw.setPadding(0, dp(8), 0, dp(8));
        boolean current = getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE)
                .getBoolean(key, def);
        sw.setChecked(current);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Config.write(SettingsActivity.this, key, isChecked);
            }
        });
        root.addView(sw);
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
