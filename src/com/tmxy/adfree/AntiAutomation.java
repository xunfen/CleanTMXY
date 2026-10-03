package com.tmxy.adfree;

import android.content.Context;
import android.view.MotionEvent;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 绕过天猫校园 5.7.2 新增的「自动化开锁防护」{@code com.tmall.campus.utils.AutomationGuard}。
 *
 * <p>原始判定（AutomationGuard.java:145-148）：
 * <pre>
 * isAutomatedUnlock(ctx) = !(hasRecentNfcTap() || hasRecentGenuineTouch())
 *                          &amp;&amp; hasSuspiciousAccessibilityService(ctx)
 * </pre>
 * 也就是说：只要设备上开着任意第三方无障碍服务（李跳跳 / AutoJS / 自动点击器 / 各种跳广告工具），
 * 且最近 3 秒内没有「真实手指触摸」（合成 MotionEvent 的 deviceId==0、source 不含
 * SOURCE_TOUCHSCREEN、pressure/size 为 0 都会判为非真实），
 * 通过 JSBridge 发起的开锁就会被直接拒绝（VocLockBridge$unlock$1$1.java:69-77）。
 *
 * <p>这里把三个判定位点全部放开，让模块自己的自动开锁与用户手动点击都不会被拦。
 */
public final class AntiAutomation {

    private AntiAutomation() {}

    /** @return 成功挂上的 Hook 数量；0 表示这版没有 AutomationGuard。 */
    public static int install(ClassLoader cl) {
        if (!Config.bypassAutomationGuard) {
            Logx.i("AntiAutomation: 开关关闭，跳过");
            return 0;
        }
        int n = 0;
        try {
            Class<?> guard = XposedHelpers.findClass("com.tmall.campus.utils.AutomationGuard", cl);

            n += hookReturnFalse(guard, "isAutomatedUnlock", Context.class);
            n += hookReturnFalse(guard, "hasSuspiciousAccessibilityService", Context.class);

            Method genuine = XposedHelpers.findMethodExactIfExists(
                    guard, "genuineTouchRejectReason", MotionEvent.class);
            if (genuine != null) {
                XposedBridge.hookMethod(genuine, XC_MethodReplacement.returnConstant(null));
                Logx.ok("AntiAutomation: genuineTouchRejectReason -> null");
                n++;
            } else {
                Logx.miss("AntiAutomation: genuineTouchRejectReason 不存在");
            }

            if (n == 0) {
                Logx.miss("AntiAutomation: AutomationGuard 里一个方法都没挂上");
            } else {
                Logx.ok("AntiAutomation: 已绕过自动化开锁防护 x" + n);
            }
        } catch (Throwable t) {
            Logx.miss("AntiAutomation: 该版本没有 AutomationGuard（跳过）: " + t);
        }
        return n;
    }

    private static int hookReturnFalse(Class<?> clazz, String name, Class<?>... params) {
        try {
            Method m = XposedHelpers.findMethodExactIfExists(clazz, name, (Object[]) params);
            if (m == null) {
                Logx.miss("AntiAutomation: AutomationGuard." + name + " 不存在");
                return 0;
            }
            XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(Boolean.FALSE));
            Logx.ok("AntiAutomation: AutomationGuard." + name + " -> false");
            return 1;
        } catch (Throwable t) {
            Logx.miss("AntiAutomation: hook " + name + " 失败: " + t);
            return 0;
        }
    }
}
