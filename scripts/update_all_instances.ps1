param(
    [switch]$InstallOnly,
    [switch]$Online
)

$ErrorActionPreference = 'Stop'
$EpochRoot = Split-Path -Parent $PSScriptRoot
$EpochLogDirectory = Join-Path $EpochRoot 'build\update-logs'
$EpochTranscriptStarted = $false
$EpochExitCode = 0
Push-Location -LiteralPath $EpochRoot
try {
    New-Item -ItemType Directory -Path $EpochLogDirectory -Force | Out-Null
    $EpochLog = Join-Path $EpochLogDirectory ('update-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '.log')
    Start-Transcript -LiteralPath $EpochLog | Out-Null
    $EpochTranscriptStarted = $true
    Write-Host "Epoch instance update. Log: $EpochLog"
    if (-not (Get-Command python -ErrorAction SilentlyContinue)) {
        throw 'Python is required. Install Python and add it to PATH.'
    }

    # Fail before the build if a supported local profile is still running.
    @'
import sys
from pathlib import Path
root = Path(sys.argv[1]).resolve()
sys.path.insert(0, str(root / 'scripts'))
from epoch_instances import discover, running_instances
catalog = discover(root, dict(instanceId='', sessionId='updater', version='', gameDir=str(root)), running=[])
versions = (root / '.minecraft' / 'versions').resolve()
targets = [entry for entry in catalog['instances'] if entry['supported']
           and Path(entry['gameDir']).resolve().parent == versions
           and Path(entry['versionJson']).resolve().parent == Path(entry['gameDir']).resolve()]
targets = list({str(Path(entry['gameDir']).resolve()).lower(): entry for entry in targets}.values())
if not targets:
    raise RuntimeError('No supported Forge profiles found under ' + str(versions))
live = {str(Path(process['gameDir']).resolve()).lower() for process in running_instances()}
for target in targets:
    print('Target: ' + target['version'] + ' - ' + target['gameDir'])
    if str(Path(target['gameDir']).resolve()).lower() in live:
        raise RuntimeError('Close the running instance before updating: ' + target['gameDir'])
'@ | python - $EpochRoot
    if ($LASTEXITCODE -ne 0) { throw 'Instance preflight failed.' }

    if ($InstallOnly) {
        Write-Host 'Installing existing local build artifacts.'
        & (Join-Path $PSScriptRoot 'install_mods.ps1')
    } else {
        Write-Host 'Building and checking all five adapters, then installing them.'
        & (Join-Path $PSScriptRoot 'build.ps1') -Offline:(-not $Online)
    }
    Write-Host 'All supported local Forge profiles have been updated.'
    Write-Host 'Previous Epoch JARs are backed up in each instance under epoch\mod-backups.'
} catch {
    $EpochExitCode = 1
    Write-Host ('UPDATE FAILED: ' + $_.Exception.Message) -ForegroundColor Red
} finally {
    if ($EpochTranscriptStarted) { Stop-Transcript | Out-Null }
    Pop-Location
}
exit $EpochExitCode
