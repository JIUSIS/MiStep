param([string]$BuildDirectory = 'build')
$ErrorActionPreference = 'Stop'
$module = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$jdk = $env:MISTEP_JAVA_BIN
$android = $env:MISTEP_ANDROID_JAR
$classes = Join-Path $module "$BuildDirectory\classes"
if (-not (Test-Path -LiteralPath $classes)) { throw 'Build the APK first.' }
$xposed = Join-Path $module 'libs\xposed-api-82.jar'
$out = Join-Path $module "$BuildDirectory\rotation-jvm"
New-Item -ItemType Directory -Force -Path $out | Out-Null
$sources = @(Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.java' |
    ForEach-Object FullName)
& (Join-Path $jdk 'javac.exe') -encoding UTF-8 --release 17 `
    -cp "$classes;$android;$xposed" -d $out @sources
if ($LASTEXITCODE -ne 0) { throw 'Rotation bridge test compilation failed.' }
& (Join-Path $jdk 'java.exe') -cp "$out;$classes;$android;$xposed" `
    com.jianguo.mistep.TaskRotationBridgeTest
if ($LASTEXITCODE -ne 0) { throw 'Rotation bridge regression failed.' }
& (Join-Path $jdk 'java.exe') -cp "$out;$classes;$android;$xposed" `
    com.jianguo.mistep.NativeFreeformLayerBridgeTest
if ($LASTEXITCODE -ne 0) { throw 'Native freeform layer bridge regression failed.' }
& (Join-Path $jdk 'java.exe') -cp "$out;$classes;$android;$xposed" `
    com.jianguo.mistep.LiveFinishSnapshotTest
if ($LASTEXITCODE -ne 0) { throw 'Live finish magnification snapshot regression failed.' }
