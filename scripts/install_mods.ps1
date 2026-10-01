$ErrorActionPreference = 'Stop'
$EpochRoot = Split-Path -Parent $PSScriptRoot
@'
import sys
from pathlib import Path
root = Path(sys.argv[1]).resolve()
sys.path.insert(0, str(root / 'scripts'))
from epoch_instances import artifact, discover, install_adapter, running_instances
running = running_instances()
# Deploy only to the existing HMCL profiles inside the Minecraft directory.
# Never synthesize development instances during installation.
seed = dict(instanceId='', sessionId='installer', version='', gameDir=str(root))
catalog = discover(root, seed, running=[])
versions = (root / '.minecraft' / 'versions').resolve()
targets = [entry for entry in catalog['instances'] if entry['supported']
           and Path(entry['gameDir']).resolve().parent == versions
           and Path(entry['versionJson']).resolve().parent == Path(entry['gameDir']).resolve()]
targets = list({str(Path(entry['gameDir']).resolve()).lower(): entry for entry in targets}.values())
if not targets:
    raise RuntimeError('No supported Forge profiles found under ' + str(versions))
# Validate both builds and targets before changing either instance.
for target in targets:
    artifact(root, target['version'])
    if any(Path(process['gameDir']).resolve() == Path(target['gameDir']) for process in running):
        raise RuntimeError('Close the running instance before installing: ' + target['gameDir'])
for target in targets:
    # Recheck live processes inside the shared installer before each deployment.
    installed = install_adapter(root, target)
    print('Installed Epoch for ' + target['version'] + ': ' + str(installed))
'@ | python - $EpochRoot
if ($LASTEXITCODE -ne 0) { throw 'Epoch adapter installation failed; see the error above.' }
