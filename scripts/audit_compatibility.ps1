param([switch]$Offline)
$ErrorActionPreference = 'Stop'
$epochRoot = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.15.6-hotspot'
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
