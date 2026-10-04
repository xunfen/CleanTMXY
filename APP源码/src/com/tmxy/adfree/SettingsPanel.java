package com.tmxy.adfree;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/**
 * 设置面板（纯代码构建，不依赖任何资源文件）。
 *
 * <p><b>为什么独立于 SettingsActivity：</b>免 root 的补丁版把模块内嵌进天猫校园后，
 * 设置界面<b>不能</b>做成 Activity —— Activity 必须声明在天猫校园的清单里，而那份清单
 * 我们改不了（LSPatch/NPatch 只加自己的加载器，不会加模块的组件）。
 *
 * <p>所以内嵌场景下设置做成<b>弹窗</b>注入到天猫校园当前的 Activity 里：弹窗跑在
 * 宿主进程、用宿主的 Context 读写，因此写的就是<b>宿主自己的</b> prefs —— 和注入端
 * （{@link Config} 的宿主模式）读的是同一份，开关立即生效。
 *
 * <p>有 root 的场景仍可从桌面图标打开 {@link SettingsActivity}，它直接复用本类的 build()。
 */
public final class SettingsPanel {

    private SettingsPanel() {}

    /** 内嵌场景：在天猫校园当前的 Activity 上弹出设置。 */
    public static void showDialog(Activity host) {
        if (host == null || host.isFinishing()) {
            return;
        }
        try {
            new AlertDialog.Builder(host)
                    .setView(build(host))
                    .setPositiveButton("关闭", null)
                    .show();
        } catch (Throwable t) {
            Logx.e("SettingsPanel.showDialog 失败", t);
        }
    }

    /** 构建设置界面。ctx 决定读写哪份 prefs：独立 App 里就是模块的，宿主里就是宿主的。 */
    public static View build(final Context ctx) {
        ScrollView scroll = new ScrollView(ctx);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(ctx, 18);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.parseColor("#F5F6F8"));
        scroll.addView(root);

        TextView title = new TextView(ctx);
        title.setText("天猫校园净化");
        title.setTextSize(22f);
        title.setTextColor(Color.parseColor("#15181D"));
        root.addView(title);

        TextView sub = new TextView(ctx);
        sub.setTextSize(12f);
        sub.setTextColor(Color.parseColor("#5A6270"));
        sub.setPadding(0, dp(ctx, 6), 0, dp(ctx, 14));
        sub.setText("作用域：com.tmall.campus.and（天猫校园 5.7.2）\n"
                + "改动开关后需要强制停止天猫校园再打开才生效。");
        root.addView(sub);

        root.addView(buildStatusCard(ctx));

        // 版权与防倒卖声明
        TextView notice = new TextView(ctx);
        notice.setTextSize(12.5f);
        notice.setTextColor(Color.parseColor("#8A2020"));
        notice.setBackgroundColor(Color.parseColor("#FDEBEB"));
        notice.setPadding(dp(ctx, 12), dp(ctx, 11), dp(ctx, 12), dp(ctx, 11));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nlp.bottomMargin = dp(ctx, 14);
        notice.setLayoutParams(nlp);
        notice.setText("【免费开源 · 谨防被骗】\n"
                + "本软件完全免费、开源，唯一发布地址：\n"
                + HookEntry.HOMEPAGE + "\n\n"
                + "如果你是通过付费购买得到的，那你被骗了 —— 本项目没有任何收费版本，"
                + "也不会通过任何渠道出售。请到上面的地址免费获取。");
        root.addView(notice);

        section(ctx, root, "去广告");
        toggle(ctx, root, "kill_ad_sdk", "掐断广告 SDK（TopOn 聚合 + 基准，走 App 自己的入口）", true);
        toggle(ctx, root, "kill_sdk_direct", "【激进】直接掐第三方 SDK 自身 init（可能半初始化，默认关）", false);
        toggle(ctx, root, "kill_splash", "跳过冷启动开屏广告", true);
        toggle(ctx, root, "kill_hot_splash", "跳过热启动广告", true);
        toggle(ctx, root, "block_update", "屏蔽强制更新弹窗", true);
        toggle(ctx, root, "hide_home_ads", "隐藏首页广告容器", true);
        toggle(ctx, root, "hide_web_ads", "隐藏开门页/H5 广告容器", true);
        toggle(ctx, root, "hide_bottom_tab", "隐藏底部导航栏", false);
        toggle(ctx, root, "block_shake_ad", "关闭摇一摇广告（精准挂 App 自己的开关，不动系统传感器）", true);

        section(ctx, root, "首页精简（这些区块里全是淘宝客推广，不是广告 SDK 素材）");
        toggle(ctx, root, "hide_home_feed", "去掉整块淘宝客商品流（含 9.9元秒杀 那排导购 tab）", true);
        toggle(ctx, root, "hide_home_member_card", "去掉「升级会员 享受专享权益」卡片", true);
        toggle(ctx, root, "hide_home_flower_card", "去掉「小红花 / 每日领水滴」卡片", true);
        toggle(ctx, root, "hide_home_ai_entry", "去掉首页悬浮 AI 入口（长按说话）", true);

