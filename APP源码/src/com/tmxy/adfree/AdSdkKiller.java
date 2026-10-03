package com.tmxy.adfree;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 从源头掐断广告，按「收益/风险」分层。
 *
 * <p>逆向结论（详细依据见 out/AD-HOOK-POINTS.md）：
 * 天猫校园的广告 = TopOn(AnyThink/Taku) 聚合 + 基准 + 若干直投，
 * 而 App 自己有一层封装（{@code com.tmall.campus.ad.*}），所有广告位最终都要
 * 过「拿 adid → 拿广告位 ID → 加载素材」三道关。
 *
 * <p><b>可靠性排序（重要）</b>：真正拦住广告的是 <b>L3 {@code CampusAd.getAdid}</b> ——
 * 已逐条核验它的 27 个调用点，每一个都在下一行做 {@code adid == null || isBlank} 判断后
 * 直接 return 或走空分支，所以返回 null 既安全又必然让广告路径退出。
 *
 * <p>对比之下 <b>L3' {@code getProgramAdId}</b> 返回 null <b>并不是</b>拦截：
 * <ul>
 *   <li>{@code TakuFeedAdUtils.findFeedId} / {@code TakuInsertPreloadAd.findInsertId}
 *       → 回退成 {@code "0"}（加载必然失败，属于「让请求快点失败」）</li>
 *   <li>{@code SplashAdManager.findSplashId} → 回退成<b>硬编码广告位</b>
 *       （{@code b1gm69tss34c5u} / {@code b673d7914f3a74} 等，<b>照样可能出广告</b>）</li>
 * </ul>
 * 开屏那条路是靠 L5 {@code SplashActivity.startWork()} 整体替换 + L1 的
 * {@code advertisement_operate_switch=false} 挡住的，不是靠 L3'。
 *
 * <table>
 *   <tr><th>层</th><th>Hook</th><th>覆盖</th></tr>
 *   <tr><td>L1</td><td>{@link AdConfigBlock}（{@code Configuration.load}）</td><td>10 个远端开关，零风险（走 App 自己的开关）</td></tr>
 *   <tr><td>L2</td><td>{@code AdInitJob.run()} / {@code CampusAdInitJob.run()} / {@code TakuManager.initSdk} / {@code JZManager.initSdk}</td><td>App 自己的 SDK 初始化入口</td></tr>
 *   <tr><td>L2b</td><td>{@code ATSDK.init} 等 16 个第三方 SDK 入口</td><td>默认关（{@code kill_sdk_direct}），避免半初始化</td></tr>
 *   <tr><td>L3</td><td>{@code CampusAd.getAdid(...)} → null</td><td><b>真正的总闸门</b>（27/27 调用点 null 安全）</td></tr>
 *   <tr><td>L3'</td><td>{@code AdConfigManager.getProgramAdId(...)} → null</td><td>仅让请求快速失败，<b>不是闸门</b></td></tr>
 *   <tr><td>L4</td><td>{@code TakuFeedAdUtils.loadTakuAd(...)} → null</td><td>横幅/信息流统一加载口（8 处复用，调用点 filterNotNull）</td></tr>
 *   <tr><td>L4'</td><td>{@code TakuInsertPreloadAd.loadAd(...)} → null</td><td>插屏加载口（调用点 != null 检查）</td></tr>
 *   <tr><td>L5</td><td>{@code SplashActivity.startWork()} 替换（在 AdBlock 里）</td><td>冷启动开屏</td></tr>
 *   <tr><td>L5'</td><td>{@code AdHotLaunchLifecycleCallback.showHotStartAd / loadHotStartAd}</td><td>热启动广告</td></tr>
 *   <tr><td>L7</td><td>{@code TakuManager$forbidOctopus$1.isCanShake} / {@code TakuManager$forbidBeiZi$1.forbidSensor}</td><td>摇一摇（精准，不动系统传感器）</td></tr>
 * </table>
 *
 * <p>故意<b>不</b>碰激励视频（{@code TakuIncentiveAdManager}）—— 那是用户主动触发的
 * 「看广告得积分」，拦了会破坏功能。
 */
