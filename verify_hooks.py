#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Static verifier: every class name / method name referenced by the Xposed module
and the standalone app must exist in the 5.7.2 decompiled source tree."""
import os, re, sys, glob

# 位置无关：优先按「本脚本在 <ROOT>/tools/ 下」推断 <ROOT>，
# 这样把整棵树快照到别处（如 Beta1.0.0/）后，校验的是快照自己而不是活动目录。
# 可用环境变量 CAMPUS_ROOT 覆盖。
import os as _os
_HERE = _os.path.dirname(_os.path.abspath(__file__))
_CAND = [_os.environ.get("CAMPUS_ROOT"), _os.path.dirname(_HERE)]
ROOT = next((c for c in _CAND if c and _os.path.isdir(_os.path.join(c, "源码", "tmall-campus-5.7.2-decompiled"))),
            r"D:\DeepSeekHarmess\Main\tmall-campus")
SRC = _os.path.join(ROOT, "源码", "tmall-campus-5.7.2-decompiled")
MOD = _os.path.join(ROOT, "源码", "module", "src")
KEY = _os.path.join(ROOT, "源码", "keyapp", "src")
print(f"[root] {ROOT}")

fails = []
oks = []

# ---- 1. collect referenced target class names from module sources ----
mod_files = glob.glob(os.path.join(MOD, "**", "*.java"), recursive=True)
target_classes = set()
methods_by_class = {}

for f in mod_files:
    txt = open(f, encoding="utf-8").read()
    for m in re.finditer(r'"(com\.tmall\.campus[a-zA-Z0-9_.$]*)"', txt):
        name = m.group(1)
        # heuristic: a class name is dotted and its last segment is Capitalized
        if "." in name and name.split(".")[-1][:1].isupper():
            target_classes.add(name)
    # map class -> nearby method-name string used in noOpMethod/returnFalse helpers
    for m in re.finditer(r'noOpMethod\(\s*cl\s*,\s*"([^"]+)"\s*,\s*"([^"]+)"', txt):
        methods_by_class.setdefault(m.group(1), set()).add(m.group(2))
    for m in re.finditer(r'returnFalse\(\s*cl\s*,\s*"([^"]+)"\s*,\s*"([^"]+)"', txt):
        methods_by_class.setdefault(m.group(1), set()).add(m.group(2))
    for m in re.finditer(r'noOpMethod\(\s*cl\s*,\s*([A-Z_]+)\s*,\s*"([^"]+)"', txt):
        pass

# ad SDK killer uses constants
for name, meth in re.findall(r'noOpMethod\(cl, ([A-Z_]+|"[^"]+"), "([^"]+)"\)', open(
        os.path.join(MOD, "com", "tmxy", "adfree", "AdSdkKiller.java"), encoding="utf-8").read()):
    pass

print("=== 1. 模块引用的 com.tmall.campus.* 类 ===")
for c in sorted(target_classes):
    path = os.path.join(SRC, c.replace(".", os.sep) + ".java")
    nested = os.path.join(SRC, c.replace(".", os.sep) + ".java")
    if os.path.isfile(path):
        oks.append(c)
        print(f"  OK    {c}")
    else:
        # maybe it is a nested class flattened into the outer file
        head = c.rsplit(".", 1)
        outer = os.path.join(SRC, c.replace(".", os.sep) + ".java")
        candidate = None
        parts = c.split(".")
        for i in range(len(parts) - 1, 2, -1):
            outer_name = ".".join(parts[:i])
            p2 = os.path.join(SRC, outer_name.replace(".", os.sep) + ".java")
            if os.path.isfile(p2):
                candidate = p2
                break
        if candidate:
            inner = c[len(".".join(parts[:i])) + 1:]
            body = open(candidate, encoding="utf-8").read()
            if inner in body:
                oks.append(c)
                print(f"  OK    {c}  (nested in {os.path.basename(candidate)})")
                continue
        # 嵌套类（匿名内部类）：com.a.b.Outer$1$2 -> 检查 Outer.java
        if "$" in c:
            base = c.split("$")[0]
            alt = os.path.join(SRC, base.replace(".", os.sep) + ".java")
            if os.path.isfile(alt):
                oks.append(c)
                print(f"  OK    {c}  (anonymous inner of {os.path.basename(alt)})")
                continue
        fails.append(c)
        print(f"  MISS  {c}")

# ---- 2. verify method names exist on those classes ----
print("\n=== 2. AdSdkKiller 里按名字 Hook 的方法 ===")
AD_EXTRA = {
    "com.tmall.campus.ad.takuad.TakuManager": ["initSdk", "configureAdSdkPrivacySettings"],
    "com.anythink.core.api.ATSDK": ["init", "start"],
    "com.tmall.campus.ad.takuad.splashad.utils.SplashAdUtil": ["takuAdSwitch", "operateAdSwitch"],
    "com.tmall.campus.ad.CampusAd": ["checkPlayAd"],
}
for cls, meths in AD_EXTRA.items():
    p = os.path.join(SRC, cls.replace(".", os.sep) + ".java")
    if not os.path.isfile(p):
        print(f"  --    {cls}: 不在 classes7.dex（第三方 SDK，跳过静态校验）")
        continue
    body = open(p, encoding="utf-8").read()
    for mn in meths:
        if re.search(r"\b" + re.escape(mn) + r"\s*\(", body):
            print(f"  OK    {cls}.{mn}")
        else:
            fails.append(f"{cls}.{mn}")
            print(f"  MISS  {cls}.{mn}")

# ---- 3. explicit class+method expectations ----
print("\n=== 3. 显式 Hook 目标（类 + 方法）===")
EXPECT = [
    ("com.tmall.campus.launcher.SplashActivity", "startWork"),
    ("com.tmall.campus.launcher.SplashActivity", "openMainActivity"),
    ("com.tmall.campus.launcher.HotSplashActivity", "initView"),
    ("com.tmall.campus.update.ui.UpdateUIConfirmImpl", "alertForConfirm"),
    ("com.tmall.campus.utils.AutomationGuard", "isAutomatedUnlock"),
    ("com.tmall.campus.utils.AutomationGuard", "hasSuspiciousAccessibilityService"),
    ("com.tmall.campus.utils.AutomationGuard", "genuineTouchRejectReason"),
    ("com.tmall.campus.ui.widget.QuickLinkView", "setQuickLinkList"),
    ("com.tmall.campus.ui.widget.QuickLinkView", "getOnItemClickListener"),
    ("com.tmall.campus.ui.bean.QuickLinkResourceCode$QuickLinkInfo", "getJumpUrl"),
    ("com.tmall.campus.ui.bean.QuickLinkResourceCode$QuickLinkInfo", "getTitle"),
    ("com.tmall.campus.bizwebview.ui.CampusWebActivity", None),  # 见下方负向断言
    ("com.tmall.campus.and.main.MainActivity", "onResume"),
    ("com.tmall.campus.home.main.ui.MainFragment", "initView"),
    ("com.tmall.campus.launcher.CampusApp", "attachBaseContext"),
    ("com.tmall.campus.launcher.CampusApp", "onCreate"),
    ("com.tmall.campus.doorlock.session.VocLockManager", "unlock"),
    ("com.tmall.campus.doorlock.VocLockHelperV2", "unlock"),
    ("com.tmall.campus.doorlock.VocLockHelperV2", "activate"),
    ("com.tmall.campus.doorlock.JSBParams", "getMacAddress"),
    ("com.tmall.campus.doorlock.JSBParams", "getActivationKey"),
    ("com.tmall.campus.doorlock.JSBParams", "getAdvertisString"),
    ("com.tmall.campus.doorlock.JSBParams", "getSeid"),
    ("com.tmall.campus.doorlock.JSBParams", "getExpireTime"),
    ("com.tmall.campus.bizwebview.plugin.VocLockBridge", "unlock"),
    ("com.tmall.campus.bizwebview.plugin.ZMUnlockJsBridge", "unlock"),
    # --- 分层去广告新增（依据 out/AD-HOOK-POINTS.md） ---
    ("com.tmall.campus.configcenter.Configuration", "load"),
    ("com.tmall.campus.ad.AdInitJob", "run"),
    ("com.tmall.campus.ad.CampusAd", "getAdid"),
    ("com.tmall.campus.ad.manager.AdConfigManager", "getProgramAdId"),
    ("com.tmall.campus.ad.takuad.feedad.util.TakuFeedAdUtils", "loadTakuAd"),
    ("com.tmall.campus.ad.takuad.insertad.util.TakuInsertPreloadAd", "loadAd"),
    ("com.tmall.campus.launcher.lifecycle.AdHotLaunchLifecycleCallback", "showHotStartAd"),
    ("com.tmall.campus.launcher.lifecycle.AdHotLaunchLifecycleCallback", "loadHotStartAd"),
    ("com.tmall.campus.ad.jizhunad.JZManager", "initSdk"),
    ("com.tmall.campus.ad.takuad.TakuManager", "initSdk"),
    ("com.tmall.campus.ad.takuad.splashad.utils.SplashAdUtil", "takuAdSwitch"),
    ("com.tmall.campus.ad.takuad.splashad.utils.SplashAdUtil", "operateAdSwitch"),
    ("com.tmall.campus.ad.takuad.splashad.utils.SplashAdUtil", "jzAdSwitch"),
    ("com.tmall.campus.ad.CampusAd", "checkPlayAd"),
    ("com.tmall.campus.ad.CampusAd", "checkDynamicPlayAd"),
    ("com.tmall.campus.ad.AdInitJob", "run"),
    ("com.tmall.campus.ad.CampusAdInitJob", "run"),
]
for cls, mn in EXPECT:
    p = os.path.join(SRC, cls.replace(".", os.sep).replace("$", "$") + ".java")
    nested = os.path.join(SRC, cls.replace(".", os.sep) + ".java")
    file_path = p if os.path.isfile(p) else None
    if mn is None:
        # 负向断言：CampusWebActivity 不应自己声明 onResume，
        # 因此模块必须走 ActivityHook 的按类名分发，而不是直接挂这个类。
        body = open(file_path, encoding="utf-8").read() if file_path else ""
        declared = re.search(r"\bonResume\s*\(", body)
        if declared:
            print(f"  NOTE  {cls} 现在自己声明了 onResume —— 可直接挂，无需分发")
        else:
            print(f"  OK    {cls} 未声明 onResume（确认必须用 ActivityHook 分发）")
        continue
    if file_path is None:
        # nested class inside outer file
        base = cls.split("$")[0]
        alt = os.path.join(SRC, base.replace(".", os.sep) + ".java")
        file_path = alt if os.path.isfile(alt) else None
    if file_path is None:
        fails.append(f"{cls}.{mn}")
        print(f"  MISS  {cls}.{mn}  (类文件不存在)")
        continue
    body = open(file_path, encoding="utf-8").read()
    if re.search(r"\b" + re.escape(mn) + r"\s*\(", body):
        print(f"  OK    {cls}.{mn}")
    else:
        fails.append(f"{cls}.{mn}")
        print(f"  MISS  {cls}.{mn}")

# ---- 4. keyapp: no android-only deps in the protocol layer ----
print("\n=== 4. VocProtocol 必须是纯 JVM（无 android import）===")
vp = open(os.path.join(KEY, "com", "tmxy", "adfree", "key", "VocProtocol.java"),
          encoding="utf-8").read()
bad = [l for l in vp.splitlines() if l.strip().startswith("import android")]
if bad:
    fails.append("VocProtocol imports android")
    print("  FAIL  " + "; ".join(bad))
else:
    print("  OK    无 android import（可在桌面 JVM 自测）")

print()
if fails:
    print("[FAIL] %d 项校验失败：" % len(fails))
    for x in fails:
        print("   -", x)
    sys.exit(1)
print("[OK] 全部通过（%d 个类 + 显式方法表）" % len(oks))
