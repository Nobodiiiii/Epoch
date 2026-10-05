param([string]$JavaHome = 'C:\Program Files\Java\jdk-17.0.15.6-hotspot')
$ErrorActionPreference = 'Stop'
$epochRoot = Split-Path -Parent $PSScriptRoot
$gsonJar = Join-Path $epochRoot '.minecraft\libraries\com\google\code\gson\gson\2.8.0\gson-2.8.0.jar'
if (-not (Test-Path -LiteralPath $gsonJar)) { throw "Missing test dependency: $gsonJar" }
$outputDir = Join-Path $PSScriptRoot 'build'
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
$sharedDir = Join-Path $epochRoot 'src\shared\java\dev\epoch\bridge'
& (Join-Path $JavaHome 'bin\javac.exe') --release 8 -encoding UTF-8 -cp $gsonJar -d $outputDir (Join-Path $sharedDir 'ChunkKey.java') (Join-Path $sharedDir 'Snapshot.java') (Join-Path $sharedDir 'WorldExtras.java') (Join-Path $sharedDir 'BiomeCompatibility.java') (Join-Path $sharedDir 'PlayerCompatibility.java') (Join-Path $PSScriptRoot 'WorldExtrasTest.java') (Join-Path $sharedDir 'BridgeFiles.java') (Join-Path $sharedDir 'AutoRuntime.java') (Join-Path $sharedDir 'InventorySlots.java') (Join-Path $PSScriptRoot 'SharedBridgeTest.java') (Join-Path $PSScriptRoot 'AutoRuntimeTest.java') (Join-Path $PSScriptRoot 'InventorySlotsTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Shared tests did not compile' }
& (Join-Path $JavaHome 'bin\java.exe') -cp "$outputDir;$gsonJar" SharedBridgeTest
if ($LASTEXITCODE -ne 0) { throw 'Shared tests failed' }
& (Join-Path $JavaHome 'bin\java.exe') -cp "$outputDir;$gsonJar" AutoRuntimeTest
if ($LASTEXITCODE -ne 0) { throw 'AutoRuntime session and heartbeat tests failed' }
& (Join-Path $JavaHome 'bin\java.exe') -cp "$outputDir;$gsonJar" InventorySlotsTest
if ($LASTEXITCODE -ne 0) { throw 'Player inventory slot tests failed' }
& (Join-Path $JavaHome 'bin\javac.exe') --release 8 -encoding UTF-8 -cp $gsonJar -d $outputDir (Join-Path $sharedDir 'BlockStateCompatibility.java') (Join-Path $sharedDir 'CanonicalBlockState.java') (Join-Path $sharedDir 'CompatibilityRules.java') (Join-Path $sharedDir 'BlockCompatibilityProvider.java') (Join-Path $PSScriptRoot 'BlockStateCompatibilityTest.java') (Join-Path $PSScriptRoot 'CompatibilityRulesTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Block state compatibility tests did not compile' }
& (Join-Path $JavaHome 'bin\java.exe') -cp "$outputDir;$gsonJar;$(Join-Path $epochRoot 'src/shared/resources')" BlockStateCompatibilityTest
if ($LASTEXITCODE -ne 0) { throw 'Block state compatibility tests failed' }

& (Join-Path $JavaHome 'bin/java.exe') -cp "$outputDir;$gsonJar;$(Join-Path $epochRoot 'src/shared/resources')" CompatibilityRulesTest
if ($LASTEXITCODE -ne 0) { throw 'Compatibility rule pack tests failed' }

& (Join-Path $JavaHome 'bin/java.exe') -cp "$outputDir;$gsonJar" WorldExtrasTest
if ($LASTEXITCODE -ne 0) { throw 'World extras tests failed' }

& (Join-Path $JavaHome 'bin/javac.exe') --release 8 -encoding UTF-8 -cp "$outputDir;$gsonJar" -d $outputDir (Join-Path $sharedDir 'ChunkKey.java') (Join-Path $sharedDir 'ChunkSnapshot.java') (Join-Path $sharedDir 'ChunkStore.java') (Join-Path $sharedDir 'ChunkOrigins.java') (Join-Path $sharedDir 'ChunkWriteQueue.java') (Join-Path $sharedDir 'WorldBinding.java') (Join-Path $sharedDir 'StreamCheckpoint.java') (Join-Path $sharedDir 'SavedWorld.java') (Join-Path $sharedDir 'NativeRegion.java') (Join-Path $PSScriptRoot 'NativeRegionTest.java') (Join-Path $sharedDir 'WorldSession.java') (Join-Path $PSScriptRoot 'WorldSessionTest.java') (Join-Path $sharedDir 'SourceChunkRequests.java') (Join-Path $PSScriptRoot 'ChunkStoreTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Chunk authority tests did not compile' }
& (Join-Path $JavaHome 'bin/java.exe') -cp "$outputDir;$gsonJar" dev.epoch.bridge.ChunkStoreTest
if ($LASTEXITCODE -ne 0) { throw 'Chunk authority and recovery tests failed' }

& (Join-Path $JavaHome "bin/java.exe") -cp "$outputDir;$gsonJar" dev.epoch.bridge.WorldSessionTest
if ($LASTEXITCODE -ne 0) { throw "World restart and process ownership tests failed" }

& (Join-Path $JavaHome "bin/java.exe") -cp "$outputDir;$gsonJar" dev.epoch.bridge.NativeRegionTest
if ($LASTEXITCODE -ne 0) { throw "Native region generation-status tests failed" }
