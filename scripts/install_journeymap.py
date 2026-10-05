"""Install pinned official JourneyMap releases and dependencies for Epoch's five profiles."""
import argparse
import hashlib
import json
import re
import time
import urllib.request
import zipfile
from pathlib import Path

from epoch_instances import running_instances

ROOT = Path(__file__).resolve().parent.parent


def mod_ids(path):
    try:
        with zipfile.ZipFile(path) as jar:
            if 'META-INF/mods.toml' in jar.namelist():
                text = jar.read('META-INF/mods.toml').decode('utf8')
                sections = re.findall(r'(?ms)^\s*\[\[mods\]\]\s*(.*?)(?=^\s*\[|\Z)', text)
                return {identity for section in sections for identity in re.findall(r'modId\s*=\s*[\"\']([^\"\']+)', section)}
            value = json.loads(jar.read('mcmod.info').decode('utf8'))
            items = value if isinstance(value, list) else value.get('modList', [])
            return {item['modid'] for item in items if 'modid' in item}
    except (OSError, KeyError, ValueError, zipfile.BadZipFile):
        return set()


def run(download_only=False):
    entries = json.loads((ROOT / 'scripts/journeymap-files.json').read_text(encoding='utf8'))
    cache = ROOT / 'build/journeymap'
    cache.mkdir(parents=True, exist_ok=True)
    for entry in entries:
        info = entry['file']
        destination = cache / info['filename']
        if not destination.exists():
            request = urllib.request.Request(info['url'], headers={'User-Agent': 'Epoch-local-installer/0.3'})
            with urllib.request.urlopen(request, timeout=60) as response:
                data = response.read()
            if hashlib.sha512(data).hexdigest() != info['hashes']['sha512']:
                raise ValueError('Download checksum mismatch: ' + info['filename'])
            destination.write_bytes(data)
        if hashlib.sha512(destination.read_bytes()).hexdigest() != info['hashes']['sha512']:
            raise ValueError('Cached checksum mismatch: ' + info['filename'])
        if entry['game'] == '1.20.1' and info['filename'].startswith('journeymap-'):
            with zipfile.ZipFile(destination) as jar:
                names = [name for name in jar.namelist() if name.endswith('/journeymap-api-forge-1.20.1-2.0.0.jar')]
                if len(names) != 1:
                    raise ValueError('JourneyMap embedded API missing')
                (cache / Path(names[0]).name).write_bytes(jar.read(names[0]))
    if download_only:
        return
    profiles = {entry['game']: (ROOT / '.minecraft/versions' / (entry['game'] + '-Forge')).resolve() for entry in entries}
    running = running_instances()
    for profile in profiles.values():
        if not profile.is_dir():
            raise FileNotFoundError(profile)
        if any(Path(process['gameDir']).resolve() == profile for process in running):
            raise RuntimeError('Close the running game before installation: ' + str(profile))
    for entry in entries:
        info = entry['file']
        mods = profiles[entry['game']] / 'mods'
        mods.mkdir(exist_ok=True)
        destination = mods / info['filename']
        if destination.exists() and hashlib.sha512(destination.read_bytes()).hexdigest() == info['hashes']['sha512']:
            continue
        if any(Path(process['gameDir']).resolve() == profiles[entry['game']] for process in running_instances()):
            raise RuntimeError('Game started during installation')
        identities = mod_ids(cache / info['filename'])
        if not identities:
            raise ValueError('Downloaded JAR has no recognizable mod identity: ' + info['filename'])
        backup = profiles[entry['game']] / 'epoch/map-mod-backups' / str(time.time_ns())
        old = [path for path in mods.glob('*.jar') if mod_ids(path) & identities]
        if destination.exists() and destination not in old:
            raise FileExistsError('Destination is occupied by an unrelated file: ' + str(destination))
        if old:
            backup.mkdir(parents=True)
            for path in old:
                path.rename(backup / path.name)
        try:
            temporary = mods / (info['filename'] + '.tmp')
            temporary.write_bytes((cache / info['filename']).read_bytes())
            temporary.replace(destination)
        except Exception:
            for path in old:
                (backup / path.name).rename(path)
            raise
        print('Installed ' + str(destination), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--download-only', action='store_true')
    run(parser.parse_args().download_only)
