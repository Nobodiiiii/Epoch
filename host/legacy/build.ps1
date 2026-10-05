param(
    [string]$JavaHome = 'C:\Users\Nobodiiiii\.jdks\corretto-1.8.0_492',
    [string]$Gcc = 'C:\mingw64\bin\gcc.exe',
    [switch]$Smoke
)
$ErrorActionPreference = 'Stop'
$buildPath = Join-Path $PSScriptRoot 'build'
$classesPath = Join-Path $buildPath 'classes'
New-Item -ItemType Directory -Path $classesPath -Force | Out-Null
& (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 -d $classesPath (Join-Path $PSScriptRoot 'EpochLegacyAgent.java')
if ($LASTEXITCODE -ne 0) { throw 'Legacy agent compilation failed' }
$manifest = Join-Path $buildPath 'MANIFEST.MF'
[IO.File]::WriteAllText($manifest, "Manifest-Version: 1.0`r`nPremain-Class: dev.epoch.legacy.EpochLegacyAgent`r`n`r`n", [Text.Encoding]::ASCII)
& (Join-Path $JavaHome 'bin/jar.exe') cfm (Join-Path $PSScriptRoot 'epoch-legacy-agent.jar') $manifest -C $classesPath .
if ($LASTEXITCODE -ne 0) { throw 'Legacy agent packaging failed' }
& $Gcc -std=c11 -O2 -Wall -Wextra -shared '-static-libgcc' "-I$JavaHome/include" "-I$JavaHome/include/win32" (Join-Path $PSScriptRoot 'epoch-legacy-window.c') -o (Join-Path $PSScriptRoot 'epoch-legacy-window.dll') -luser32
if ($LASTEXITCODE -ne 0) { throw 'Legacy window guard compilation failed' }
if (-not $Smoke) { return }
$mouseTest = Join-Path $buildPath 'LegacyMouseTest.exe'
& $Gcc -std=c11 -O2 -Wall -Wextra '-static-libgcc' "-I$JavaHome/include" "-I$JavaHome/include/win32" (Join-Path $PSScriptRoot 'LegacyMouseTest.c') -o $mouseTest -luser32
if ($LASTEXITCODE -ne 0) { throw 'Legacy mouse regression compilation failed' }
& $mouseTest
if ($LASTEXITCODE -ne 0) { throw 'Legacy mouse regression failed' }
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$lwjglRoot = Join-Path $projectRoot '.minecraft/libraries/org/lwjgl/lwjgl'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$previousHidden = $env:EPOCH_HIDDEN_START
try {
    $env:EPOCH_HIDDEN_START = '1'
    foreach ($version in @('2.9.1', '2.9.4-nightly-20150209')) {
        $lwjglJar = Join-Path $lwjglRoot "lwjgl/$version/lwjgl-$version.jar"
        $nativesJar = Join-Path $lwjglRoot "lwjgl-platform/$version/lwjgl-platform-$version-natives-windows.jar"
        $smokePath = Join-Path $projectRoot ".minecraft/epoch/native-smoke/$version"
        New-Item -ItemType Directory -Path $smokePath -Force | Out-Null
        $archive = [IO.Compression.ZipFile]::OpenRead($nativesJar)
        try {
            foreach ($entry in $archive.Entries) {
                if ($entry.Name.EndsWith('.dll')) {
                    [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $smokePath $entry.Name), $true)
                }
            }
        } finally { $archive.Dispose() }
        & (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 -cp "$classesPath;$lwjglJar" -d $smokePath (Join-Path $PSScriptRoot 'LegacyBindingSmoke.java')
        if ($LASTEXITCODE -ne 0) { throw "LWJGL $version smoke compilation failed" }
        & (Join-Path $JavaHome 'bin/java.exe') "-javaagent:$(Join-Path $PSScriptRoot 'epoch-legacy-agent.jar')" "-Depoch.legacyNative=$(Join-Path $PSScriptRoot 'epoch-legacy-window.dll')" "-Djava.library.path=$smokePath" "-Dorg.lwjgl.librarypath=$smokePath" -cp "$smokePath;$classesPath;$lwjglJar" dev.epoch.legacy.LegacyBindingSmoke
        if ($LASTEXITCODE -ne 0) { throw "LWJGL $version smoke failed" }
    }
} finally { $env:EPOCH_HIDDEN_START = $previousHidden }
