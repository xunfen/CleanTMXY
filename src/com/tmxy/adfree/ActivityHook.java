package com.tmxy.adfree;

import android.app.Activity;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 统一的 Activity 生命周期分发。
 *
 * <p>为什么需要它：{@code CampusWebActivity} 本身<b>没有</b>覆写 {@code onResume}
 * （已用反编译源码核验），如果直接 {@code findMethodExactIfExists(CampusWebActivity, "onResume")}，
 * Xposed 会沿继承链找到 {@code android.app.Activity.onResume} 并挂上去 —— 那样回调会
 * 在进程内<b>每个</b> Activity 上触发，而不是只在开门页。
 *
 * <p>正确做法：只挂一次真实的 {@code Activity.onResume}，然后在回调里按实际类名过滤。
 */
public final class ActivityHook {

    public interface Listener {
        void onActivityResumed(Activity activity);
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<Listener>();
    private static volatile boolean installed;

    /** 主线程 Handler，供各监听者做延迟重试。 */
    public static final android.os.Handler HANDLER =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private ActivityHook() {}

    public static void addListener(Listener l) {
        LISTENERS.add(l);
    }

    /** @return true 表示 Activity.onResume 已挂上（重复调用只有第一次生效，同样返回 true）。 */
    public static synchronized boolean install(ClassLoader cl) {
        if (installed) {
            return true;
        }
        try {
            Class<?> activityClass = XposedHelpers.findClass("android.app.Activity", cl);
            Method onResume = XposedHelpers.findMethodExactIfExists(activityClass, "onResume");
            if (onResume == null) {
                Logx.miss("ActivityHook: 找不到 Activity.onResume");
                return false;
            }
            XposedBridge.hookMethod(onResume, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof Activity)) {
                        return;
                    }
                    Activity a = (Activity) param.thisObject;
                    for (Listener l : LISTENERS) {
                        try {
                            l.onActivityResumed(a);
                        } catch (Throwable t) {
                            Logx.e("ActivityHook listener 失败", t);
                        }
                    }
                }
            });
            installed = true;
            Logx.ok("ActivityHook: 已挂 Activity.onResume（按类名分发）");
            return true;
        } catch (Throwable t) {
            Logx.e("ActivityHook.install 失败", t);
            return false;
        }
    }

    public static boolean isResumed(Activity a, String className) {
        return a != null && className.equals(a.getClass().getName());
    }
}
