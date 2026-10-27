param([Parameter(Mandatory = $true)][string]$Apk, [switch]$RequireNative)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$apkPath = (Resolve-Path -LiteralPath $Apk).Path
$archive = [IO.Compression.ZipFile]::OpenRead($apkPath)
try {
    foreach ($required in @('AndroidManifest.xml', 'classes.dex', 'assets/xposed_init')) {
        $entries = @($archive.Entries | Where-Object FullName -CEQ $required)
        if ($entries.Count -ne 1 -or $entries[0].Length -eq 0) {
            throw "Module APK requires exactly one nonempty $required"
        }
    }
    $reader = [IO.StreamReader]::new($archive.GetEntry('assets/xposed_init').Open())
    try { $entryClass = $reader.ReadToEnd().Trim() } finally { $reader.Dispose() }
    if ($entryClass -cne 'com.jianguo.mistep.HostEntry') {
        throw "Unexpected Xposed entry: $entryClass"
    }
    $dexEntry = $archive.GetEntry('classes.dex')
    $dexStream = $dexEntry.Open()
    $dexBytes = [IO.MemoryStream]::new()
    try { $dexStream.CopyTo($dexBytes) } finally { $dexStream.Dispose() }
    try {
        if ([Text.Encoding]::ASCII.GetString($dexBytes.ToArray()).IndexOf(
                'Lcom/jianguo/mistep/HostEntry;', [StringComparison]::Ordinal) -lt 0) {
            throw 'The Xposed entry descriptor is missing from classes.dex.'
        }
    } finally { $dexBytes.Dispose() }
    if ($RequireNative) {
        foreach ($name in @('assets/native_init', 'lib/arm64-v8a/libmistep_raw.so')) {
            $entries = @($archive.Entries | Where-Object FullName -CEQ $name)
            if ($entries.Count -ne 1 -or $entries[0].Length -eq 0) {
                throw "Native APK requires exactly one nonempty $name"
            }
        }
        $reader = [IO.StreamReader]::new($archive.GetEntry('assets/native_init').Open())
        try { $nativeEntry = $reader.ReadToEnd().Trim() } finally { $reader.Dispose() }
        if ($nativeEntry -cne 'libmistep_raw.so') { throw 'Unexpected native entry.' }
        $library = $archive.GetEntry('lib/arm64-v8a/libmistep_raw.so')
        if ($library.CompressedLength -ne $library.Length) {
            throw 'Native library must be stored uncompressed for APK loading.'
        }
        $stream = $library.Open()
        $header = [byte[]]::new(20)
        try { $count = $stream.Read($header, 0, $header.Length) } finally { $stream.Dispose() }
        if ($count -ne 20 -or [BitConverter]::ToString($header, 0, 5) -ne '7F-45-4C-46-02' -or
                [BitConverter]::ToUInt16($header, 18) -ne 183) { throw 'Native library must be ELF64 ARM64.' }
    }
    [pscustomobject]@{
        apk = $apkPath
        entryClass = $entryClass
        dexBytes = $dexEntry.Length
        startupPackaging = 'passed'
        nativePackaging = $(if ($RequireNative) { 'passed' } else { 'not requested' })
    }
} finally { $archive.Dispose() }
