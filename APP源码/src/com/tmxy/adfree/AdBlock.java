package com.tmxy.adfree;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 去广告 Hook 集合（针对天猫校园 5.7.2，包名 com.tmall.campus.and）。
 *
 * <ol>
 *   <li>冷启动开屏广告：替换 {@code SplashActivity.startWork()}，直接走 openMainActivity()</li>
 *   <li>热启动广告：替换 {@code HotSplashActivity.initView()}，直接 finish()</li>
 *   <li>强更弹窗：{@code UpdateUIConfirmImpl.alertForConfirm(...)} 全部返回 null</li>
 *   <li>首页 / 开门页原生广告容器：按资源 id 隐藏（兜底清残留空框）</li>
 * </ol>
 */
public final class AdBlock {

    /**
     * 首页广告/推广位容器候选资源名。
     *
     * <p><b>这些名字全部从 5.7.2 的 {@code R$id} 常量表里实挖出来的</b>
     * （30 个 R$id.java / 2227 个 id 名全量比对），不是猜的 —— 参考模块 MainHook 里那套
     * 5.0.0 的 id 在 5.7.2 已经 13/17 零命中。
     *
     * <p>来源包：{@code com.tmall.campus.home} / {@code .ui} / {@code .members}
     */
    private static final String[] HOME_AD_IDS = {
            // ---- 淘宝客商品流整块（真机截图确认：里面全是推广商品，含盗版软件广告）----
            "cmv_commodity",        // home —— CommodityModuleView，商品流模块的根
            "cl_commodity",         // home —— 商品区容器
            "tab_commodity",        // home —— 顶部导购 tab（9.9元秒杀/苹果专区/…）
            "vp_commodity",         // home —— 商品 ViewPager
            "rv_commodity",         // home —— 商品网格 RecyclerView
            // ---- 首页推广卡片 ----
            "fl_ai_float_container", // activity_main —— 底部 AI 悬浮容器（「长按说话」）
            "member_card",          // home —— MemberAssetCardView「升级会员 享受专享权益」
            "flower_card",          // home —— FlowerFarmCardView「小红花 / 每日领水滴」
            // ---- 广告 SDK 容器（原有）----
            "cl_ad_container",      // home
            "fl_ad_container",      // home / profile / scancode
            "iv_fix_banner",        // home
            "iv_invite_banner",     // home
            "seaview_banner",       // home
            "rv_product_banner",    // ui
            "iv_banner",            // ui
            "banner_fl",            // members
            "ad_container",         // members
    };

    /**
     * 开门页 / H5 页广告容器候选资源名（同样实挖自 R$id）。
     *
     * <p>来源包：{@code com.tmall.campus.ad} / {@code .scancode}
     */
    private static final String[] WEB_AD_IDS = {
            "fl_ad_render",         // ad —— 参考模块也提到了，5.7.2 仍存在
            "fl_ad",                // ad
            "cl_ad_top",            // ad
            "cl_ad_tag",            // ad
            "iv_close_ad",          // ad
            "cl_ad",                // scancode
            "icon_ad_container",    // scancode
            "fl_ad_tag",            // scancode
            "ll_ad_tag",            // scancode
    };

    /** 底部导航容器（已在 com.tmall.campus.and 的 R$id 中核验）。 */
    private static final String[] BOTTOM_TAB_IDS = {"tl_main"};

    private static final long[] RETRY_DELAYS = {0L, 300L, 900L, 1800L, 3500L};

    private static final String MAIN_ACTIVITY = "com.tmall.campus.and.main.MainActivity";
    private static final String WEB_ACTIVITY = "com.tmall.campus.bizwebview.ui.CampusWebActivity";

    /** 「哪些 id 解析不到」只报一次，避免每轮重试刷屏。 */
    private static volatile boolean missingLogged;

    private AdBlock() {}

    /** @return 成功挂上的 Hook 数量。 */
    public static int install(ClassLoader cl) {
        int n = 0;
        // 不在这里调 AdSdkKiller —— HookEntry 已把它作为独立分组调过一次。
        // 早期两处都调，导致整批 SDK Hook 被挂两遍、自检汇总的 adSdk 计数虚高
        // （真机日志里是 17 而不是 9），日志也整段翻倍，严重干扰排查。
        n += hookSplash(cl);
        n += hookHotSplash(cl);
        n += hookUpdateDialog(cl);
        n += hookActivityForAdHiding(cl);
        n += hookAiEntry(cl);
        n += hookBottomTab(cl);
        return n;
    }

    // ------------------------------------------------------------------ 开屏

