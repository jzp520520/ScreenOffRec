# ============================================================
#  息屏速录 ScreenOffRec - Windows 一键构建脚本（无需 Gradle）
#  依赖：JDK 17（含 keytool）+ Android SDK
#        platforms;android-30  +  build-tools;30.0.3
#  用法：.\scripts\build-windows.ps1 [-SdkPath C:\Android\Sdk]
# ============================================================
param(
    [string]$SdkPath = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$JdkPath = "C:\Program Files\Java\jdk-17",
    [string]$VersionName = "1.5",
    [int]$VersionCode = 6
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$BT   = Join-Path $SdkPath 'build-tools\30.0.3'
$PLAT = Join-Path $SdkPath 'platforms\android-30\android.jar'
foreach ($f in @((Join-Path $BT 'aapt2.exe'), $PLAT, (Join-Path $JdkPath 'bin\javac.exe'))) {
    if (-not (Test-Path $f)) { throw "缺少 $f —— 请用 Android SDK Manager 安装 platforms;android-30 和 build-tools;30.0.3" }
}

$Build = Join-Path $Root 'build'
New-Item -ItemType Directory -Force -Path "$Build\classes","$Build\dex" | Out-Null

Write-Host '[1/6] aapt2 compile' -ForegroundColor Cyan
& "$BT\aapt2.exe" compile --dir (Join-Path $Root 'app\res') -o "$Build\res.zip"

Write-Host '[2/6] aapt2 link' -ForegroundColor Cyan
& "$BT\aapt2.exe" link -o "$Build\base.apk" -I $PLAT `
    --manifest (Join-Path $Root 'app\AndroidManifest.xml') `
    --min-sdk-version 26 --target-sdk-version 30 `
    --version-code $VersionCode --version-name $VersionName `
    "$Build\res.zip"

Write-Host '[3/6] javac' -ForegroundColor Cyan
$src = Get-ChildItem (Join-Path $Root 'app\java') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
cmd /c "`"$JdkPath\bin\javac.exe`" -encoding UTF-8 -J-Duser.language=en -source 8 -target 8 -bootclasspath `"$PLAT;$BT\core-lambda-stubs.jar`" -d `"$Build\classes`" $src"

Write-Host '[4/6] d8 (dex)' -ForegroundColor Cyan
$cls = Get-ChildItem "$Build\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& "$JdkPath\bin\java.exe" -cp "$BT\lib\d8.jar;$BT\lib\shrinkedAndroid.jar" `
    com.android.tools.r8.D8 --release --lib $PLAT --min-api 26 --output "$Build\dex" $cls

Write-Host '[5/6] 打包 classes.dex' -ForegroundColor Cyan
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
$apk = [System.IO.Compression.ZipFile]::Open("$Build\base.apk", 'Update')
$dexBytes = [System.IO.File]::ReadAllBytes("$Build\dex\classes.dex")
$entry = $apk.CreateEntry('classes.dex')
$s = $entry.Open(); $s.Write($dexBytes, 0, $dexBytes.Length); $s.Close()
$apk.Dispose()

Write-Host '[6/6] zipalign + 签名' -ForegroundColor Cyan
& "$BT\zipalign.exe" -f 4 "$Build\base.apk" "$Build\aligned.apk"
$ks = Join-Path $Root 'debug.keystore'
if (-not (Test-Path $ks)) {
    cmd /c "`"$JdkPath\bin\keytool.exe`" -genkeypair -keystore `"$ks`" -alias rec -keyalg RSA -keysize 2048 -validity 10950 -storepass screenoff123 -keypass screenoff123 -dname CN=ScreenOffRec 2>nul"
    Write-Host "已自动生成调试签名库 debug.keystore（发布请换成自己的）"
}
& "$BT\apksigner.bat" sign --ks $ks --ks-key-alias rec --ks-pass pass:screenoff123 --key-pass pass:screenoff123 `
    --out "$Build\ScreenOffRec_v$VersionName.apk" "$Build\aligned.apk"
& "$BT\apksigner.bat" verify "$Build\ScreenOffRec_v$VersionName.apk"

Write-Host "`n完成: $Build\ScreenOffRec_v$VersionName.apk" -ForegroundColor Green
