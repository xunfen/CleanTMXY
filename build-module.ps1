# 一键重建去广告模块（本仓库只含模块）
#
#   powershell -ExecutionPolicy Bypass -File build-module.ps1
#
# 免 Gradle：aapt2 → javac → d8 → zipalign → apksigner
param(
    [string]$SDKPath = ''
)

$ErrorActionPreference = 'Stop'
$Root = $PSScriptRoot
if (-not $Root) { $Root = Split-Path -Parent $MyInvocation.MyCommand.Path }

if ($SDKPath) { $env:ANDROID_SDK_ROOT = $SDKPath }

$Out = Join-Path $Root 'TmxyAdFree.apk'

Write-Host "root = $Root"
Write-Host "== 构建模块 ==" -ForegroundColor Cyan
& powershell -ExecutionPolicy Bypass -File (Join-Path $Root 'build\build.ps1') `
    -ProjectDir (Join-Path $Root '源码') `
    -OutName 'TmxyAdFree.apk' `
    -AssetsDir (Join-Path $Root '源码\assets')
if ($LASTEXITCODE -ne 0) { throw "构建失败" }

$built = Join-Path $Root '源码\build\TmxyAdFree.apk'   # build.ps1 把产物放在 ProjectDir\build 下
if (-not (Test-Path $built)) { throw "没找到构建产物: $built" }

# ---- 断言：必须是合法 Xposed 模块 ----
# 没有 assets/xposed_init 的话 LSPosed 根本不认这个模块（模块列表里看不到，也不注入）。
# 曾经因为 AssetsDir 路径写错，静默产出过这种坏包 —— 所以这里必须卡住。
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($built)
try { $names = @($zip.Entries | ForEach-Object { $_.FullName }) } finally { $zip.Dispose() }
foreach ($n in @('assets/xposed_init', 'classes.dex', 'AndroidManifest.xml')) {
    if ($names -notcontains $n) {
        Write-Host "构建校验失败：产物缺少 $n" -ForegroundColor Red
        throw "产物不是合法 Xposed 模块"
    }
}
$entry = (Get-Content (Join-Path $Root '源码\assets\xposed_init') -Raw).Trim()
Write-Host "[OK] 自检通过：assets/xposed_init = $entry" -ForegroundColor Green

Copy-Item $built $Out -Force
Write-Host ""
Write-Host ("BUILD OK -> {0}  ({1:N1} KB)" -f $Out, ((Get-Item $Out).Length / 1KB)) -ForegroundColor Green
Write-Host ""
Write-Host "装法：LSPosed 里启用本模块 + 勾选作用域「天猫校园」+ 强制停止天猫校园再打开。" -ForegroundColor Yellow
Write-Host "无 root 想打包成单文件 APK：powershell -ExecutionPolicy Bypass -File patch-lspatch.ps1" -ForegroundColor Yellow
Write-Host "（patch-lspatch.ps1 默认读 ..\apk\tmall-campus.apk，按提示传 -OriginalApk 即可）" -ForegroundColor DarkGray