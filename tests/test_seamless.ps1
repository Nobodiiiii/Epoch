param([string]$JavaHome = 'C:\Program Files\Java\jdk-17.0.15.6-hotspot')
$ErrorActionPreference = 'Stop'
$epochRoot = Split-Path -Parent $PSScriptRoot
$epochOutput = Join-Path $PSScriptRoot 'build/seamless'
New-Item -ItemType Directory -Force -Path $epochOutput | Out-Null
$epochGson = Join-Path $epochRoot '.minecraft/libraries/com/google/code/gson/gson/2.8.0/gson-2.8.0.jar'
$epochSources = @('ChunkKey','Snapshot','BlockPreservation','ChunkSnapshot','WorldExtras','SnbtFields','BlockSemantics','BlockContext','BlockPayloads','SignPayloads','SpawnerPayloads') | ForEach-Object { Join-Path $epochRoot "src/shared/java/dev/epoch/bridge/$_.java" }
& "$JavaHome/bin/javac.exe" --release 8 -encoding UTF-8 -cp $epochGson -d $epochOutput $epochSources (Join-Path $PSScriptRoot 'SeamlessBlocksTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Seamless block tests did not compile' }
& "$JavaHome/bin/java.exe" -cp "$epochOutput;$epochGson" SeamlessBlocksTest
if ($LASTEXITCODE -ne 0) { throw 'Seamless block tests failed' }
