package com.tmxy.adfree;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import org.json.JSONObject;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 把天猫校园 H5 下发给门锁模块的 {@code JSBParams} 抓出来，转交给独立 App「校园钥匙」
 * （{@code com.tmxy.adfree.key}）。
 *
 * <p>这是「用自写 App 代替天猫校园开门」的关键一环：
 * VOC 协议只需要 {@code macAddress + activationKey} 两个参数（见 VOC-LOCK-PROTOCOL.md），
 * 而这两个值本来就由服务端经 H5 明文下发，抓一次之后独立 App 就能完全离线开锁。
 */
public final class KeyBridge {

    /** 独立 App 的 ContentProvider。 */
    private static final String KEY_APP_PKG = "com.tmxy.adfree.key";
    private static final Uri KEY_PROVIDER = Uri.parse("content://com.tmxy.adfree.key");
    private static final String TOKEN = "tmxy-adfree-key-v1";

    /** 收到的门锁参数广播（可选，供其它工具使用）。 */
    private static final String BROADCAST_ACTION = "com.tmxy.adfree.LOCK_PARAMS";

    private static volatile String lastSignature;

    private KeyBridge() {}

    /** @return 成功挂上的 Hook 数量。 */
    public static int install(ClassLoader cl) {
        if (!Config.exportKey) {
            Logx.i("KeyBridge: exportKey 关闭，跳过");
            return 0;
        }
        int n = 0;
        n += hookVocLockBridge(cl);
        n += hookVocLockHelper(cl);
        n += hookMvLock(cl);
        if (n == 0) {
            Logx.miss("KeyBridge: 一个门锁入口都没挂上，将无法抓取钥匙");
        }
        return n;
    }

    // ------------------------------------------------------------------ H5 原始 JSON