        section(ctx, root, "开门辅助");
        toggle(ctx, root, "bypass_automation_guard", "绕过自动化开锁防护（5.7.2 必需）", true);
        toggle(ctx, root, "auto_enter_door", "启动后自动进入开门页", true);
        toggle(ctx, root, "auto_tap_unlock", "自动点击「点击开锁」", true);
        toggle(ctx, root, "exit_after_unlock", "开锁成功后自动退出 App", true);

        section(ctx, root, "钥匙导出");
        toggle(ctx, root, "export_key", "把门锁参数导出给独立 App「校园钥匙」", true);

        TextView tip = new TextView(ctx);
        tip.setTextSize(12f);
        tip.setTextColor(Color.parseColor("#5A6270"));
        tip.setPadding(0, dp(ctx, 16), 0, 0);
        tip.setText("「校园钥匙」是一个不带广告的独立开门 App。\n"
                + "装上它、并在天猫校园里正常开一次门，本模块就会把 MAC 与 activationKey 送过去，"
                + "之后可以完全脱离天猫校园，用桌面图标或下拉通知栏磁贴一键开门。\n\n"
                + "也可以在 logcat（tag: TmxyAdFree）里看到完整参数，手动填进校园钥匙。");
        root.addView(tip);

        Button forceStop = new Button(ctx);
        forceStop.setText("强制停止天猫校园");
        forceStop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    android.app.ActivityManager am =
                            (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
                    am.killBackgroundProcesses(HookEntry.TARGET_PKG);
                    toast(ctx, "已请求停止，请重新打开天猫校园");
                } catch (Throwable t) {
                    toast(ctx, String.valueOf(t.getMessage()));
                }
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, 14);
        root.addView(forceStop, lp);

        return scroll;
    }

    /**
     * 状态卡：显示模块版本、安装时间、天猫校园版本。
     *
     * <p>只显示能可靠拿到的事实。曾经想显示「是否已生效」，做不了：注入端跑在天猫校园
     * 进程里，而天猫校园 targetSdk 30、清单里没有 &lt;queries&gt; 声明能看见
     * com.tmxy.adfree —— Android 11+ 的包可见性会把注入端对模块 App 的所有访问都挡掉
     * （广播被静默丢弃、ContentProvider 报 Unknown authority）。生效与否看日志：
     * adb logcat -s TmxyAdFree:I
     */
    private static View buildStatusCard(Context ctx) {
        String target = "未安装";
        try {
            android.content.pm.PackageInfo pi =
                    ctx.getPackageManager().getPackageInfo(HookEntry.TARGET_PKG, 0);
            target = pi.versionName + " (" + pi.versionCode + ")";
        } catch (Throwable ignored) {
        }
        String myBuild = "—";
        try {
            android.content.pm.PackageInfo me =
                    ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            myBuild = android.text.format.DateFormat
                    .format("MM-dd HH:mm", me.lastUpdateTime).toString();
        } catch (Throwable ignored) {
        }

        StringBuilder sb = new StringBuilder();
        sb.append("模块版本    ").append(HookEntry.VERSION).append('\n');
        sb.append("安装于      ").append(myBuild).append('\n');
        sb.append("天猫校园    ").append(target).append('\n');
        sb.append("是否生效    看日志：adb logcat -s TmxyAdFree:I\n");
        sb.append("            （模块无法自动检测 —— 受 Android 包可见性限制）");

        TextView tv = new TextView(ctx);
        tv.setTextSize(12.5f);
        tv.setTextColor(Color.parseColor("#1B4F8A"));
        tv.setBackgroundColor(Color.parseColor("#EAF1FA"));
        tv.setPadding(dp(ctx, 12), dp(ctx, 11), dp(ctx, 12), dp(ctx, 11));
        tv.setText("【模块状态】\n" + sb);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(ctx, 14);
        tv.setLayoutParams(lp);
        return tv;
    }

    private static void section(Context ctx, LinearLayout root, String name) {
        TextView tv = new TextView(ctx);
        tv.setText(name);
        tv.setTextSize(14f);
        tv.setTextColor(Color.parseColor("#1565C0"));
        tv.setGravity(Gravity.START);
        tv.setPadding(0, dp(ctx, 16), 0, dp(ctx, 4));
        root.addView(tv);
    }

    private static void toggle(final Context ctx, LinearLayout root,
                               final String key, String label, boolean def) {
        Switch sw = new Switch(ctx);
        sw.setText(label);
        sw.setTextSize(14f);
        sw.setPadding(0, dp(ctx, 8), 0, dp(ctx, 8));
        boolean current = ctx.getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE)
                .getBoolean(key, def);
        sw.setChecked(current);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Config.write(ctx, key, isChecked);
            }
        });
        root.addView(sw);
    }

    private static void toast(Context ctx, String msg) {
        try {
            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    private static int dp(Context ctx, int v) {
        return Math.round(ctx.getResources().getDisplayMetrics().density * v);
    }
}