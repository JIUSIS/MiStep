param([string]$Python = 'python', [string]$SdkPath=$env:ANDROID_HOME, [string]$JavaHome=$env:JAVA_HOME)
$ErrorActionPreference='Stop'
$root=$PSScriptRoot
if (-not $SdkPath) { $SdkPath=$env:ANDROID_SDK_ROOT }
if (-not $JavaHome -or -not $SdkPath) { throw 'Set JAVA_HOME and ANDROID_HOME.' }
$jdk=Join-Path $JavaHome 'bin'
$android=Join-Path $SdkPath 'platforms\android-37.0\android.jar'
$classes=Join-Path $root 'build\classes'
if (-not (Test-Path -LiteralPath $classes)) { throw 'Run build.ps1 first.' }
$env:MISTEP_JAVA_BIN=$jdk
$env:MISTEP_ANDROID_JAR=$android
$env:MISTEP_CLASSES=$classes
foreach($tool in @('java','javac','javap','jar')) { Set-Item -Path "env:MISTEP_$($tool.ToUpperInvariant())" -Value (Join-Path $jdk "$tool.exe") }
$out=Join-Path $root 'build\tests'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$tests=@(Get-ChildItem -LiteralPath (Join-Path $root 'tests') -Filter '*.java' | ForEach-Object FullName)
$cp="$classes;$android;$(Join-Path $root 'libs\xposed-api-82.jar')"
& (Join-Path $jdk 'javac.exe') -encoding UTF-8 --release 17 -d $out (Join-Path $root 'tools\test-stubs\android\graphics\Rect.java')
if($LASTEXITCODE -ne 0){throw 'JVM geometry fake compilation failed.'}
& (Join-Path $jdk 'javac.exe') -encoding UTF-8 --release 17 -cp $cp -d $out @tests
if($LASTEXITCODE -ne 0){throw 'Policy test compilation failed.'}
foreach($test in $tests){
    & (Join-Path $jdk 'java.exe') -cp "$out;$cp" ('com.jiusis.mistep.'+[IO.Path]::GetFileNameWithoutExtension($test))
    if($LASTEXITCODE -ne 0){throw "Policy test failed: $test"}
}
$failedSuites=@()
foreach($suite in @(Get-ChildItem -LiteralPath (Join-Path $root 'tools') -Directory | Where-Object {Test-Path -LiteralPath (Join-Path $_.FullName 'run.py')})){
    & $Python -X utf8 (Join-Path $suite.FullName 'run.py') (Join-Path $root "build\tests\$($suite.Name)")
    if($LASTEXITCODE -ne 0){$failedSuites += $suite.Name; Write-Warning "Production-method test failed: $($suite.Name)"}
}
& (Join-Path $root 'tools\rotation-jvm\run.ps1') -BuildDirectory 'build'
if(-not $?){throw 'Production WMS tests failed.'}
if($failedSuites.Count){throw "Production-method suites failed: $($failedSuites -join ', ')"}
Write-Output "All offline tests passed. Device behavior requires separate validation."
