param(
    [string]$JavaHome = 'C:\Users\Nobodiiiii\.jdks\corretto-1.8.0_492',
    [string]$CompilerHome = 'C:\Program Files\Java\jdk-17.0.15.6-hotspot'
)
$ErrorActionPreference = 'Stop'
$epochRoot = Split-Path -Parent $PSScriptRoot
$epochLibraries = Join-Path $epochRoot '.minecraft/libraries'
$epochOutput = Join-Path $PSScriptRoot 'build'
$epochLaunch = Join-Path $epochLibraries 'net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar'
$epochGson = Join-Path $epochLibraries 'com/google/code/gson/gson/2.8.0/gson-2.8.0.jar'
$epochLogApi = Join-Path $epochLibraries 'org/apache/logging/log4j/log4j-api/2.17.0/log4j-api-2.17.0.jar'
$epochLogCore = Join-Path $epochLibraries 'org/apache/logging/log4j/log4j-core/2.17.0/log4j-core-2.17.0.jar'
$epochAdapters = @(
    (Join-Path $epochRoot 'forge1710/build/libs/epoch-1.7.10-0.3.0.jar'),
    (Join-Path $epochRoot 'forge112/build/libs/epoch-1.12.2-0.3.0.jar')
)
foreach ($epochDependency in (@($epochLaunch, $epochGson, $epochLogApi, $epochLogCore) + $epochAdapters)) {
    if (-not (Test-Path -LiteralPath $epochDependency)) { throw "Missing packaged regression dependency: $epochDependency" }
}
New-Item -ItemType Directory -Force -Path $epochOutput | Out-Null
& (Join-Path $CompilerHome 'bin/javac.exe') --release 8 -encoding UTF-8 -cp $epochLaunch -d $epochOutput (Join-Path $PSScriptRoot 'LaunchWrapperCacheTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Packaged LaunchWrapper tests did not compile' }
$epochClasspath = @($epochOutput, $epochLaunch, $epochLogApi, $epochLogCore) -join ';'
& (Join-Path $JavaHome 'bin/java.exe') "-Depoch.projectRoot=$epochRoot" -cp $epochClasspath LaunchWrapperCacheTest $epochGson @epochAdapters
if ($LASTEXITCODE -ne 0) { throw 'Packaged LaunchWrapper projection cache tests failed' }
