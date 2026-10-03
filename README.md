# CleanTMXY —— 天猫校园去广告模块

> ## 🆓 免费开源 · 谨防被骗
>
> 本项目**完全免费、开源**，唯一发布地址：**<https://github.com/xunfen/CleanTMXY>**
>
> **如果你是通过付费购买得到的，那你被骗了。** 本项目没有任何收费版本，也不会通过任何渠道出售。
> **本模块仅供学习交流，严禁用于商业用途，请与24小时内删除**

针对 **天猫校园 `com.tmall.campus.and` 5.7.2**（versionCode 50007002）的 Xposed 去广告模块。

| | |
|---|---|
| 模块包名 | `com.tmxy.adfree` |
| 版本 | 1.0.8（versionCode 9） |
| 下载 | **[去 Releases 下载 TmxyAdFree.apk](https://github.com/xunfen/CleanTMXY/releases/latest)**（36 KB） |
| 作用域 | `com.tmall.campus.and` |

---

## 做了什么

- **开屏广告**：冷启动 2 秒直接进首页，热启动广告同样跳过
- **第三方广告 SDK 完全不启动**：TopOn(Taku) / 穿山甲 / 优量汇 / 快手 / 百度 / 美数 / 倍孜 / 章鱼 / UBiX 等
- **首页精简**：去掉整块淘宝客商品流（「9.9元秒杀」那排导购 tab + 商品网格）、会员推广卡、小红花卡、AI 悬浮入口
- **屏蔽强制更新弹窗**
- **关闭摇一摇广告**（精准挂 App 自己的开关，不动系统传感器）
- 顺带：绕过 5.7.2 新增的「自动化开锁防护」`AutomationGuard`，以及门锁参数导出（详见设置界面）

---

## 安装

**先下载 APK：** 到 **[Releases](https://github.com/xunfen/CleanTMXY/releases/latest)** 下载 `TmxyAdFree.apk`。
本仓库只存源码，APK 由 Release 分发。

### 方式一：有 root（LSPosed）

```
1. 装 LSPosed（Magisk / KernelSU）
2. 安装 TmxyAdFree.apk
3. LSPosed 里【启用模块】+【勾选作用域「天猫校园」】
4. 强制停止天猫校园，再打开
```

验证是否生效：

```bash
adb logcat -s TmxyAdFree:I
```

应看到：

```
TmxyAdFree v1.0.7 已加载: package=com.tmall.campus.and
自检汇总: adConfig=10 adSdk=18 adblock=5 antiAuto=3 door=5 key=11
已跳过开屏广告 -> openMainActivity()
```

**任何一组是 `0`，说明该类在你那版 APK 里找不到**（多半是版本不匹配，本模块针对 5.7.2）。

### 方式二：无 root（打成单文件 APK）

```powershell
powershell -ExecutionPolicy Bypass -File patch-lspatch.ps1 -OriginalApk <原版天猫校园.apk>
```

产出约 119 MB 的补丁包，用你自己的密钥签名。发给别人时告诉他：

```
1. 先卸载手机上的原版天猫校园（签名不同，不卸载装不上）
2. 安装这个 APK
3. 直接登录即可，无需 root / LSPosed / 任何框架
```

> ⚠️ 天猫校园**每次更新都要重新打**（versionCode 变了，旧补丁包对应旧版本）。
>
> ⚠️ 原理是 LSPatch 系列分支的**签名绕过**（`-l 2`）。阿里 SecurityGuard 的「安全图片」用 APK 签名加密，
> 直接重签名会导致 `SecException` → 首页空白 → native 崩溃；签名绕过让 SecurityGuard 看到原始签名，一切照常。
>
> ⚠️ 官方 `LSPosed/LSPatch` **已停更**（0.6，2023），在 Android 15 上会抛
> `NoSuchFieldError: AppBindData#compatInfo` 直接闪退 —— 这是 LSPatch 自身的系统兼容性问题，
> **跟天猫校园、跟签名都无关，别误判成"被检测了"**。请用活跃分支
> [JingMatrix/LSPatch](https://github.com/JingMatrix/LSPatch) v1.2 或
> [NPatch](https://github.com/7723mod/NPatch)。

---

## 工作原理（分层 Hook）

| 层 | 切入口 | 拦的是什么 |
|---|---|---|
| L1 | `configcenter.Configuration.load` 覆盖 10 个开关 | 全局广告与自动化配置 |
| L2~L4 | `AdInitJob` / `CampusAdInitJob` / `TakuManager.initSdk` / `JZManager.initSdk` | 广告 SDK 初始化 |
| L5 | `CampusAd.getAdid` → null | **总闸门**（27 个调用点全部 null 检查） |
| L6 | `SplashAdManager` / `AdConfigManager.getProgramAdId` → null | 广告位获取 |
| L7 | `TakuFeedAdUtils.loadTakuAd` / `TakuInsertPreloadAd.loadAd` → null | 信息流广告加载口 |
| L8 | `SplashActivity.startWork` / `HotSplashActivity.initView` 整体替换 | 冷 / 热启动开屏 |
| L9 | 按 id **深度遍历视图树**隐藏区块 + `AIAssistantService.getFloatingEntryView` → null | 首页推广区块、AI 悬浮入口 |

> **关键区分**：L1~L8 拦的是**第三方广告 SDK**。
> 首页那片「9.9元秒杀」商品网格是**淘宝客 CPS 业务流**（走阿里妈妈接口），广告 SDK 那套 Hook 够不着 ——
> 它由 L9 的「首页精简」按 id 整块隐藏。
>
> 判断依据：`CommodityInfo` 带 `promotionAmount` / `impressionTrackingUrls` / `sessionId`，
> 是阿里妈妈推广位的标准字段。

---

## 目录

```
├── 源码/
│   ├── AndroidManifest.xml
│   ├── assets/xposed_init    模块入口：com.tmxy.adfree.HookEntry
│   └── src/com/tmxy/adfree/  11 个类
├── build/
│   ├── build.ps1             免 Gradle 构建（aapt2→javac→d8→zipalign→apksigner）
│   └── xposed-api.jar        legacy Xposed API（构建必需）
├── build-module.ps1          一键重建模块 + 产物断言
├── patch-lspatch.ps1         无 root 打包（自动下载 LSPatch CLI 并校验 SHA256）
├── verify_hooks.py           静态校验：模块引用的类/方法逐个对照反编译源码
├── check_ids.py              资源 id 全量比对（2227 个）
└── download.py               下载辅助（本机 PowerShell TLS 有问题，故走 Python）
```

> **APK 不在仓库里**，请到 [Releases](https://github.com/xunfen/CleanTMXY/releases/latest) 下载。
> 仓库只存源码；`build/` 是构建输出目录，已在 `.gitignore` 中排除。

**读代码建议顺序**：`HookEntry` → `AdSdkKiller` → `AdBlock` → `AdConfigBlock`。

---

## 自行构建

```powershell
powershell -ExecutionPolicy Bypass -File build-module.ps1
```

依赖：

- JDK 8+（开发用 JDK 24）
- Android SDK：`platforms/android-37.0`、`build-tools/36.0.0`
  （路径优先读环境变量 `ANDROID_SDK_ROOT` / `ANDROID_HOME`，否则用 `D:\Andriod\Sdk`）
- 签名：`build/campus.keystore` 不存在会**自动生成**（所以本仓库不包含私钥）

脚本会在构建后**断言产物含 `assets/xposed_init`** —— 没有它 LSPosed 根本不认这个模块（列表里看不到、也不注入）。

### 校验

`verify_hooks.py` 与 `check_ids.py` 需要**反编译源码树**（本仓库未包含，因为它是对天猫校园的衍生作品）。
自备 5.7.2 原版 APK 后用 jadx 导出，然后：

```powershell
$env:CAMPUS_ROOT = '<你的项目根>'
python verify_hooks.py    # 应输出：[OK] 全部通过（29 个类 + 显式方法表）
python check_ids.py       # 应输出：命中 19 / 缺失 0
```

### 踩过的坑（省得你再踩）

1. **`.ps1` 必须带 UTF-8 BOM** —— 路径含中文时 PowerShell 5.1 按 ANSI 解码，中文注释变乱码会报「字符串缺少终止符」
2. **`.java` 绝不能带 BOM** —— javac 报 `非法字符: '\ufeff'`
3. **`javac` 不要用 `@argfile`** 传含中文的源文件列表（编码随 JDK 版本变），直接传路径
4. **aapt2 的 `-A` 在中文路径下会失败**（内部拼出 `assets.` 这种坏路径），改成构建后直接注入 zip

---

## 已验证 / 未验证

### ✅ 已真机验证（Android 15 / MuMu x86_64）

- 模块注入 main / `:channel` / `:pushservice` 三进程；自检**无一条「没挂上」**
- **开屏广告消失**：冷启动 2 秒直接进首页
- **广告 SDK 未启动**：`ATSDK` / `pangle` / `TTAdSdk` / `GDTAdSdk` / `KsAdSDK` / `BaiduMobAd` 全部 0 次；无广告域名请求
- 无 `FATAL EXCEPTION`、无 Xposed 报错
- **无 root 补丁包**：`loadMainPageInfo code=OK`、无 `SecException`、无 native SIGSEGV、**登录正常**
- 静态校验：29 个类 + 显式方法表全过；2227 个资源 id 中模块用的 19 个 100% 命中

### ❌ 未验证

- **只在 Android 15 / x86_64 上验证过**，其他 Android 版本与真机 arm64 没测
- **只在 5.7.2 上验证过**，其他版本会有部分 Hook 匹配不上（会记 `miss` 跳过，不会崩）
- 底部导航裁剪功能**未完成**，默认关闭（`trim_bottom_tab=false`）。根因已定位但没调通

---

## 说明

本仓库**不含天猫校园的任何原始文件**。所有结论均可通过「自备 5.7.2 原版 APK + 运行校验脚本」独立复现 ——
脚本会逐条对照反编译源码。

本项目仅供**学习与逆向技术研究**，请勿用于任何违反服务条款或法律的用途。