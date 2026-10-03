# Build an Android APK (Xposed module or standalone app) with aapt2 + javac + d8 + apksigner.
# No Gradle required.
#
# Usage: pwsh -File build.ps1 -ProjectDir <dir> [-OutName name.apk] [-AssetsDir <dir>] [-ResDir <dir>]
param(
    [Parameter(Mandatory = $true)][string]$ProjectDir,
    [string]$OutName = "out.apk",
    [string]$AssetsDir = "",
    [string]$ResDir = "",
    [string]$PackageName = ""
)

# Native tools write progress to stderr; keep that from aborting the script and
# rely on $LASTEXITCODE checks after every external command instead.
$ErrorActionPreference = 'Continue'

# Android SDK 路径：优先读环境变量，方便别人在别的机器上构建
$SDK = $env:ANDROID_SDK_ROOT
if (-not $SDK) { $SDK = $env:ANDROID_HOME }
if (-not $SDK) { $SDK = 'D:\Andriod\Sdk' }
$BT          = Join-Path $SDK 'build-tools\36.0.0'
$ANDROID_JAR = Join-Path $SDK 'platforms\android-37.0\android.jar'
$XPOSED_JAR  = Join-Path $PSScriptRoot 'xposed-api.jar'      # 随仓库一起分发
$KEYSTORE    = Join-Path $PSScriptRoot 'campus.keystore'     # 不存在会自动生成（见下）
$KS_PASS     = 'campus123'
$KS_ALIAS    = 'campus'

$AAPT2     = Join-Path $BT 'aapt2.exe'
$D8        = Join-Path $BT 'd8.bat'
$ZIPALIGN  = Join-Path $BT 'zipalign.exe'
$APKSIGNER = Join-Path $BT 'apksigner.bat'
$KEYTOOL   = 'keytool'

$Build   = Join-Path $ProjectDir 'build'
$Classes = Join-Path $Build 'classes'
$Gen     = Join-Path $Build 'gen'
$DexOut  = Join-Path $Build 'dex'
$BaseApk = Join-Path $Build 'base.apk'

Write-Host "== clean =="
if (Test-Path $Build) { Remove-Item -Recurse -Force $Build }
New-Item -ItemType Directory -Force -Path $Build, $Classes, $Gen, $DexOut | Out-Null

# ---------------------------------------------------------------- resources
$ResZip = Join-Path $Build 'res.zip'
$HasRes = $false
if ($ResDir -and (Test-Path $ResDir)) {
    Write-Host "== aapt2 compile =="
    & $AAPT2 compile --dir $ResDir -o $ResZip
    if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }
    $HasRes = $true
}

Write-Host "== aapt2 link =="
$linkArgs = @(
    'link', '-o', $BaseApk,
    '-I', $ANDROID_JAR,
    '--manifest', (Join-Path $ProjectDir 'AndroidManifest.xml'),
    '--java', $Gen,
    '--min-sdk-version', '24',
    '--target-sdk-version', '35',
    '--no-version-vectors'
)
if ($HasRes)           { $linkArgs += @('-R', $ResZip) }
# 注意：这里**故意不用** aapt2 的 -A 传 assets。
# aapt2 在含中文的路径下会拼出 "...\assets." 这种坏路径直接报
# 「系统找不到指定的文件」，而同一个中文路径给 --manifest 却没事 —— 是 -A 特有的 bug。
# 所以 assets 改到下面「add classes.dex」那一步直接注入 zip。
if ($PackageName)      { $linkArgs += @('--rename-manifest-package', $PackageName) }
& $AAPT2 @linkArgs
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

# ---------------------------------------------------------------- sources
Write-Host "== javac =="
$sources = @()
$sources += Get-ChildItem -Recurse -File -Filter '*.java' -Path (Join-Path $ProjectDir 'src') -ErrorAction SilentlyContinue
$sources += Get-ChildItem -Recurse -File -Filter '*.java' -Path $Gen -ErrorAction SilentlyContinue
if ($sources.Count -eq 0) { throw "no java sources" }
$srcPaths = @($sources | ForEach-Object { $_.FullName })

