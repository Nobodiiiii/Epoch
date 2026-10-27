param(
    [string]$JavaHome = '',
    [string]$Gcc = ''
)
$ErrorActionPreference = 'Stop'
if (-not $JavaHome) { $JavaHome = (& python (Join-Path $PSScriptRoot '../../scripts/runtime_config.py') --java-home 8).Trim(); if ($LASTEXITCODE -ne 0) { throw 'Java 8 discovery failed' } }
if (-not $Gcc) { $Gcc = if ($env:EPOCH_GCC) { $env:EPOCH_GCC } else { (Get-Command gcc -ErrorAction Stop).Source } }
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
