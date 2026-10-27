param([switch]$Offline)
$ErrorActionPreference = 'Stop'
$EpochRoot = Split-Path -Parent $PSScriptRoot
$env:EPOCH_JAVA17_HOME = (& python (Join-Path $PSScriptRoot 'runtime_config.py') --java-home 17).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Java 17 discovery failed' }
$env:EPOCH_JAVA8_HOME = (& python (Join-Path $PSScriptRoot 'runtime_config.py') --java-home 8).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Java 8 discovery failed' }
$env:JAVA_HOME = $env:EPOCH_JAVA17_HOME
python (Join-Path $PSScriptRoot 'install_journeymap.py') --download-only
if ($LASTEXITCODE -ne 0) { throw 'JourneyMap compile API preparation failed' }
$EpochGradle = Get-ChildItem -LiteralPath "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.8-bin" -Recurse -Filter gradle.bat | Select-Object -First 1 -ExpandProperty FullName
if (-not $EpochGradle) { throw 'Gradle 8.8 is required. Install it or use the module Gradle wrapper.' }
$EpochArgs = @('assemble','--no-daemon','--console=plain','--max-workers=4')
if ($Offline) { $EpochArgs += '--offline' }
foreach ($module in @('forge120','forge118','forge116','forge112','forge1710')) {
    Push-Location (Join-Path $EpochRoot $module)
    try {
        & $EpochGradle @EpochArgs
        if ($LASTEXITCODE -ne 0) { throw "Build failed: $module" }
    } finally { Pop-Location }
}
python (Join-Path $PSScriptRoot 'launch_epoch.py') --build-only
if ($LASTEXITCODE -ne 0) { throw 'Host build failed' }
& (Join-Path $EpochRoot 'host\legacy\build.ps1') -JavaHome $env:EPOCH_JAVA8_HOME
if ($LASTEXITCODE -ne 0) { throw 'Legacy window components failed' }
& (Join-Path $PSScriptRoot 'install_mods.ps1')
