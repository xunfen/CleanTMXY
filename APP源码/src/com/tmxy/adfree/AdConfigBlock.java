package com.tmxy.adfree;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * L1 配置层：拦截 {@code com.tmall.campus.configcenter.Configuration.load(String)}，
 * 用一处 Hook 关掉全 App 的广告开关与自动化限制。
 *
 * <p>依据：{@code ConfigUtils.readBoolConfig/readLongConfig/readStringConfig} 全部只经
 * {@code Configuration.INSTANCE.load(key)} 取值（{@code ConfigUtils.java:17-69}），
 * 所以在这里改返回值等于改了全部配置读取点。
 *
 * <p>每条被覆盖的 key 都在 5.7.2 反编译源码中核验过调用点（见类内注释）。
 * <b>只覆盖白名单里的 key</b>，其余一律放行，避免误伤业务配置。
 */
public final class AdConfigBlock {

    /** key -> 强制返回值。 */
    private static final Map<String, String> OVERRIDES = new HashMap<String, String>();

    static {
        // ---- 广告总开关 / 各投放位开关：一律 false ----
        OVERRIDES.put("enable_campus_advertisement", "false");          // CampusAd.java:225 自研广告总开关
        OVERRIDES.put("advertisement_operate_switch", "false");         // SplashAdUtil.java:44 开屏运营位
        OVERRIDES.put("advertisement_taku_init_switch", "false");       // SplashAdUtil.java:52 TopOn
        OVERRIDES.put("advertisement_jz_init_switch", "false");         // SplashAdUtil.java:58 基准
        OVERRIDES.put("enable_home_popup", "false");                    // HomePopupManager.java:101 首页弹窗

        // ---- 反自动化解锁：关掉，让模块能自动开门 ----
        OVERRIDES.put("voclock_automation_guard_enable", "false");      // VocLockBridge$unlock$1$1.java:69

        // ---- 开屏展示天数 / 次数：0 = 不再展示 ----
        OVERRIDES.put("show_campusad_splash_daycounts", "0");           // CampusAd.java:111
        OVERRIDES.put("show_campusad_dynamic_splash_daycounts", "0");   // CampusAd.java:157

        // ---- 加载间隔：拉到 int 上限 = 实际永不触发 ----
        OVERRIDES.put("advertisement_load_interval", "2147483647");                 // CampusAdLifecycleCallback.java:16
        OVERRIDES.put("advertisement_splash_hotLaunching_interval", "2147483647");  // SplashAdManager.java:130
    }

    /** 幂等闸门：HookEntry 与 AdSdkKiller 都会调 install，不能重复挂。 */
    private static volatile boolean installed;

    private AdConfigBlock() {}

    /**
     * 幂等安装。
     *
     * <p>{@code HookEntry} 会单独调一次，{@code AdSdkKiller.install} 内部也会调一次；
     * 没有这个闸门就会把 {@code Configuration.load} 挂两遍 —— 每次配置读取跑两遍回调，
     * 结果一样但纯属浪费，而且日志里会出现两条一样的“[OK] 已接管 10 个…”，很容易误导排查。
     *
     * @return 本次新接管的配置项个数；已经装过则返回 0。
     */
    public static synchronized int install(ClassLoader cl) {
        if (installed) {
            Logx.d("AdConfigBlock: 已安装过，跳过重复安装");
            return 0;
        }
        try {
            Class<?> cfg = XposedHelpers.findClassIfExists(
                    "com.tmall.campus.configcenter.Configuration", cl);
            if (cfg == null) {
                Logx.miss("AdConfigBlock: Configuration 类不存在，跳过");
                return 0;
            }
            Method load = XposedHelpers.findMethodExactIfExists(cfg, "load", String.class);
            if (load == null) {
                Logx.miss("AdConfigBlock: Configuration.load(String) 不存在，跳过");
                return 0;
            }
            XposedBridge.hookMethod(load, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length == 0) {
                        return;
                    }
                    Object key = param.args[0];
                    if (!(key instanceof String)) {
                        return;
                    }
                    String override = OVERRIDES.get(key);
                    if (override != null) {
                        param.setResult(override);
                    }
                }
            });
            installed = true;
            Logx.ok("AdConfigBlock: 已接管 " + OVERRIDES.size() + " 个广告/自动化配置项");
            return OVERRIDES.size();
        } catch (Throwable t) {
            Logx.e("AdConfigBlock.install 失败", t);
            return 0;
        }
    }

    /** 供设置界面展示。 */
    public static String[] overriddenKeys() {
        return OVERRIDES.keySet().toArray(new String[0]);
    }
}
