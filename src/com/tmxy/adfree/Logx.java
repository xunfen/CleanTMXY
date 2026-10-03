package com.tmxy.adfree;

import android.util.Log;

import de.robv.android.xposed.XposedBridge;

/**
 * 双通道日志：logcat + Xposed 模块日志。
 *
 * <p><b>级别约定</b>：诊断信息（类是否存在、Hook 成功几个）一律走 {@link #i}
 * 或 {@link #w}，保证 {@code adb logcat -s TmxyAdFree:I} 就能看全 ——
 * 早期版本把这些放在 DEBUG，结果被 -s 过滤掉，等于没有诊断能力。
 */
public final class Logx {

    private Logx() {}

    public static void i(String msg) {
        Log.i(HookEntry.TAG, msg);
        xp(msg);
    }

    /**
     * 历史调用点保留。语义已改为 INFO —— 早期版本这里是 DEBUG，
     * 结果被 {@code adb logcat -s TmxyAdFree:I} 过滤掉，导致诊断信息看不到。
     */
    public static void d(String msg) {
        i(msg);
    }

    /** 自检项：成功。 */
    public static void ok(String msg) {
        i("[OK] " + msg);
    }

    /** 自检项：失败/跳过。用 WARN，保证不会被 -s TAG:I 过滤。 */
    public static void miss(String msg) {
        w(msg);
    }

    public static void w(String msg) {
        Log.w(HookEntry.TAG, msg);
        xp(msg);
    }

    public static void e(String msg, Throwable t) {
        Log.e(HookEntry.TAG, msg, t);
        try {
            XposedBridge.log(HookEntry.TAG + ": " + msg);
            if (t != null) {
                XposedBridge.log(t);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 同时投递到 Xposed/LSPosed 模块日志。 */
    private static void xp(String msg) {
        try {
            XposedBridge.log(HookEntry.TAG + ": " + msg);
        } catch (Throwable ignored) {
        }
    }
}
