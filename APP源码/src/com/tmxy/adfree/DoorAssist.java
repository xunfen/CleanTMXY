package com.tmxy.adfree;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.TextView;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 「自动开门」助手：让用户从点开图标到门开、再到退出的全程无需手动操作。
 *
 * <ol>
 *   <li>首页 QuickLink 数据里找「开门」，用 App 自己的点击回调跳进开门 H5</li>
 *   <li>数据级跳转失败时，退化为在首页视图树里找含「开门」的可见 TextView 并点击</li>
 *   <li>进入 CampusWebActivity 后，向 WebView 中心派发一次合成触摸（等价于点「点击开锁」）</li>
 *   <li>监听 {@code VocLockManager.unlock} 的回调，开锁成功后移除整个 App 任务</li>
 * </ol>
 */
public final class DoorAssist {

    private static final Handler UI = new Handler(Looper.getMainLooper());

    private static final long[] QUICKLINK_NAV_DELAYS = {0L, 100L, 300L, 700L, 1500L};
    private static final long CENTER_TAP_INITIAL_DELAY = 2500L;
    private static final long CENTER_TAP_RETRY_INTERVAL = 1200L;
    private static final int CENTER_TAP_MAX_ATTEMPTS = 4;
    private static final long EXIT_AFTER_BLE_SUCCESS_DELAY = 120L;
    private static final long DOOR_FALLBACK_INITIAL_DELAY = 2500L;
    private static final long DOOR_FALLBACK_INTERVAL = 400L;
    private static final int DOOR_FALLBACK_MAX_POLLS = 75;

    private static final String WEB_ACTIVITY = "com.tmall.campus.bizwebview.ui.CampusWebActivity";

    /** 每个 App 进程只自动导航一次，避免从开门页返回首页时立刻又跳进去。 */
    private static final AtomicBoolean doorNavigationTriggered = new AtomicBoolean(false);
    private static final AtomicBoolean centerTapStarted = new AtomicBoolean(false);
    private static final AtomicBoolean unlockSuccessHandled = new AtomicBoolean(false);
    /** 视图降级轮询器同一时刻只允许一个（initView 和 onResume 都会触发）。 */
    private static final AtomicBoolean fallbackPollerRunning = new AtomicBoolean(false);

    private DoorAssist() {}

    /** 冷启动时复位（由 SplashActivity.startWork 的替换逻辑调用）。 */
    public static void resetForColdStart() {
        doorNavigationTriggered.set(false);
        centerTapStarted.set(false);
        unlockSuccessHandled.set(false);
        fallbackPollerRunning.set(false);
    }

    /** @return 成功挂上的 Hook 数量。 */
    public static int install(ClassLoader cl) {
        int n = 0;
        n += hookUnlockResult(cl);
        if (Config.autoEnterDoor) {
            n += hookQuickLinkView(cl);
            n += hookMainFragment(cl);
        } else {
            Logx.i("DoorAssist: autoEnterDoor 关闭，跳过自动进开门页");
        }
        if (Config.autoTapUnlock) {
            n += hookCampusWebActivity(cl);
        } else {
            Logx.i("DoorAssist: autoTapUnlock 关闭，跳过自动点击开锁");
        }
        return n;
    }

    // ------------------------------------------------------------------ 开锁结果

