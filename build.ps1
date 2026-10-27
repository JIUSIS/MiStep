param(
    [string]$SdkPath = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$NdkVersion = '28.2.13676358',
    [string]$BuildToolsVersion = '37.0.0',
    [string]$KeyStore = $env:MISTEP_KEYSTORE,
    [string]$KeyAlias = $env:MISTEP_KEY_ALIAS,
    [switch]$Unsigned
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
Push-Location $root
try {
if (-not $SdkPath) { $SdkPath = $env:ANDROID_SDK_ROOT }
if (-not $SdkPath -or -not $JavaHome) { throw 'Set ANDROID_HOME and JAVA_HOME, or pass -SdkPath and -JavaHome.' }
$javaBin = Join-Path $JavaHome 'bin'
$bt = Join-Path $SdkPath "build-tools\$BuildToolsVersion"
$android = Join-Path $SdkPath 'platforms\android-37.0\android.jar'
$xposed = Join-Path $root 'libs\xposed-api-82.jar'
$version = Get-Content -LiteralPath (Join-Path $root 'version.json') -Raw | ConvertFrom-Json
$out = Join-Path $root 'build'
$dist = Join-Path $root 'dist'
$src = Join-Path $root 'module\src\main'
$apk = Join-Path $dist "MiStep-$($version.name).apk"
if (Test-Path -LiteralPath $apk) { throw 'Preserve the existing APK. Use a fresh checkout for another build.' }
foreach ($path in @($android,$xposed,(Join-Path $javaBin 'javac.exe'),(Join-Path $bt 'aapt2.exe'))) {
    if (-not (Test-Path -LiteralPath $path)) { throw "Missing build input: $path" }
}
if (-not $Unsigned) {
    if (-not $KeyStore -or -not $KeyAlias -or -not $env:MISTEP_STORE_PASSWORD -or -not $env:MISTEP_KEY_PASSWORD) {
        throw 'Signing requires MISTEP_KEYSTORE, MISTEP_KEY_ALIAS, MISTEP_STORE_PASSWORD and MISTEP_KEY_PASSWORD. Use -Unsigned to build without credentials.'
    }
}
[xml]$manifest = Get-Content -LiteralPath (Join-Path $src 'AndroidManifest.xml') -Raw
$ns = 'http://schemas.android.com/apk/res/android'
if ($manifest.manifest.GetAttribute('versionName',$ns) -ne $version.name -or
    $manifest.manifest.GetAttribute('versionCode',$ns) -ne [string]$version.code) { throw 'Version metadata mismatch.' }
$entry = Get-Content -LiteralPath (Join-Path $src 'java\com\jianguo\mistep\HostEntry.java') -Raw
if ([regex]::Matches($entry,[regex]::Escape($version.runtimeMarker)).Count -ne 2) { throw 'Runtime markers mismatch.' }
New-Item -ItemType Directory -Force -Path $out,$dist,(Join-Path $out 'classes'),(Join-Path $out 'native'),(Join-Path $out 'dex') | Out-Null
$env:JAVA_HOME = $JavaHome
$env:MISTEP_JAVA_BIN = $javaBin
$env:MISTEP_ANDROID_JAR = $android
$env:MISTEP_CLASSES = Join-Path $out 'classes'
foreach ($tool in @('java','javac','javap','jar')) { Set-Item -Path "env:MISTEP_$($tool.ToUpperInvariant())" -Value (Join-Path $javaBin "$tool.exe") }
$sources = @(Get-ChildItem -LiteralPath (Join-Path $src 'java') -Recurse -Filter '*.java' | ForEach-Object FullName)
& (Join-Path $javaBin 'javac.exe') -encoding UTF-8 -nowarn --release 17 -classpath "$android;$xposed" -d $env:MISTEP_CLASSES @sources
if ($LASTEXITCODE -ne 0) { throw 'Full Java source compilation failed.' }
$clang = Join-Path $SdkPath "ndk\$NdkVersion\toolchains\llvm\prebuilt\windows-x86_64\bin\clang++.exe"
if (-not (Test-Path -LiteralPath $clang)) { throw 'Install the documented Android NDK version.' }
$common = @('--target=aarch64-linux-android28','-std=c++17','-O2','-fPIC','-fno-exceptions','-fno-rtti','-fvisibility=hidden','-static-libstdc++','-Wall','-Wextra','-Werror','-Wl,-z,max-page-size=16384')
$native = Join-Path $out 'native\libmistep_raw.so'
& $clang @common -shared (Join-Path $src 'cpp\native_raw_input.cpp') '-Wl,-z,defs' '-Wl,-z,relro,-z,now' -llog -ldl -lm -o $native
if ($LASTEXITCODE -ne 0) { throw 'Native source compilation failed.' }
& $clang @common (Join-Path $root 'tests\native_raw_input_test.cpp') -ldl -lm -o (Join-Path $out 'native\native-raw-selftest')
if ($LASTEXITCODE -ne 0) { throw 'Native selftest source compilation failed.' }
& (Join-Path $bt 'aapt2.exe') compile --dir 'module\src\main\res' -o 'build\resources.zip'
if ($LASTEXITCODE -ne 0) { throw 'Resource compilation failed.' }
$base = Join-Path $out 'base.apk'
& (Join-Path $bt 'aapt2.exe') link -o 'build\base.apk' -I $android --manifest 'module\src\main\AndroidManifest.xml' -A 'module\src\main\assets' --min-sdk-version 24 --target-sdk-version 37 'build\resources.zip'
if ($LASTEXITCODE -ne 0) { throw 'Resource linking failed.' }
$jar = Join-Path $out 'classes.jar'
& (Join-Path $javaBin 'jar.exe') --create --file $jar -C $env:MISTEP_CLASSES .
if ($LASTEXITCODE -ne 0) { throw 'Class archive failed.' }
& (Join-Path $bt 'd8.bat') --min-api 37 --lib $android --lib $xposed --output (Join-Path $out 'dex') $jar
if ($LASTEXITCODE -ne 0) { throw 'DEX compilation failed.' }
$stage = Join-Path $out 'stage'
New-Item -ItemType Directory -Force -Path (Join-Path $stage 'lib\arm64-v8a') | Out-Null
Copy-Item -LiteralPath (Join-Path $out 'dex\classes.dex') -Destination $stage -Force
Copy-Item -LiteralPath $native -Destination (Join-Path $stage 'lib\arm64-v8a\libmistep_raw.so') -Force
$packed = Join-Path $out 'packed.apk'
Copy-Item -LiteralPath $base -Destination $packed -Force
& (Join-Path $javaBin 'jar.exe') --update --file $packed --no-compress -C $stage classes.dex -C $stage lib\arm64-v8a\libmistep_raw.so
if ($LASTEXITCODE -ne 0) { throw 'APK packaging failed.' }
if ($Unsigned) { $apk = Join-Path $dist "MiStep-$($version.name)-unsigned.apk" }
& (Join-Path $bt 'zipalign.exe') -f -P 16 4 $packed $apk
if ($LASTEXITCODE -ne 0) { throw 'APK alignment failed.' }
if (-not $Unsigned) {
    & (Join-Path $bt 'apksigner.bat') sign --ks $KeyStore --ks-key-alias $KeyAlias --ks-pass env:MISTEP_STORE_PASSWORD --key-pass env:MISTEP_KEY_PASSWORD --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled false $apk
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
    & (Join-Path $bt 'apksigner.bat') verify --verbose $apk
    if ($LASTEXITCODE -ne 0) { throw 'Signature verification failed.' }
}
& (Join-Path $bt 'zipalign.exe') -c -P 16 4 $apk
if ($LASTEXITCODE -ne 0) { throw 'Final APK alignment failed.' }
$packaging = & (Join-Path $root 'tools\assert_module_apk.ps1') -Apk $apk -RequireNative
$identity = [ordered]@{version=$version.name; versionCode=$version.code;runtimeMarker=$version.runtimeMarker;sha256=(Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant();bytes=(Get-Item -LiteralPath $apk).Length;nativeSha256=(Get-FileHash -LiteralPath $native -Algorithm SHA256).Hash.ToLowerInvariant();javaSourceCount=$sources.Count;build='Full Java and native source build; no development class cache';startupPackaging=$packaging.startupPackaging;nativePackaging=$packaging.nativePackaging;deviceValidation='Release build not installed; behavior baseline is development 2.9.193.'}
$identity | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $dist 'build-info.json') -Encoding utf8
"$($identity.sha256)  $([IO.Path]::GetFileName($apk))" | Set-Content -LiteralPath (Join-Path $dist 'SHA256SUMS.txt') -Encoding ascii
Write-Output "Built Mi Step $($version.name), versionCode=$($version.code), SHA256=$($identity.sha256)"
} finally { Pop-Location }
