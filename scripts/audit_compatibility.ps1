param([switch]$Offline)
$ErrorActionPreference = 'Stop'
$epochRoot = Split-Path -Parent $PSScriptRoot
$env:EPOCH_JAVA17_HOME = (& python (Join-Path $PSScriptRoot 'runtime_config.py') --java-home 17).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Java 17 discovery failed' }
$env:EPOCH_JAVA8_HOME = (& python (Join-Path $PSScriptRoot 'runtime_config.py') --java-home 8).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Java 8 discovery failed' }
$env:JAVA_HOME = $env:EPOCH_JAVA17_HOME
$epochGradle = Get-ChildItem -LiteralPath "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.8-bin" -Recurse -Filter gradle.bat | Select-Object -First 1 -ExpandProperty FullName
if (-not $epochGradle) { throw 'Gradle 8.8 is required' }
# Complete all exports before auditing, so no target sees a partial catalog set.
foreach ($phase in @('export', 'audit')) {
    foreach ($module in @('forge1710','forge112','forge116','forge118','forge120')) {
        $epochArgs = @('registryAudit','--no-daemon','--console=plain','--max-workers=2')
        if ($Offline) { $epochArgs += '--offline' }
        if ($phase -eq 'audit') { $epochArgs += '-PauditAll' }
        Push-Location (Join-Path $epochRoot $module)
        try {
            & $epochGradle @epochArgs
            if ($LASTEXITCODE -ne 0) { throw "Registry $phase failed: $module" }
        } finally { Pop-Location }
    }
}
python (Join-Path $PSScriptRoot 'compatibility_report.py')
if ($LASTEXITCODE -ne 0) { throw 'Compatibility coverage verification failed' }
