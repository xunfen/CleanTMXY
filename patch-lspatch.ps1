# 一键把模块打进天猫校园，产出「免 root 开箱即用」的成品 APK
#
# 原理：LSPatch 系列分支内置【签名绕过】(-l 参数)。阿里 SecurityGuard 的安全图片
# 是用 APK 签名加密的，直接重签名会解不开 → 首页空白 / native 崩溃。
# 而 -l 2 让 PackageManager 和 openat 都返回【原始 APK】，于是 SecurityGuard
# 看到的是阿里的原始签名，安全图片正常解密，一切照常。
#
# 实测环境：天猫校园 5.7.2 / Android 15 (MuMu, x86_64) / LSPatch v1.2 (JingMatrix)
#   验证结论：loadMainPageInfo code=OK、无 SecException、无 AppKey null、无 SIGSEGV、登录正常
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File tools\patch-lspatch.ps1
#   powershell -ExecutionPolicy Bypass -File tools\patch-lspatch.ps1 -BypassLevel 3
param(
    [string]$OriginalApk = '',
    [string]$ModuleApk   = '',
    [string]$OutDir      = '',
    [string]$WorkDir     = '',
    [int]   $BypassLevel = 2,
    [switch]$SkipDownload
)

$ErrorActionPreference = 'Stop'

# 推断项目根：本脚本在 <ROOT>\tools\ 下，所以取上一级。
# $PSScriptRoot 在 -File 调用时一定有；被 dot-source 或 scriptblock 调用时可能为空，
# 所以逐级回退。
$scriptDir = $PSScriptRoot
if (-not $scriptDir) { $scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path }
if (-not $scriptDir) { $scriptDir = (Get-Location).Path }
$Root = Split-Path -Parent $scriptDir
if (-not (Test-Path (Join-Path $Root 'apk\tmall-campus.apk'))) {
    $Root = 'D:\DeepSeekHarmess\Main\tmall-campus'
}
if (-not $OriginalApk) { $OriginalApk = Join-Path $Root 'apk\tmall-campus.apk' }
if (-not $ModuleApk)   { $ModuleApk   = Join-Path $Root 'out\TmxyAdFree.apk' }
if (-not $OutDir)      { $OutDir      = Join-Path $Root 'out' }
if (-not $WorkDir)     { $WorkDir     = Join-Path $Root 'work\lspatch-test' }

# LSPatch CLI（JingMatrix 分支 v1.2）。官方 LSPosed/LSPatch 已归档，其 0.6 版在
# Android 15 上会抛 NoSuchFieldError: AppBindData#compatInfo，直接用不了。
$JarUrl    = 'https://github.com/JingMatrix/LSPatch/releases/download/v1.2/lspatch-v1.2-487-release.jar'
$JarSha256 = 'D238FDC414D121B7FA454D8B4CCF420DF3A8C97D563761861FF92BD9C5DA2165'

$Jar      = Join-Path $WorkDir 'lspatch.jar'
$Keystore = Join-Path $Root 'build\campus.keystore'
$KsPass   = 'campus123'
$KsAlias  = 'campus'

Write-Host "root      = $Root"
Write-Host "原包      = $OriginalApk"
Write-Host "模块      = $ModuleApk"
Write-Host "签名密钥  = $Keystore (alias=$KsAlias)"
Write-Host "绕过等级  = $BypassLevel"
Write-Host ""

foreach ($p in @($OriginalApk, $ModuleApk, $Keystore)) {
    if (-not (Test-Path $p)) { throw "缺少输入文件: $p" }
}
New-Item -ItemType Directory -Force -Path $WorkDir, $OutDir | Out-Null

# ---------------------------------------------------------------- 准备 CLI
if (-not (Test-Path $Jar)) {
    if ($SkipDownload) { throw "没有 $Jar，且指定了 -SkipDownload" }
    Write-Host "== 下载 LSPatch CLI ==" -ForegroundColor Cyan
    # PowerShell 的 TLS 在这台机器上坏过，所以走 Python
    $dl = Join-Path $WorkDir '_dl.py'
    @"
import urllib.request, ssl
ctx = ssl.create_default_context(); ctx.check_hostname = False; ctx.verify_mode = ssl.CERT_NONE
req = urllib.request.Request(r'$JarUrl', headers={'User-Agent': 'Mozilla/5.0'})
with urllib.request.urlopen(req, timeout=180, context=ctx) as r, open(r'$Jar', 'wb') as f:
    while True:
        b = r.read(262144)
        if not b:
            break
        f.write(b)
print('  downloaded')
"@ | Out-File -Encoding utf8 $dl
    & python $dl
    if ($LASTEXITCODE -ne 0) { throw "下载失败" }
}

$actual = (Get-FileHash $Jar -Algorithm SHA256).Hash
if ($actual -ne $JarSha256) {
    Write-Host "警告：lspatch.jar 的 SHA256 与记录不符！" -ForegroundColor Yellow
    Write-Host "  期望 $JarSha256" -ForegroundColor Yellow
    Write-Host "  实际 $actual" -ForegroundColor Yellow
    throw "jar 哈希校验失败（第三方工具可能被替换过，确认无误再改脚本）"
}
Write-Host "[OK] lspatch.jar 哈希校验通过" -ForegroundColor Green

# ---------------------------------------------------------------- 打补丁
Write-Host "== 打补丁 ==" -ForegroundColor Cyan
$sw = [System.Diagnostics.Stopwatch]::StartNew()
& java -jar $Jar $OriginalApk -m $ModuleApk -o $OutDir -l $BypassLevel -k $Keystore $KsPass $KsAlias $KsPass -f
if ($LASTEXITCODE -ne 0) { throw "打补丁失败" }
$sw.Stop()

# ---------------------------------------------------------------- 校验产物
$outApk = Get-ChildItem $OutDir -File -Filter '*lspatched*.apk' |
          Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $outApk) { throw "没找到产物 APK" }

Write-Host ""
Write-Host "== 产物自检 ==" -ForegroundColor Cyan
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($outApk.FullName)
try { $names = @($zip.Entries | ForEach-Object { $_.FullName }) } finally { $zip.Dispose() }

foreach ($n in @('assets/lspatch/loader.dex', 'assets/lspatch/origin.apk')) {
    if ($names -notcontains $n) { throw "产物里缺关键条目: $n" }
    Write-Host "  $n    OK"
}
$mod = @($names | Where-Object { $_ -like 'assets/lspatch/modules/*.apk' })
if ($mod.Count -eq 0) { throw "产物里没嵌入模块" }
Write-Host "  嵌入模块         $($mod -join ', ')"
Write-Host "  签名             $(Split-Path $Keystore -Leaf) (alias=$KsAlias)"
Write-Host ("  大小             {0:N1} MB" -f ($outApk.Length / 1MB))
Write-Host ("  耗时             {0:N1} 秒" -f $sw.Elapsed.TotalSeconds)
Write-Host ""
Write-Host "BUILD OK -> $($outApk.FullName)" -ForegroundColor Green
Write-Host ""
Write-Host "发给同学时告诉他们：" -ForegroundColor Yellow
Write-Host "  1. 先卸载手机上的原版天猫校园（签名不同，不卸载装不上）"
Write-Host "  2. 安装这个 APK"
Write-Host "  3. 直接登录即可，无需 root / LSPosed / 任何框架"