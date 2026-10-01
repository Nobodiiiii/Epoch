param([string]$JavaHome = 'C:\Program Files\Java\jdk-17.0.15.6-hotspot')
$ErrorActionPreference = 'Stop'
$epochRoot = Split-Path -Parent $PSScriptRoot
$gsonJar = Join-Path $epochRoot '.minecraft\libraries\com\google\code\gson\gson\2.8.0\gson-2.8.0.jar'
if (-not (Test-Path -LiteralPath $gsonJar)) { throw "Missing test dependency: $gsonJar" }
$outputDir = Join-Path $PSScriptRoot 'build'
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
$sharedDir = Join-Path $epochRoot 'src\shared\java\dev\epoch\bridge'
& (Join-Path $JavaHome 'bin\javac.exe') --release 8 -encoding UTF-8 -cp $gsonJar -d $outputDir (Join-Path $sharedDir 'Snapshot.java') (Join-Path $sharedDir 'BridgeFiles.java') (Join-Path $sharedDir 'AutoRuntime.java') (Join-Path $sharedDir 'InventorySlots.java') (Join-Path $PSScriptRoot 'SharedBridgeTest.java') (Join-Path $PSScriptRoot 'AutoRuntimeTest.java') (Join-Path $PSScriptRoot 'InventorySlotsTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Shared tests did not compile' }
& (Join-Path $JavaHome 'bin\java.exe') -cp "$outputDir;$gsonJar" SharedBridgeTest
if ($LASTEXITCODE -ne 0) { throw 'Shared tests failed' }
& (Join-Path $JavaHome 'bin\java.exe') -cp "$outputDir;$gsonJar" AutoRuntimeTest
if ($LASTEXITCODE -ne 0) { throw 'AutoRuntime session and heartbeat tests failed' }
& (Join-Path $JavaHome 'bin\java.exe') -cp "$outputDir;$gsonJar" InventorySlotsTest
if ($LASTEXITCODE -ne 0) { throw 'Player inventory slot tests failed' }