    /**
     * 直接包住 {@code VocLockManager.unlock(ctx, JSBParams, UnlockCallback)} 的第三个参数，
     * 这样无论 UI 走的是 WebView 提示还是蓝牙回调，只要原生层报成功就能第一时间收到。
     */
    private static int hookUnlockResult(ClassLoader cl) {
        try {
            final Class<?> callbackClass = XposedHelpers.findClass(
                    "com.tmall.campus.doorlock.UnlockCallback", cl);
            Class<?> managerClass = XposedHelpers.findClass(
                    "com.tmall.campus.doorlock.session.VocLockManager", cl);

            Method unlock = null;
            for (Method m : managerClass.getDeclaredMethods()) {
                if ("unlock".equals(m.getName()) && m.getParameterTypes().length == 3) {
                    unlock = m;
                    break;
                }
            }
            if (unlock == null) {
                Logx.miss("DoorAssist: VocLockManager.unlock 不存在");
                return 0;
            }

            XposedBridge.hookMethod(unlock, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object original = param.args[2];
                        if (original == null || Proxy.isProxyClass(original.getClass())) {
                            return;
                        }
                        param.args[2] = Proxy.newProxyInstance(
                                callbackClass.getClassLoader(),
                                new Class<?>[]{callbackClass},
                                new CallbackProxy(original));
                        Logx.i("已包装 UnlockCallback，监听开锁结果");
                    } catch (Throwable t) {
                        Logx.e("包装 UnlockCallback 失败", t);
                    }
                }
            });
            Logx.ok("DoorAssist: hook VocLockManager.unlock（开锁结果监听）");
            return 1;
        } catch (Throwable t) {
            Logx.miss("DoorAssist: hookUnlockResult 跳过: " + t);
            return 0;
        }
    }

    private static final class CallbackProxy implements InvocationHandler {

        private final Object original;

        CallbackProxy(Object original) {
            this.original = original;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            try {
                if ("onUnlockSuccess".equals(name)) {
                    Logx.i("检测到蓝牙开锁成功");
                    onUnlockSuccess();
                } else if ("onUnlockFailed".equals(name)) {
                    Logx.w("开锁失败: " + (args != null && args.length > 1 ? args[1] : "?"));
                }
                if (original != null) {
                    return method.invoke(original, args);
                }
            } catch (Throwable t) {
                Logx.e("转发 " + name + " 失败", t);
            }
            return null;
        }
    }

    private static void onUnlockSuccess() {
        if (!Config.exitAfterUnlock) {
            return;
        }
        if (!unlockSuccessHandled.compareAndSet(false, true)) {
            return;
        }
        UI.postDelayed(new Runnable() {
            @Override public void run() {
                closeAppTasks();
            }
        }, EXIT_AFTER_BLE_SUCCESS_DELAY);
    }

    private static void closeAppTasks() {
        try {
            Context ctx = HookEntry.appContext();
            if (ctx == null) {
                return;
            }
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                return;
            }
            int n = 0;
            for (ActivityManager.AppTask task : am.getAppTasks()) {
                task.finishAndRemoveTask();
                n++;
            }
            Logx.i("开锁成功，已移除 App 任务数=" + n);
        } catch (Throwable t) {
            Logx.e("closeAppTasks 失败", t);
        }
    }

    // ------------------------------------------------------------------ QuickLink

    private static int hookQuickLinkView(ClassLoader cl) {
        try {
            Class<?> quickLink = XposedHelpers.findClass("com.tmall.campus.ui.widget.QuickLinkView", cl);
            Method target = null;
            for (Method m : quickLink.getDeclaredMethods()) {
                if ("setQuickLinkList".equals(m.getName()) && m.getParameterTypes().length == 4) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                Logx.miss("DoorAssist: QuickLinkView.setQuickLinkList 不存在");
                return 0;
            }
            XposedBridge.hookMethod(target, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    final Object view = param.thisObject;
                    Object listArg = param.args[0];
                    if (!(listArg instanceof List)) {
                        return;
                    }
                    final List<?> items = (List<?>) listArg;
                    for (long delay : QUICKLINK_NAV_DELAYS) {
                        UI.postDelayed(new Runnable() {
                            @Override public void run() {
                                tryNavigateFromQuickLink(view, items);
                            }
                        }, delay);
                    }
                }
            });
            Logx.ok("DoorAssist: hook QuickLinkView.setQuickLinkList（自动进入开门页）");
            return 1;
        } catch (Throwable t) {
            Logx.miss("DoorAssist: hookQuickLinkView 跳过: " + t);
            return 0;
        }
    }

    private static void tryNavigateFromQuickLink(Object view, List<?> items) {
        if (doorNavigationTriggered.get()) {
            return;
        }
        try {
            Object listener = XposedHelpers.callMethod(view, "getOnItemClickListener");
            if (listener == null) {
                return;
            }
            for (Object item : items) {
                if (item == null) {
                    continue;
                }
                String title = String.valueOf(XposedHelpers.callMethod(item, "getTitle"));
                if (title == null || !title.contains("开门")) {
                    continue;
                }
                String jumpUrl = String.valueOf(XposedHelpers.callMethod(item, "getJumpUrl"));
                if (jumpUrl == null || jumpUrl.isEmpty() || "null".equals(jumpUrl)) {
                    continue;
                }
                if (doorNavigationTriggered.compareAndSet(false, true)) {
                    XposedHelpers.callMethod(listener, "invoke", jumpUrl);
                    Logx.i("已从 QuickLink 进入开门页: " + title);
                }
                return;
            }
        } catch (Throwable t) {
            Logx.e("tryNavigateFromQuickLink 失败", t);
        }
    }

    // ------------------------------------------------------------------ 视图降级

    private static int hookMainFragment(ClassLoader cl) {
        try {
            Class<?> fragment = XposedHelpers.findClass("com.tmall.campus.home.main.ui.MainFragment", cl);
            // MainFragment 的真实签名是 initView(View)（MainFragment.java:314）与 onResume()（:2214），
            // 两个都挂上，避免只看无参 initView 而漏掉。
            int n = 0;
            for (Method m : fragment.getDeclaredMethods()) {
                String name = m.getName();
                if (!"initView".equals(name) && !"onResume".equals(name)) {
                    continue;
                }
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        final Object fragmentObj = param.thisObject;
                        UI.postDelayed(new Runnable() {
                            @Override public void run() {
                                startDoorFallbackPolling(fragmentObj);
                            }
                        }, DOOR_FALLBACK_INITIAL_DELAY);
                    }
                });
                n++;
            }
            if (n == 0) {
                Logx.miss("DoorAssist: MainFragment.initView/onResume 不存在");
                return 0;
            }
            Logx.ok("DoorAssist: hook MainFragment x" + n + "（开门页降级轮询）");
            return n;
        } catch (Throwable t) {
            Logx.miss("DoorAssist: hookMainFragment 跳过: " + t);
            return 0;
        }
    }

    private static void startDoorFallbackPolling(final Object fragmentObj) {
        if (doorNavigationTriggered.get()) {
            return;
        }
        // MainFragment 的 initView 与 onResume 都会调到这里；不加闸门的话
        // 每次页面 resume 都会多起一个 30 秒的轮询器，越堆越多。
        if (!fallbackPollerRunning.compareAndSet(false, true)) {
            return;
        }
        final int[] polls = {0};
        Runnable r = new Runnable() {
            @Override public void run() {
                if (doorNavigationTriggered.get() || polls[0]++ >= DOOR_FALLBACK_MAX_POLLS) {
                    fallbackPollerRunning.set(false);
                    return;
                }
                try {
                    View root = (View) XposedHelpers.callMethod(fragmentObj, "getView");
                    if (root != null) {
                        TextView hit = findDoorTextView(root, 0);
                        if (hit != null) {
                            if (doorNavigationTriggered.compareAndSet(false, true)) {
                                Logx.i("已通过视图降级点击进入开门页: " + hit.getText());
                                if (!hit.performClick()) {
                                    View clickable = findClickableAncestor(hit);
                                    if (clickable != null) {
                                        clickable.performClick();
                                    } else {
                                        synthesizeTap(hit);
                                    }
                                }
                            }
                            fallbackPollerRunning.set(false);
                            return;
                        }
                    }
                } catch (Throwable t) {
                    Logx.d("door fallback poll: " + t);
                }
                UI.postDelayed(this, DOOR_FALLBACK_INTERVAL);
            }
        };
        UI.postDelayed(r, 0L);
    }

    private static TextView findDoorTextView(View v, int depth) {
        if (v == null || depth > 30) {
            return null;
        }
        if (v instanceof TextView && v.getVisibility() == View.VISIBLE && v.isEnabled()) {
            CharSequence text = ((TextView) v).getText();
            if (text != null) {
                String s = text.toString();
                if (s.contains("开门") || s.contains("开锁")) {
                    return (TextView) v;
                }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView r = findDoorTextView(g.getChildAt(i), depth + 1);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    private static View findClickableAncestor(View v) {
        View cur = v;
        for (int i = 0; i < 6 && cur != null; i++) {
            if (cur.isClickable()) {
                return cur;
            }
            cur = cur.getParent() instanceof View ? (View) cur.getParent() : null;
        }
        return null;
    }

    // ------------------------------------------------------------------ 开门页

    private static int hookCampusWebActivity(ClassLoader cl) {
        if (!ActivityHook.install(cl)) {
            return 0;
        }
        ActivityHook.addListener(new ActivityHook.Listener() {
            @Override
            public void onActivityResumed(final Activity activity) {
                if (!WEB_ACTIVITY.equals(activity.getClass().getName())) {
                    return;
                }
                // 安全阀：只有「我们主动跳进来的开门页」或「URL 看着就是门禁页」才自动点，
                // 否则会把洗烘、缴费等其它 H5 页面的中心按钮也点掉。
                boolean doorPage = doorNavigationTriggered.get() || looksLikeDoorPage(activity);
                if (!doorPage) {
                    Logx.d("CampusWebActivity 不是开门页，跳过自动点击");
                    return;
                }
                if (!centerTapStarted.compareAndSet(false, true)) {
                    return;
                }
                UI.postDelayed(new Runnable() {
                    @Override public void run() {
                        tapUnlock(activity, 1);
                    }
                }, CENTER_TAP_INITIAL_DELAY);
            }
        });
        Logx.ok("DoorAssist: 已注册自动点击开锁（仅限开门页）");
        return 1;
    }

    /** 通过 WebView 当前 URL 判断是不是门禁页。 */
    private static boolean looksLikeDoorPage(Activity activity) {
        try {
            WebView wv = findWebView(activity.getWindow().getDecorView(), 0);
            String url = wv == null ? null : wv.getUrl();
            if (url == null) {
                return false;
            }
            String u = url.toLowerCase(java.util.Locale.ROOT);
            return u.contains("door") || u.contains("lock") || u.contains("opendoor")
                    || u.contains("access") || u.contains("menjin") || u.contains("kaimen");
        } catch (Throwable t) {
            return false;
        }
    }

    private static void tapUnlock(final Activity activity, final int attempt) {
        if (unlockSuccessHandled.get()) {
            return;
        }
        try {
            View decor = activity.getWindow().getDecorView();
            WebView web = findWebView(decor, 0);
            View target = web != null ? web : decor;
            int w = target.getWidth();
            int h = target.getHeight();
            if (w > 0 && h > 0) {
                boolean handled = synthesizeTapAt(target, w / 2f, h / 2f);
                Logx.i("开锁中心点击 attempt=" + attempt + " handled=" + handled
                        + (web != null ? " (WebView)" : " (DecorView)"));
            } else {
                Logx.d("开锁中心点击 attempt=" + attempt + " 视图尚未布局");
            }
        } catch (Throwable t) {
            Logx.e("tapUnlock 失败", t);
        }
        if (attempt < CENTER_TAP_MAX_ATTEMPTS && !unlockSuccessHandled.get()) {
            UI.postDelayed(new Runnable() {
                @Override public void run() {
                    tapUnlock(activity, attempt + 1);
                }
            }, CENTER_TAP_RETRY_INTERVAL);
        }
    }

    private static WebView findWebView(View v, int depth) {
        if (v == null || depth > 30) {
            return null;
        }
        if (v instanceof WebView) {
            return (WebView) v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                WebView r = findWebView(g.getChildAt(i), depth + 1);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    private static boolean synthesizeTapAt(View target, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 40L, MotionEvent.ACTION_UP, x, y, 0);
        try {
            boolean handled = target.dispatchTouchEvent(down);
            handled |= target.dispatchTouchEvent(up);
            return handled;
        } finally {
            down.recycle();
            up.recycle();
        }
    }

    private static void synthesizeTap(View v) {
        synthesizeTapAt(v, v.getWidth() / 2f, v.getHeight() / 2f);
    }

    private static Method findNoArg(Class<?> clazz, String name) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (name.equals(m.getName()) && m.getParameterTypes().length == 0) {
                return m;
            }
        }
        Class<?> sup = clazz.getSuperclass();
        return sup != null && sup != Object.class ? findNoArg(sup, name) : null;
    }
}