    private static int hookSplash(ClassLoader cl) {
        if (!Config.killSplash) {
            Logx.i("AdBlock: killSplash 关闭，跳过");
            return 0;
        }
        try {
            Class<?> splash = XposedHelpers.findClass("com.tmall.campus.launcher.SplashActivity", cl);
            Method startWork = XposedHelpers.findMethodExactIfExists(splash, "startWork");
            if (startWork == null) {
                Logx.miss("AdBlock: SplashActivity.startWork 不存在，跳过");
                return 0;
            }
            XposedBridge.hookMethod(startWork, new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) {
                    DoorAssist.resetForColdStart();
                    try {
                        XposedHelpers.callMethod(param.thisObject, "openMainActivity");
                        Logx.i("已跳过开屏广告 -> openMainActivity()");
                    } catch (Throwable t) {
                        Logx.e("openMainActivity 调用失败", t);
                    }
                    return null;
                }
            });
            Logx.ok("AdBlock: hook SplashActivity.startWork");
            return 1;
        } catch (Throwable t) {
            Logx.e("hookSplash 失败", t);
            return 0;
        }
    }

    private static int hookHotSplash(ClassLoader cl) {
        if (!Config.killHotSplash) {
            Logx.i("AdBlock: killHotSplash 关闭，跳过");
            return 0;
        }
        try {
            Class<?> hot = XposedHelpers.findClass("com.tmall.campus.launcher.HotSplashActivity", cl);
            Method initView = XposedHelpers.findMethodExactIfExists(hot, "initView");
            if (initView == null) {
                Logx.miss("AdBlock: HotSplashActivity.initView 不存在，跳过");
                return 0;
            }
            XposedBridge.hookMethod(initView, new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) {
                    try {
                        if (param.thisObject instanceof Activity) {
                            ((Activity) param.thisObject).finish();
                        }
                        Logx.i("已跳过热启动广告");
                    } catch (Throwable t) {
                        Logx.e("finish HotSplash 失败", t);
                    }
                    return null;
                }
            });
            Logx.ok("AdBlock: hook HotSplashActivity.initView");
            return 1;
        } catch (Throwable t) {
            Logx.miss("AdBlock: hookHotSplash 跳过: " + t);
            return 0;
        }
    }

    // ------------------------------------------------------------------ 更新弹窗

    private static int hookUpdateDialog(ClassLoader cl) {
        if (!Config.blockUpdate) {
            Logx.i("AdBlock: blockUpdate 关闭，跳过");
            return 0;
        }
        try {
            Class<?> updateClass = XposedHelpers.findClass(
                    "com.tmall.campus.update.ui.UpdateUIConfirmImpl", cl);
            int n = 0;
            for (Method m : updateClass.getDeclaredMethods()) {
                if ("alertForConfirm".equals(m.getName())) {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(null));
                    n++;
                }
            }
            if (n == 0) {
                Logx.miss("AdBlock: UpdateUIConfirmImpl.alertForConfirm 不存在");
                return 0;
            }
            Logx.ok("AdBlock: hook 更新确认弹窗方法数=" + n);
            return n;
        } catch (Throwable t) {
            Logx.miss("AdBlock: hookUpdateDialog 跳过: " + t);
            return 0;
        }
    }

    // ------------------------------------------------------------------ 广告容器

    private static int hookActivityForAdHiding(ClassLoader cl) {
        if (!ActivityHook.install(cl)) {
            return 0;
        }
        ActivityHook.addListener(new ActivityHook.Listener() {
            @Override
            public void onActivityResumed(final Activity activity) {
                final String name = activity.getClass().getName();
                final String[] ids;
                final boolean isMain;
                if (MAIN_ACTIVITY.equals(name)) {
                    ids = enabledHomeIds();
                    isMain = true;
                } else if (WEB_ACTIVITY.equals(name)) {
                    ids = WEB_AD_IDS;
                    isMain = false;
                } else {
                    return;
                }
                Logx.i("清理广告容器 @" + name + " 候选 " + ids.length + " 个 id");
                for (long delay : RETRY_DELAYS) {
                    ActivityHook.HANDLER.postDelayed(new Runnable() {
                        @Override public void run() {
                            try {
                                int hidden = hideByIds(activity, ids);
                                if (hidden > 0) {
                                    Logx.i("已隐藏区块 " + hidden + " 个 @" + name);
                                }
                                if (isMain && Config.hideBottomTab) {
                                    hideBottomTab(activity);
                                }
                            } catch (Throwable t) {
                                Logx.e("hideAds 失败", t);
                            }
                        }
                    }, delay);
                }
            }
        });
        Logx.ok("AdBlock: 已注册区块隐藏（MainActivity / CampusWebActivity）");
        return 1;
    }

    /** 按开关筛出实际要隐藏的首页 id —— 让用户能逐项回退。 */
    private static String[] enabledHomeIds() {
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (String id : HOME_AD_IDS) {
            boolean keep;
            if ("cmv_commodity".equals(id) || "cl_commodity".equals(id)
                    || "tab_commodity".equals(id) || "vp_commodity".equals(id)
                    || "rv_commodity".equals(id)) {
                keep = Config.hideHomeFeed;
            } else if ("member_card".equals(id)) {
                keep = Config.hideHomeMemberCard;
            } else if ("flower_card".equals(id)) {
                keep = Config.hideHomeFlowerCard;
            } else {
                keep = true;
            }
            if (keep) {
                out.add(id);
            }
        }
        return out.toArray(new String[0]);
    }

    private static int hideByIds(Activity activity, String[] names) {
        int hidden = 0;
        View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        StringBuilder missing = new StringBuilder();
        for (String name : names) {
            Integer id = resolveId(activity, name);
            if (id == null || id == 0) {
                // 记下来：如果某个区块没藏掉，这一行就能立刻告诉我们
                // 是「这个版本里根本没这个 id」还是「找到了但没藏成功」。
                if (missing.length() > 0) {
                    missing.append(' ');
                }
                missing.append(name);
                continue;
            }
            // 深度遍历：同一个 id 在视图树里可能有多个实例（模块列表会重复 inflate），
            // 只 findViewById 拿第一个会漏。
            int n = decor == null ? 0 : hideByIdDeep(decor, id, 0);
            if (n > 0) {
                hidden += n;
            }
        }
        if (missing.length() > 0 && !missingLogged) {
            missingLogged = true;
            Logx.i("这些 id 在本版本里解析不到（正常，说明该区块不存在或已改 id）：" + missing);
        }
        return hidden;
    }

    private static int hideByIdDeep(View v, int id, int depth) {
        if (v == null || depth > 40) {
            return 0;
        }
        int n = 0;
        if (v.getId() == id && v.getVisibility() != View.GONE) {
            v.setVisibility(View.GONE);
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp != null) {
                lp.height = 0;
                v.setLayoutParams(lp);
            }
            n++;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                n += hideByIdDeep(g.getChildAt(i), id, depth + 1);
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ 底部导航

    /** 按枚举常量名过滤 tab 数组；保留 TAB_KEEP 里的。同类数组返回，兼容 Kotlin 类型期望。 */
    private static Object[] filterTabs(Object[] arr, String from) {
        java.util.List<Object> keep = new java.util.ArrayList<Object>();
        java.util.List<String> dropped = new java.util.ArrayList<String>();
        for (Object o : arr) {
            String name = (o instanceof Enum) ? ((Enum<?>) o).name() : null;
            if (name == null) {
                keep.add(o);
            } else if (TAB_KEEP.contains(name)) {
                keep.add(o);
            } else {
                dropped.add(name);
            }
        }
        if (dropped.isEmpty()) {
            return arr;
        }
        Object[] out = (Object[]) java.lang.reflect.Array.newInstance(
                arr.getClass().getComponentType(), keep.size());
        for (int i = 0; i < keep.size(); i++) {
            out[i] = keep.get(i);
        }
        Logx.ok("AdBlock: " + from + " 裁剪 -> 保留 " + keep.size() + " 个，去掉 " + dropped);
        return out;
    }

    /** 底部导航要保留的 tab（MainTab 枚举常量名）。 */
    private static final java.util.Set<String> TAB_KEEP =
            new java.util.HashSet<String>(java.util.Arrays.asList("HOME", "PROFILE"));

    /**
     * 底部导航只留「首页」和「我的」。
     *
     * <p>切入口是 {@code MainActivity:149}：
     * {@code MainTabLayout.INSTANCE.rearrangeTabList(MainTab.values())}
     * —— 所有 tab 都从这一个方法流出去，所以直接过滤它的返回值，
     * 比事后按 id 藏 View 干净得多，也不用复刻它自己的启停逻辑。
     *
     * <p>按<b>枚举常量名</b>过滤（不认下标），所以将来 tab 顺序变了也不会误伤。
     */
    private static int hookBottomTab(ClassLoader cl) {
        if (!Config.trimBottomTab) {
            Logx.i("AdBlock: trimBottomTab 关闭，跳过");
            return 0;
        }
        int n = 0;

        // ① 最上游：直接过滤 MainTab.values()。
        //    这个 App 的 tab 有不止一个数据来源（实测只挂 rearrangeTabList 时，
        //    日志显示已裁剪成功，但 TabLayout 上第二个 tab 仍是「商城」——
        //    说明界面不是从这个返回值建的）。改 values() 能让所有消费方看到一致结果。
        Class<?> tabEnum = XposedHelpers.findClassIfExists("com.tmall.campus.and.main.MainTab", cl);
        if (tabEnum != null) {
            try {
                Method values = tabEnum.getDeclaredMethod("values");
                XposedBridge.hookMethod(values, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object r = param.getResult();
                        if (!(r instanceof Object[])) {
                            return;
                        }
                        param.setResult(filterTabs((Object[]) r, "MainTab.values()"));
                    }
                });
                n++;
                Logx.ok("AdBlock: 已接管 MainTab.values()");
            } catch (Throwable t) {
                Logx.miss("AdBlock: 挂 MainTab.values() 失败: " + t);
            }
        }

        // ② 再挂 rearrangeTabList（双保险，某些路径可能直接传数组进来）
        String[] classes = {
                "com.tmall.campus.and.main.MainTabLayout$Companion",
                "com.tmall.campus.and.main.MainTabLayout",
        };
        for (String cn : classes) {
            Class<?> c = XposedHelpers.findClassIfExists(cn, cl);
            if (c == null) {
                continue;
            }
            for (Method m : c.getDeclaredMethods()) {
                if (!"rearrangeTabList".equals(m.getName()) || m.getParameterTypes().length != 1) {
                    continue;
                }
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object r = param.getResult();
                        if (r instanceof Object[]) {
                            param.setResult(filterTabs((Object[]) r, "rearrangeTabList"));
                        }
                    }
                });
                n++;
            }
        }
        if (n == 0) {
            Logx.miss("AdBlock: MainTabLayout.rearrangeTabList 不存在（跳过底部裁剪）");
        } else {
            Logx.ok("AdBlock: 已接管底部导航 tab 列表 x" + n);
        }
        return n;
    }

    // ------------------------------------------------------------------ 首页 AI 入口

    /**
     * 隐藏首页那个悬浮 AI 入口（「长按说话」胶囊）。
     *
     * <p>它不是布局里的 View，而是运行时从 DRouter 服务拿的：
     * {@code MainFragment.java:733 -> aiAssistantProvider.getFloatingEntryView(activity)}。
     * 接口 {@code IAIAssistantProvider.getFloatingEntryView} 声明就是 {@code @Nullable}，
     * 调用点 MainFragment$initPopupSources$4 也有 null 分支，所以返回 null 是契约内的行为。
     *
     * <p>要挂实现类而不是接口 —— 接口方法是抽象的，挂它拦不到实现。
     */
    private static int hookAiEntry(ClassLoader cl) {
        if (!Config.hideHomeAiEntry) {
            Logx.i("AdBlock: hideHomeAiEntry 关闭，跳过");
            return 0;
        }
        try {
            Class<?> svc = XposedHelpers.findClassIfExists(
                    "com.tmall.campus.assistant.AIAssistantService", cl);
            if (svc == null) {
                Logx.miss("AdBlock: AIAssistantService 不存在");
                return 0;
            }
            int n = 0;
            for (Method m : svc.getDeclaredMethods()) {
                if (!"getFloatingEntryView".equals(m.getName()) || m.getParameterTypes().length != 1) {
                    continue;
                }
                XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(null));
                n++;
            }
            if (n == 0) {
                Logx.miss("AdBlock: AIAssistantService.getFloatingEntryView 不存在");
                return 0;
            }
            Logx.ok("AdBlock: 已隐藏首页 AI 入口（getFloatingEntryView -> null）");
            return n;
        } catch (Throwable t) {
            Logx.miss("AdBlock: hookAiEntry 跳过: " + t);
            return 0;
        }
    }

    private static Integer resolveId(Context ctx, String name) {
        try {
            return ctx.getResources().getIdentifier(name, "id", ctx.getPackageName());
        } catch (Throwable t) {
            return null;
        }
    }

    private static void hideBottomTab(Activity activity) {
        try {
            Object tl = XposedHelpers.callMethod(activity, "getTlMain");
            if (tl instanceof View) {
                ((View) tl).setVisibility(View.GONE);
                Logx.i("已隐藏底部 Tab(getTlMain)");
                return;
            }
        } catch (Throwable ignored) {
        }
        for (String name : BOTTOM_TAB_IDS) {
            Integer id = resolveId(activity, name);
            if (id != null && id != 0) {
                View v = activity.findViewById(id);
                if (v != null) {
                    v.setVisibility(View.GONE);
                    Logx.i("已隐藏底部 Tab(" + name + ")");
                    return;
                }
            }
        }
    }

    // 摇一摇改由 AdSdkKiller 精准处理（挂 App 自己的
    // TakuManager$forbidOctopus$1.isCanShake / TakuManager$forbidBeiZi$1.forbidSensor），
    // 不再粗暴拦截系统 SensorManager —— 那样会连带废掉地图指南针等正常功能。
}
