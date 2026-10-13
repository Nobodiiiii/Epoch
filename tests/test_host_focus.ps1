param([string]$JavaHome = 'C:\Program Files\Java\jdk-17.0.15.6-hotspot')
$ErrorActionPreference = 'Stop'
$EpochFocusRoot = Split-Path -Parent $PSScriptRoot
$EpochFocusLibraries = Join-Path $EpochFocusRoot '.minecraft/libraries'
$EpochFocusClasspath = (@(
    'net/java/dev/jna/jna/5.12.1/jna-5.12.1.jar',
    'net/java/dev/jna/jna-platform/5.12.1/jna-platform-5.12.1.jar',
    'com/google/code/gson/gson/2.10.1/gson-2.10.1.jar'
) | ForEach-Object { Join-Path $EpochFocusLibraries $_ }) -join ';'
$EpochFocusOutput = Join-Path $EpochFocusRoot 'build/host-focus-tests'
New-Item -ItemType Directory -Force -Path $EpochFocusOutput | Out-Null
& (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 -cp $EpochFocusClasspath -d $EpochFocusOutput (Join-Path $EpochFocusRoot 'host/EpochHost.java') (Join-Path $PSScriptRoot 'HostFocusTest.java') (Join-Path $PSScriptRoot 'HostWarmupTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Host focus regression compilation failed' }
& (Join-Path $JavaHome 'bin/java.exe') '-Djava.awt.headless=true' -cp "$EpochFocusOutput;$EpochFocusClasspath" HostFocusTest
if ($LASTEXITCODE -ne 0) { throw 'Host focus regression failed' }
& (Join-Path $JavaHome 'bin/java.exe') '-Djava.awt.headless=true' -cp "$EpochFocusOutput;$EpochFocusClasspath" HostWarmupTest
if ($LASTEXITCODE -ne 0) { throw 'Host warmup regression failed' }