    private static int hookVocLockBridge(ClassLoader cl) {
        try {
            Class<?> bridge = XposedHelpers.findClass(
                    "com.tmall.campus.bizwebview.plugin.VocLockBridge", cl);
            int n = 0;
            for (Method m : bridge.getDeclaredMethods()) {
                String name = m.getName();
                if (!("unlock".equals(name) || "activate".equals(name)
                        || "reset".equals(name) || "battery".equals(name))) {
                    continue;
                }
                if (m.getParameterTypes().length < 1 || m.getParameterTypes()[0] != String.class) {
                    continue;
                }
                final String action = name;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (param.args[0] instanceof String) {
                                handleRawJson(action, (String) param.args[0]);
                            }
                        } catch (Throwable t) {
                            Logx.e("KeyBridge(VocLockBridge) 处理失败", t);
                        }
                    }
                });
                n++;
            }
            if (n == 0) { Logx.miss("KeyBridge: VocLockBridge 里没找到 unlock/activate"); return 0; }
            Logx.ok("KeyBridge: hook VocLockBridge 方法数=" + n);
            return n;
        } catch (Throwable t) {
            Logx.miss("KeyBridge: hookVocLockBridge 跳过: " + t);
            return 0;
        }
    }

    // ------------------------------------------------------------------ 结构化参数

    private static int hookVocLockHelper(ClassLoader cl) {
        try {
            Class<?> helper = XposedHelpers.findClass("com.tmall.campus.doorlock.VocLockHelperV2", cl);
            Class<?> jsbParams = XposedHelpers.findClass("com.tmall.campus.doorlock.JSBParams", cl);
            int n = 0;
            for (Method m : helper.getDeclaredMethods()) {
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length != 3) {
                    continue;
                }
                if (pt[0] != Context.class || pt[1] != jsbParams) {
                    continue;
                }
                final String action = m.getName();
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            exportParams(action, param.args[1]);
                        } catch (Throwable t) {
                            Logx.e("KeyBridge(VocLockHelperV2) 处理失败", t);
                        }
                    }
                });
                n++;
            }
            if (n == 0) { Logx.miss("KeyBridge: VocLockHelperV2 里没找到 (Context,JSBParams,*) 方法"); return 0; }
            Logx.ok("KeyBridge: hook VocLockHelperV2 方法数=" + n);
            return n;
        } catch (Throwable t) {
            Logx.miss("KeyBridge: hookVocLockHelper 跳过: " + t);
            return 0;
        }
    }

    private static void exportParams(String action, Object jsbParams) {
        if (jsbParams == null) {
            return;
        }
        String mac = str(XposedHelpers.callMethod(jsbParams, "getMacAddress"));
        String key = str(XposedHelpers.callMethod(jsbParams, "getActivationKey"));
        String adv = str(XposedHelpers.callMethod(jsbParams, "getAdvertisString"));
        String seid = str(XposedHelpers.callMethod(jsbParams, "getSeid"));
        long expire = 0L;
        try {
            Object e = XposedHelpers.callMethod(jsbParams, "getExpireTime");
            if (e instanceof Long) {
                expire = (Long) e;
            }
        } catch (Throwable ignored) {
        }

        if (mac == null && key == null) {
            return;
        }
        String signature = action + "|" + mac + "|" + key;
        if (signature.equals(lastSignature)) {
            return;
        }
        lastSignature = signature;

        Logx.i("=== 捕获门锁参数(" + action + ") ===");
        Logx.i("{\"macAddress\":\"" + mac + "\",\"activationKey\":\"" + key
                + "\",\"advertisString\":\"" + adv + "\",\"expireTime\":" + expire
                + ",\"seid\":\"" + seid + "\"}");
        Logx.i("=== 结束 ===");

        deliver(mac, key, adv, seid, expire, "VocLockHelperV2." + action);
    }

    private static void handleRawJson(String action, String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return;
        }
        try {
            JSONObject o = new JSONObject(raw);
            String mac = opt(o, "macAddress");
            String key = opt(o, "activationKey");
            String adv = opt(o, "advertisString");
            String seid = opt(o, "seid");
            long expire = o.optLong("expireTime", 0L);
            if (mac == null && key == null) {
                return;
            }
            String signature = action + "|" + mac + "|" + key;
            if (signature.equals(lastSignature)) {
                return;
            }
            lastSignature = signature;

            Logx.i("=== H5 原始门锁参数(" + action + ") ===");
            Logx.i(raw);
            Logx.i("=== 结束 ===");
            deliver(mac, key, adv, seid, expire, "VocLockBridge." + action);
        } catch (Throwable t) {
            Logx.d("handleRawJson 非 JSON，忽略: " + raw);
        }
    }

    private static String opt(JSONObject o, String k) {
        String v = o.optString(k, null);
        return v == null || v.isEmpty() || "null".equals(v) ? null : v;
    }

    // ------------------------------------------------------------------ MvLock 厂商

    private static int hookMvLock(ClassLoader cl) {
        try {
            Class<?> zm = XposedHelpers.findClass(
                    "com.tmall.campus.bizwebview.plugin.ZMUnlockJsBridge", cl);
            Method unlock = XposedHelpers.findMethodExactIfExists(zm, "unlock", String.class, Object.class);
            if (unlock == null) {
                for (Method m : zm.getDeclaredMethods()) {
                    if ("unlock".equals(m.getName()) && m.getParameterTypes().length >= 1
                            && m.getParameterTypes()[0] == String.class) {
                        unlock = m;
                        break;
                    }
                }
            }
            if (unlock == null) {
                Logx.miss("KeyBridge: ZMUnlockJsBridge.unlock 不存在");
                return 0;
            }
            XposedBridge.hookMethod(unlock, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args[0] instanceof String) {
                            JSONObject o = new JSONObject((String) param.args[0]);
                            String mac = opt(o, "macAddress");
                            String sync = opt(o, "syncData");
                            Logx.i("=== MvLock 开门参数 mac=" + mac + " syncDataLen="
                                    + (sync == null ? 0 : sync.length()) + " ===");
                            Logx.i(String.valueOf(param.args[0]));
                            // syncData 是 MvLock 厂商的私有协议负载，原样交给独立 App 存档
                            Bundle b = new Bundle();
                            b.putString("token", TOKEN);
                            b.putString("macAddress", mac);
                            b.putString("syncData", sync);
                            b.putString("source", "ZMUnlockJsBridge");
                            callProvider("saveMv", b);
                        }
                    } catch (Throwable t) {
                        Logx.d("hookMvLock 解析失败: " + t);
                    }
                }
            });
            Logx.ok("KeyBridge: hook ZMUnlockJsBridge.unlock（MvLock 厂商门锁）");
            return 1;
        } catch (Throwable t) {
            Logx.miss("KeyBridge: hookMvLock 跳过: " + t);
            return 0;
        }
    }

    // ------------------------------------------------------------------ 投递

    private static void deliver(String mac, String key, String adv, String seid,
                               long expire, String source) {
        Context ctx = HookEntry.appContext();
        if (ctx == null) {
            Logx.w("拿不到 Context，无法投递给校园钥匙 App（参数已打到日志里）");
            return;
        }

        Bundle b = new Bundle();
        b.putString("token", TOKEN);
        b.putString("macAddress", mac);
        b.putString("activationKey", key);
        b.putString("advertisString", adv);
        b.putString("seid", seid);
        b.putLong("expireTime", expire);
        b.putString("source", source);
        callProvider("save", b);

        try {
            Intent i = new Intent(BROADCAST_ACTION);
            i.putExtras(b);
            i.setPackage(KEY_APP_PKG);
            ctx.sendBroadcast(i);
        } catch (Throwable t) {
            Logx.d("广播参数失败: " + t);
        }
    }

    private static void callProvider(String method, Bundle args) {
        try {
            Context ctx = HookEntry.appContext();
            if (ctx == null) {
                return;
            }
            ContentResolver cr = ctx.getContentResolver();
            Bundle out = cr.call(KEY_PROVIDER, method, null, args);
            if (out != null && out.getBoolean("ok", false)) {
                Logx.i("门锁参数已投递给校园钥匙 App (" + method + ")");
            } else {
                Logx.w("校园钥匙 App 拒绝了参数（未安装？）："
                        + (out == null ? "no result" : out.getString("error")));
            }
        } catch (Throwable t) {
            Logx.w("投递门锁参数失败（独立 App 可能未安装）: " + t);
        }
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o);
        return "null".equals(s) ? null : s;
    }
}