# 直接把文件路径当参数传给 javac，**不要写 @argfile**：
# 1) argfile 的编码取决于 JDK／平台（JDK18+ 才是 UTF-8，老 JDK 用平台编码），
#    项目路径里一旦有中文（如 源码\module\src）就会被写成乱码；
# 2) 直接传参走的是 UTF-16 命令行，任何 JDK 都正确。
# 本项目源码只有十几个文件，离 32KB 命令行上限还很远；真超了再考虑 argfile。
if ($srcPaths.Count -gt 300) {
    Write-Host "  源文件 $($srcPaths.Count) 个，改用 @argfile（UTF-8）"
    $srcList = Join-Path $Build 'sources.txt'
    [System.IO.File]::WriteAllLines($srcList, $srcPaths, [System.Text.UTF8Encoding]::new($false))
    $javacArgs = @("@$srcList")
} else {
    $javacArgs = $srcPaths
}

& javac -encoding UTF-8 -nowarn `
    -source 8 -target 8 `
    -bootclasspath $ANDROID_JAR `
    -cp "$ANDROID_JAR;$XPOSED_JAR" `
    -d $Classes @javacArgs 2>&1 | Where-Object { $_ -notmatch 'obsolete' -and $_ -notmatch 'warning' } | Write-Host
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

# ---------------------------------------------------------------- dex
Write-Host "== d8 =="
$classFiles = Get-ChildItem -Recurse -File -Filter '*.class' -Path $Classes | ForEach-Object { $_.FullName }
& $D8 --release --lib $ANDROID_JAR --min-api 24 --output $DexOut @classFiles
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

# ---------------------------------------------------------------- package
Write-Host "== add classes.dex =="
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($BaseApk, 'Update')
try {
    foreach ($dex in Get-ChildItem -File -Filter 'classes*.dex' -Path $DexOut) {
        $entry = $zip.GetEntry($dex.Name)
        if ($entry) { $entry.Delete() }
        [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $dex.FullName, $dex.Name, [System.IO.Compression.CompressionLevel]::Optimal)
    }

    # ---- 注入 assets（代替 aapt2 的 -A，见上面的说明）----
    if ($AssetsDir -and (Test-Path $AssetsDir)) {
        $assetRoot = (Resolve-Path $AssetsDir).Path.TrimEnd('\', '/')
        $n = 0
        foreach ($f in Get-ChildItem -Recurse -File -Path $assetRoot) {
            $rel = 'assets/' + $f.FullName.Substring($assetRoot.Length).TrimStart('\', '/').Replace('\', '/')
            $e = $zip.GetEntry($rel)
            if ($e) { $e.Delete() }
            [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $f.FullName, $rel, [System.IO.Compression.CompressionLevel]::Optimal)
            $n++
        }
        Write-Host "  注入 assets: $n 个（$rel）"
    }
} finally { $zip.Dispose() }

# ---------------------------------------------------------------- sign
if (-not (Test-Path $KEYSTORE)) {
    Write-Host "== create keystore =="
    New-Item -ItemType Directory -Force -Path (Split-Path $KEYSTORE) | Out-Null
    & $KEYTOOL -genkeypair -v -keystore $KEYSTORE -alias $KS_ALIAS `
        -keyalg RSA -keysize 2048 -validity 10950 `
        -storepass $KS_PASS -keypass $KS_PASS `
        -dname "CN=CampusClean, OU=Dev, O=PureTools, L=NA, S=NA, C=CN" 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}

$Aligned = Join-Path $Build 'aligned.apk'
& $ZIPALIGN -f -p 4 $BaseApk $Aligned
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

$Final = Join-Path $Build $OutName
& $APKSIGNER sign --ks $KEYSTORE --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" `
    --ks-key-alias $KS_ALIAS --v1-signing-enabled true --v2-signing-enabled true `
    --out $Final $Aligned
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

Write-Host ""
Write-Host "BUILD OK -> $Final  ($([math]::Round((Get-Item $Final).Length/1KB,1)) KB)"
& $APKSIGNER verify --print-certs $Final 2>&1 | Select-Object -First 3 | Write-Host