public final class AdSdkKiller {

    /** TopOn 聚合根（第三方，非 classes7.dex，按名字兜底）。 */
    private static final String AT_SDK = "com.anythink.core.api.ATSDK";

    /** 可能被直投初始化的各家 SDK；找不到就跳过。 */
    private static final String[] DIRECT_SDK_CLASSES = {
            "com.bytedance.sdk.openadsdk.TTAdSdk",          // 穿山甲
            "com.bytedance.msdk.api.TTAdSdk",               // GroMore
            "com.qq.e.comm.managers.GDTAdSdk",              // 优量汇
            "com.kwad.sdk.api.KsAdSDKImpl",                 // 快手
            "com.kwad.sdk.api.KsAdSDK",
            "com.baidu.mobads.sdk.api.BaiduMobAdSDK",       // 百度
            "com.ubix.ssp.open.UBiXWinPlatform",            // UBiX
            "com.ubix.ssp.open.UBiXAdSDK",
            "com.meishu.sdk.core.MeishuAdSDK",              // 美数
            "com.beizi.ad.BeiZiAdManager",                  // 倍孜
            "com.beizi.fusion.BeiZiAdManager",
            "com.octopus.ad.OctopusAdSDK",                  // 章鱼/八爪鱼
            "com.smartdigimkt.sdk.api.SDMSDK",              // smartdigimkt
            "com.smartdigimkt.sdk.SDMAdSDK",
            "cj.mobile.CJMobileAd",                         // cj.mobile
    };

    private static int hooked;
    /** 幂等闸门：HookEntry 直接调一次，AdBlock.install 内部又会调一次。 */
    private static volatile boolean installed;

    private AdSdkKiller() {}

