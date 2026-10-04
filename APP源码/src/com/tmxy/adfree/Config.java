package com.tmxy.adfree;

import android.content.Context;

import de.robv.android.xposed.XSharedPreferences;

/**
 * 模块开关。优先读 LSPosed 提供的 XSharedPreferences，读不到就全部按「开」处理，
 * 保证「装上就能用」。
 */
public final class Config {

    public static final String PREFS = "tmxy_adfree";

    // 去广告
    public static boolean killAdSdk = true;
    /**
     * 额外直接掐断第三方 SDK 自身的 init（ATSDK / TTAdSdk / GDTAdSdk / KsAdSDKImpl…）。
     * 默认关：App 自己的初始化入口已被 killAdSdk 关掉，第三方 SDK 只会由 TopOn 适配器拉起，
     * 而那些适配器不会跑；再直接 noOp 它们收益为 0，却可能造出「SDK 半初始化」这种
     * App 自己都没设计过的状态。详见 AdSdkKiller 里的说明。
     */
    public static boolean killSdkDirect = false;
    public static boolean killSplash = true;
    public static boolean killHotSplash = true;
    public static boolean blockUpdate = true;
    public static boolean hideHomeAds = true;
    public static boolean hideWebAds = true;
    public static boolean hideBottomTab = false;
    /** 精准关闭 App 自己的摇一摇开关（不动系统传感器）。 */
    public static boolean blockShakeAd = true;

    // 首页精简（真机截图确认过：这些区块里全是淘宝客推广，不是广告 SDK 素材）
    /** 隐藏首页整块淘宝客商品流（item-recommend 模块：顶部导购 tab + 商品网格）。 */
    public static boolean hideHomeFeed = true;
    /** 隐藏「升级会员 享受专享权益」卡片（member_card）。 */
    public static boolean hideHomeMemberCard = true;
    /** 隐藏「小红花 / 每日领水滴」卡片（flower_card）。 */
    public static boolean hideHomeFlowerCard = true;
    /** 隐藏首页悬浮 AI 入口（「长按说话」胶囊）。 */
    public static boolean hideHomeAiEntry = true;
    /**
     * 底部导航只留「首页」和「我的」。
     *
     * <p><b>未完成，默认关。</b>两次尝试都不对：挂 rearrangeTabList 得到「首页+商城」，
     * 再挂 MainTab.values() 变成只剩「首页」。该 App 的 tab 应由
     * TabLayoutMediator 按 ViewPager2 适配器 item 数生成、再用位置回调设文字图标，
     * 需要进一步确认那个回调才能改对。
     */
    public static boolean trimBottomTab = false;


    // 开门辅助
    public static boolean bypassAutomationGuard = true;
    public static boolean autoEnterDoor = true;
    public static boolean autoTapUnlock = true;
    public static boolean exitAfterUnlock = true;
    public static long unlockWatchTimeoutMs = 120_000L;

    // 钥匙导出
    public static boolean exportKey = true;

    private static XSharedPreferences prefs;

    /** 内嵌（免 root）模式：模块跑在宿主进程里，直接读宿主自己的 prefs。
     *  LSPosed 模式下 XSharedPreferences 可读，就不会走到这里。 */
    private static android.content.SharedPreferences hostPrefs;
    private static volatile boolean useHost;

    private Config() {}

    public static synchronized void init() {
        try {
            XSharedPreferences p = new XSharedPreferences(HookEntry.MODULE_PKG, PREFS);
            p.reload();
            prefs = p;
        } catch (Throwable t) {
            Logx.w("XSharedPreferences unavailable, using defaults: " + t);
            prefs = null;
        }
        reload();
    }

    public static synchronized void reload() {
        killAdSdk = getB("kill_ad_sdk", true);
        killSdkDirect = getB("kill_sdk_direct", false);
        killSplash = getB("kill_splash", true);
        killHotSplash = getB("kill_hot_splash", true);
        blockUpdate = getB("block_update", true);
        hideHomeAds = getB("hide_home_ads", true);
        hideWebAds = getB("hide_web_ads", true);
        hideBottomTab = getB("hide_bottom_tab", false);
        blockShakeAd = getB("block_shake_ad", true);

        hideHomeFeed = getB("hide_home_feed", true);
        hideHomeMemberCard = getB("hide_home_member_card", true);
        hideHomeFlowerCard = getB("hide_home_flower_card", true);
        hideHomeAiEntry = getB("hide_home_ai_entry", true);
        trimBottomTab = getB("trim_bottom_tab", false);  // TODO 未完成：见 CHANGELOG，默认关

        bypassAutomationGuard = getB("bypass_automation_guard", true);
        autoEnterDoor = getB("auto_enter_door", true);
        autoTapUnlock = getB("auto_tap_unlock", true);
        exitAfterUnlock = getB("exit_after_unlock", true);
        unlockWatchTimeoutMs = getL("unlock_watch_timeout_ms", 120_000L);

        exportKey = getB("export_key", true);
    }

    /**
     * 内嵌模式：由宿主 Activity 调用一次，改用宿主自己的 prefs。
     *
     * <p>免 root 的补丁版把模块内嵌进天猫校园，模块跑在<b>天猫校园的 UID</b> 下，
     * 根本读不到独立模块 App（com.tmxy.adfree）的私有 prefs（实测：目录 drwx------，
     * 跨 UID 进不去，XSharedPreferences 静默返回默认值）。所以这里改用宿主自己的
     * prefs —— 和跑在宿主里的设置弹窗（{@link SettingsPanel}）写的是同一份。
     *
     * <p>LSPosed 场景下 XSharedPreferences 是【可读】的（LSPosed 的
     * xposedsharedprefs=true 开了口子），那就保持原样不动。
     */
    public static synchronized void attachHost(Context host) {
        if (useHost || host == null) {
            return;
        }
        // 判断 XSharedPreferences 这条路通不通：直接看模块 App 的 prefs 文件能不能读。
        // 不用 XSharedPreferences.isReadable() —— 我们链接的 xposed-api.jar 是精简 stub，
        // 没有这个方法（编译期报"找不到符号"）。
        try {
            java.io.File f = new java.io.File(
                    "/data/data/" + HookEntry.MODULE_PKG + "/shared_prefs/" + PREFS + ".xml");
            if (f.canRead()) {
                return;   // LSPosed 场景：文件可读，继续用模块自己的 prefs
            }
        } catch (Throwable ignored) {
        }
        try {
            hostPrefs = host.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            useHost = true;
            Logx.ok("Config: 改用宿主 prefs（内嵌模式）—— 设置弹窗与模块共用同一份");
            reload();
        } catch (Throwable t) {
            Logx.w("Config.attachHost 失败: " + t);
        }
    }

    /** 当前是不是"内嵌模式"（模块跑在宿主进程、读写宿主自己的 prefs）。 */
    public static boolean isHostMode() {
        return useHost;
    }

    private static boolean getB(String key, boolean def) {
        try {
            if (useHost) {
                return hostPrefs.getBoolean(key, def);
            }
            if (prefs == null) {
                return def;
            }
            return prefs.getBoolean(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    private static long getL(String key, long def) {
        try {
            if (useHost) {
                return hostPrefs.getLong(key, def);
            }
            if (prefs == null) {
                return def;
            }
            return prefs.getLong(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    /** 供模块自身的设置界面写入。 */
    public static void write(Context ctx, String key, boolean value) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(key, value).apply();
    }

    public static void write(Context ctx, String key, long value) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(key, value).apply();
    }
}
