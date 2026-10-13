param([switch]$Offline)
$ErrorActionPreference = 'Stop'
$EpochRoot = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.15.6-hotspot'
python (Join-Path $PSScriptRoot 'install_journeymap.py') --download-only
if ($LASTEXITCODE -ne 0) { throw 'JourneyMap compile API preparation failed' }
$EpochGradle = Get-ChildItem -LiteralPath "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.8-bin" -Recurse -Filter gradle.bat | Select-Object -First 1 -ExpandProperty FullName
if (-not $EpochGradle) { throw 'Gradle 8.8 is required. Install it or use the module Gradle wrapper.' }
$EpochArgs = @('build','--no-daemon','--console=plain','--max-workers=4')
if ($Offline) { $EpochArgs += '--offline' }
foreach ($module in @('forge120','forge118','forge116','forge112','forge1710')) {
    Push-Location (Join-Path $EpochRoot $module)
    try {
        & $EpochGradle @EpochArgs
        if ($LASTEXITCODE -ne 0) { throw "Build failed: $module" }
    } finally { Pop-Location }
}
& (Join-Path $EpochRoot 'tests\test_shared.ps1')
if ($LASTEXITCODE -ne 0) { throw 'Shared tests failed' }
& (Join-Path $EpochRoot 'tests\test_projection_launchwrapper.ps1')
if ($LASTEXITCODE -ne 0) { throw 'Packaged legacy cache tests failed' }
python -m unittest discover -s (Join-Path $EpochRoot 'tests') -p 'test_*.py' -v
if ($LASTEXITCODE -ne 0) { throw 'Python discovery, deployment, and bootstrap tests failed' }
python (Join-Path $PSScriptRoot 'launch_epoch.py') --build-only
if ($LASTEXITCODE -ne 0) { throw 'Host build failed' }
& (Join-Path $EpochRoot 'tests\test_host_focus.ps1')
& (Join-Path $EpochRoot 'host\legacy\build.ps1')
if ($LASTEXITCODE -ne 0) { throw 'Legacy window components failed' }
& (Join-Path $PSScriptRoot 'install_mods.ps1')
