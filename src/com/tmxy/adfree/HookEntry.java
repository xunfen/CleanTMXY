package com.tmxy.adfree;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 天猫校园净化模块入口。
 *
 * <p>作用域固定为 {@code com.tmall.campus.and}（含其全部子进程）。
 *
 * <p><b>诊断约定</b>：第一条日志必须在任何可能抛异常的动作之前打出来，
 * 并且末尾会输出一行自检汇总，形如
 * {@code 自检汇总: adConfig=10 adSdk=9/9 adblock=4 antiAuto=3 door=5 key=3}。
 * 只要 {@code adb logcat -s TmxyAdFree:I} 里能看到 {@code 自检汇总}，
 * 就能立刻判断是「模块没加载」还是「某组 Hook 没挂上」。
 */
public final class HookEntry implements IXposedHookLoadPackage {

    public static final String TAG = "TmxyAdFree";
    public static final String MODULE_PKG = "com.tmxy.adfree";
    public static final String TARGET_PKG = "com.tmall.campus.and";
    public static final String VERSION = "1.0.7";

    /** 本项目的唯一发布地址。防倒卖声明用它，改地址只改这一处。 */
    public static final String HOMEPAGE = "https://github.com/xunfen/CleanTMXY";

    private static volatile Context sAppContext;

    public static Context appContext() {
        return sAppContext;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // 第一行：任何可能失败的动作之前就打，确保「模块到底有没有被加载」可判定。
        try {
            Log.i(TAG, "TmxyAdFree v" + VERSION + " 已加载: package=" + lpparam.packageName
                    + " process=" + lpparam.processName);
            XposedBridge.log(TAG + ": v" + VERSION + " loaded into " + lpparam.processName);
        } catch (Throwable ignored) {
        }

        if (!TARGET_PKG.equals(lpparam.packageName)) {
            return;
        }

        int adConfig = -1, adSdk = -1, adBlock = -1, antiAuto = -1, door = -1, key = -1;
        try {
            Config.init();
        } catch (Throwable t) {
            Logx.e("Config.init 失败（将以默认开关继续）", t);
        }

        try {
            hookAppContext(lpparam.classLoader);
        } catch (Throwable t) {
            Logx.e("hookAppContext 失败", t);
        }

        adConfig = safe("AdConfigBlock", lpparam, 0);
        adSdk    = Config.killAdSdk ? safe("AdSdkKiller", lpparam, 1) : 0;
        adBlock  = safe("AdBlock", lpparam, 2);
        antiAuto = safe("AntiAutomation", lpparam, 3);
        door     = safe("DoorAssist", lpparam, 4);
        key      = safe("KeyBridge", lpparam, 5);

        Log.i(TAG, "自检汇总: adConfig=" + adConfig + " adSdk=" + adSdk + " adblock=" + adBlock
                + " antiAuto=" + antiAuto + " door=" + door + " key=" + key
                + " | 开关: killAdSdk=" + Config.killAdSdk + " autoDoor=" + Config.autoEnterDoor
                + " bypassGuard=" + Config.bypassAutomationGuard + " exportKey=" + Config.exportKey);
        Log.i(TAG, "如果上面某组是 0，说明该类在你这版 APK 里找不到 —— 请把上面两行发给我");
    }

    /** 单独执行一组 Hook，任何异常都被隔离，返回值是「挂上的 Hook 数量」，-1 表示整组失败。 */
    private int safe(String name, XC_LoadPackage.LoadPackageParam lp, int which) {
        try {
            switch (which) {
                case 0: return AdConfigBlock.install(lp.classLoader);
                case 1: return AdSdkKiller.install(lp.classLoader);
                case 2: return AdBlock.install(lp.classLoader);
                case 3: return AntiAutomation.install(lp.classLoader);
                case 4: return DoorAssist.install(lp.classLoader);
                case 5: return KeyBridge.install(lp.classLoader);
                default: return -1;
            }
        } catch (Throwable t) {
            Logx.e(name + " 整组失败", t);
            return -1;
        }
    }

    /** 尽早拿到 Application Context，供广告容器隐藏 / 参数投递使用。 */
    private void hookAppContext(ClassLoader cl) {
        XC_MethodHook capture = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object c = null;
                if (param.args != null && param.args.length > 0 && param.args[0] instanceof Context) {
                    c = param.args[0];
                } else if (param.thisObject instanceof Context) {
                    c = param.thisObject;
                }
                if (c instanceof Context) {
                    sAppContext = ((Context) c).getApplicationContext();
                    Logx.i("已取得 Application Context: " + sAppContext.getPackageName());
                }
            }
        };

        try {
            Class<?> app = XposedHelpers.findClass("com.tmall.campus.launcher.CampusApp", cl);
            // 注意：这里不能用 hookAllMethods —— 它会沿继承链找到
            // android.content.ContextWrapper.attachBaseContext（启动类方法），
            // 进程里每个 ContextWrapper 都会触发回调：没意义、噪音大，还可能
            // 把 sAppContext 覆盖成某个随机包装器的 context。
            // 只挂 CampusApp 自己声明的方法。
            int n = 0;
            for (Method m : app.getDeclaredMethods()) {
                String name = m.getName();
                if (!"attachBaseContext".equals(name) && !"onCreate".equals(name)) {
                    continue;
                }
                XposedBridge.hookMethod(m, capture);
                n++;
            }
            if (n > 0) {
                Logx.ok("CampusApp Context 捕获已挂 x" + n);
            } else {
                Logx.miss("CampusApp 上没有 attachBaseContext/onCreate（依赖 Application.attach 兜底）");
            }
        } catch (Throwable t) {
            Logx.miss("CampusApp 不可用（将依赖 Application.attach 兜底）: " + t);
        }

        try {
            Class<?> appBase = XposedHelpers.findClass("android.app.Application", cl);
            // Application.attach(Context) 是启动类方法，但一个进程通常只有一个 Application，
            // 这里挂它是为了覆盖「CampusApp 没被调用到」的子进程。
            XposedBridge.hookAllMethods(appBase, "attach", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof Application && sAppContext == null) {
                        sAppContext = ((Application) param.thisObject).getApplicationContext();
                    }
                }
            });
        } catch (Throwable t) {
            Logx.miss("hook Application.attach 失败: " + t);
        }
    }
}