    /**
     * 幂等安装。
     *
     * <p>真机日志暴露过一个 bug：{@code HookEntry} 把 AdSdkKiller 当一个独立分组调，
     * {@code AdBlock.install} 里也调了一次 —— 结果整批 Hook 被挂了两遍，
     * 自检汇总里 {@code adSdk} 计数虚高（17 而不是 9），日志也翻倍。
     * 功能上因为都是设同一个结果所以没崩，但纯属浪费且严重干扰排查。
     *
     * @return 本次新挂上的 Hook 数；已装过返回 0。
     */
    public static synchronized int install(ClassLoader cl) {
        if (installed) {
            Logx.d("AdSdkKiller: 已安装过，跳过重复安装");
            return 0;
        }
        hooked = 0;
        // ---------------- L1 配置层 ----------------
        AdConfigBlock.install(cl);

        // ---------------- L2 SDK 初始化层（App 自己的入口）----------------
        // 这三刀等价于 App 自己的开关 advertisement_taku_init_switch=false /
        // advertisement_jz_init_switch=false 所导致的最终状态 —— 也就是 App 本来
        // 就支持运行的状态（SplashAdUtil.takuAdSwitch() 为 false 时它自己就不 init）。
        noOp(cl, "com.tmall.campus.ad.AdInitJob", "run");
        // CampusAdInitJob 是**另一个**独立任务（name="campusAd"），
        // 唯一作用是 AdConfigManager.init() -> MTOP mtop.tmall.campus.guide.advertising.config.list
        // （CampusAdInitJob.java:12-15）。不补这一刀，广告配置接口仍会被请求、
        // ad_config 仍会写 KV。成本 1 行，收益明确。
        noOp(cl, "com.tmall.campus.ad.CampusAdInitJob", "run");
        noOp(cl, "com.tmall.campus.ad.takuad.TakuManager", "initSdk");
        noOp(cl, "com.tmall.campus.ad.takuad.TakuManager", "configureAdSdkPrivacySettings");
        noOp(cl, "com.tmall.campus.ad.jizhunad.JZManager", "initSdk");

        // ---------------- L2b 直接掐第三方 SDK 入口（默认关）----------------
        // 为什么默认关：上一组已经把 App 自己的初始化入口关掉了，第三方 SDK 只会
        // 由 TopOn 的 *ATInitManager 适配器拉起 —— 那些适配器在 TakuManager.initSdk
        // 被空实现后根本不会跑。再额外直接 noOp 这些 SDK 的 init，收益是 0，
        // 但一旦 App 里还有别的路径直接调它们，就可能造成「SDK 半初始化」
        // 这种 App 自己都没设计过的状态。所以放到开关后面，默认不开。
        if (Config.killSdkDirect) {
            noOp(cl, AT_SDK, "init");
            noOp(cl, AT_SDK, "start");
            for (String cls : DIRECT_SDK_CLASSES) {
                noOp(cl, cls, "init");
            }
        }

        // ---------------- L3 / L3' 命中判定层 ----------------
        // getAdid 是真正的总闸门：已逐条核验 27 个调用点，全部在下一行做
        // `adid == null || isBlank` 判断后直接 return/走空分支。
        nullify(cl, "com.tmall.campus.ad.CampusAd", "getAdid");                       // (String,String,String,Integer) static
        // getProgramAdId 返回 null **不是**拦截，只是让 App 用兜底值：
        //   TakuFeedAdUtils.findFeedId / TakuInsertPreloadAd.findInsertId -> "0"（加载必然失败）
        //   SplashAdManager.findSplashId -> 硬编码广告位（b1gm69tss34c5u 等，仍可能出广告！）
        // 所以它只是「让网络请求快速失败」的辅助，不是闸门。
        nullify(cl, "com.tmall.campus.ad.manager.AdConfigManager", "getProgramAdId"); // (String)

        // ---------------- L4 加载层 ----------------
        // 参数里含 kotlin.coroutines.Continuation，模块编译期拿不到该类，
        // 因此按「方法名 + 参数个数」匹配，不写死参数类型。
        nullify(cl, "com.tmall.campus.ad.takuad.feedad.util.TakuFeedAdUtils", "loadTakuAd");       // (String,String,Continuation)
        nullify(cl, "com.tmall.campus.ad.takuad.insertad.util.TakuInsertPreloadAd", "loadAd");     // (String,Context,Continuation)

        // ---------------- L5 开屏层 ----------------
        noOp(cl, "com.tmall.campus.launcher.lifecycle.AdHotLaunchLifecycleCallback", "showHotStartAd");
        noOp(cl, "com.tmall.campus.launcher.lifecycle.AdHotLaunchLifecycleCallback", "loadHotStartAd");

        // App 自己的开屏开关，直接从源头返回 false。
        // 注意：方法名必须分门别类 —— 真机日志里出现过
        // 「SplashAdUtil.checkPlayAd 没挂上」「CampusAd.takuAdSwitch 没挂上」这类噪音，
        // 就是因为早期版本把 4 个方法名无差别套到两个类上。这两个类各自有哪些开关：
        //   SplashAdUtil: takuAdSwitch / operateAdSwitch / jzAdSwitch   （SplashAdUtil.java:44/52/58）
        //   CampusAd:     checkPlayAd / checkDynamicPlayAd              （CampusAd.java:110/128）
        final String splashUtil = "com.tmall.campus.ad.takuad.splashad.utils.SplashAdUtil";
        noArgFalse(cl, splashUtil, "takuAdSwitch");
        noArgFalse(cl, splashUtil, "operateAdSwitch");
        noArgFalse(cl, splashUtil, "jzAdSwitch");
        noArgFalse(cl, "com.tmall.campus.ad.CampusAd", "checkPlayAd");
        noArgFalse(cl, "com.tmall.campus.ad.CampusAd", "checkDynamicPlayAd");

        // ---------------- L7 摇一摇 ----------------
        // 精准挂 App 自己的两个开关，不动系统 SensorManager（那样会连带废掉指南针/地图）。
        if (Config.blockShakeAd) {
            noArgFalse(cl, "com.tmall.campus.ad.takuad.TakuManager$forbidOctopus$1", "isCanShake");
            noArgFalse(cl, "com.tmall.campus.ad.takuad.TakuManager$forbidBeiZi$1", "forbidSensor");
        }
        installed = true;
        Logx.ok("AdSdkKiller: 共挂上 " + hooked + " 个 Hook");
        return hooked;
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 把类里所有叫 {@code name} 的方法替换成空实现（void）或返回默认值。
     * 故意不写死参数签名 —— 广告 SDK 版本之间参数经常变，按名字扫更耐用。
     */
    private static void noOp(ClassLoader cl, String className, String name) {
        Class<?> c = XposedHelpers.findClassIfExists(className, cl);
        if (c == null) {
            Logx.d("AdSdkKiller: 类不存在，跳过 " + className);
            return;
        }
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (!name.equals(m.getName()) || m.getReturnType() != void.class) {
                continue;
            }
            try {
                XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(null));
                n++;
            } catch (Throwable t) {
                Logx.d("AdSdkKiller: hook " + className + "." + name + " 失败: " + t);
            }
        }
        if (n > 0) {
            hooked += n;
            Logx.ok("AdSdkKiller: 已空实现 " + className + "." + name + " x" + n);
        } else {
            Logx.miss("AdSdkKiller: " + className + "." + name + " 没挂上（类或方法不存在）");
        }
    }

    /** 把类里所有叫 {@code name} 的<b>有返回值</b>方法替换成返回 null。 */
    private static void nullify(ClassLoader cl, String className, String name) {
        Class<?> c = XposedHelpers.findClassIfExists(className, cl);
        if (c == null) {
            Logx.d("AdSdkKiller: 类不存在，跳过 " + className);
            return;
        }
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (!name.equals(m.getName()) || m.getReturnType() == void.class
                    || m.getReturnType().isPrimitive()) {
                continue;
            }
            try {
                XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(null));
                n++;
            } catch (Throwable t) {
                Logx.d("AdSdkKiller: hook " + className + "." + name + " 失败: " + t);
            }
        }
        if (n > 0) {
            hooked += n;
            Logx.ok("AdSdkKiller: " + className + "." + name + " -> null x" + n);
        } else {
            Logx.miss("AdSdkKiller: " + className + "." + name + " 没挂上（类或方法不存在）");
        }
    }

    /** 无参方法返回默认值（boolean 方法返回 false）。 */
    private static void noArgFalse(ClassLoader cl, String className, String name) {
        Class<?> c = XposedHelpers.findClassIfExists(className, cl);
        if (c == null) {
            Logx.d("AdSdkKiller: 类不存在，跳过 " + className);
            return;
        }
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (!name.equals(m.getName()) || m.getParameterTypes().length != 0) {
                continue;
            }
            try {
                XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(defaultFor(m.getReturnType())));
                n++;
            } catch (Throwable t) {
                Logx.d("AdSdkKiller: hook " + className + "." + name + " 失败: " + t);
            }
        }
        if (n > 0) {
            hooked += n;
            Logx.ok("AdSdkKiller: " + className + "." + name + " -> 默认值 x" + n);
        } else {
            Logx.miss("AdSdkKiller: " + className + "." + name + " 没挂上（类或方法不存在）");
        }
    }

    private static Object defaultFor(Class<?> rt) {
        if (rt == void.class || rt == Void.class) {
            return null;
        }
        if (rt == boolean.class || rt == Boolean.class) {
            return Boolean.FALSE;
        }
        if (rt == int.class || rt == Integer.class) {
            return Integer.valueOf(0);
        }
        if (rt == long.class || rt == Long.class) {
            return Long.valueOf(0L);
        }
        if (rt == float.class || rt == Float.class) {
            return Float.valueOf(0f);
        }
        if (rt == double.class || rt == Double.class) {
            return Double.valueOf(0d);
        }
        if (rt == short.class || rt == Short.class) {
            return Short.valueOf((short) 0);
        }
        if (rt == byte.class || rt == Byte.class) {
            return Byte.valueOf((byte) 0);
        }
        if (rt == char.class || rt == Character.class) {
            return Character.valueOf((char) 0);
        }
        return null;
    }
}
