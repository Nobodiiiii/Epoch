"""Save/exit and reopen one world in every supported real client, with no live source."""
import argparse
import gzip
import hashlib
import json
import re
import subprocess
import uuid
from pathlib import Path

from epoch_instances import atomic_json, discover, install_adapter, instance_id
from launch_epoch import ROOT, resolve
from prepare_transfer import prepare
from test_streaming_handoff import Client


def saved_state(binding):
    folder = Path(binding['authority']) / 'saved'
    name = (folder / 'current').read_text(encoding='utf-8')
    raw = (folder / name).read_bytes()
    checkpoint = json.loads((folder / (name + '.authority.json')).read_text(encoding='utf-8'))
    assert checkpoint['snapshotHash'] == hashlib.sha256(raw).hexdigest()
    snapshot = json.loads(gzip.decompress(raw))
    assert 'minecraft:diamond' in snapshot['inventory'] and re.search(r'"?Count"?\s*:\s*7b', snapshot['inventory']), snapshot['inventory']
    return snapshot


def reopen(version, world, evidence, role, identity=None):
    game = ROOT / '.minecraft/versions' / (version + '-Forge')
    seed = dict(instanceId=instance_id(game, version), version=version, gameDir=str(game), sessionId='saved-restart-test')
    entry = next(item for item in discover(ROOT, seed)['instances'] if item['id'] == seed['instanceId'])
    install_adapter(ROOT, entry)
    client = Client.__new__(Client)
    client.directory = evidence / role
    if client.directory.exists():
        client.directory = evidence / (role + '-' + uuid.uuid4().hex[:8])
    client.control = client.directory / 'control'
    client.control.mkdir(parents=True)
    launch = resolve(version, entry, standalone=True)
    argv = launch['argv'][:]
    if identity:
        for option, value in (('--username', identity['username']), ('--uuid', identity['uuid'])):
            argv[argv.index(option) + 1] = value
    argv[1:1] = ['-Depoch.streaming=true', '-Depoch.testHarness=true', '-Depoch.autoStart=true',
                 '-Depoch.world=' + world, '-Depoch.controlDir=' + str(client.control)]
    with (client.directory / 'client.log').open('w', encoding='utf-8') as output:
        client.process = subprocess.Popen(argv, cwd=launch['cwd'], stdout=output, stderr=subprocess.STDOUT)
    atomic_json(client.directory / 'run.json', dict(pid=client.process.pid, world=world, gameDir=str(game), version=version))
    print(role + ': PID ' + str(client.process.pid), flush=True)
    return client


def run(resume=None):
    evidence = Path(resume).resolve() if resume else ROOT / '.minecraft/epoch/evidence' / ('saved-restart-' + uuid.uuid4().hex)
    if resume and (evidence.parent != ROOT / '.minecraft/epoch/evidence' or not evidence.name.startswith('saved-restart-')):
        raise ValueError('Resume must refer to an isolated saved-restart evidence directory')
    evidence.mkdir(parents=True, exist_ok=bool(resume))
    report = dict(passed=False, versions=[])
    client = None
    print('Evidence: ' + str(evidence), flush=True)
    try:
        if not resume:
            client = Client('1.20.1', evidence, 'source')
            client.ready()
            client.settle(client.command('streamtest', 'STREAM_TESTED'))
            client.command('streamedit', 'STREAM_INSPECTED')
            client.stop()
        source_run = json.loads((evidence / 'source/run.json').read_text(encoding='utf-8'))
        original = source_run['world']
        binding_file = Path(source_run['gameDir']) / 'saves' / original / 'epoch/authority.json'
        binding = json.loads(binding_file.read_text(encoding='utf-8'))
        assert (Path(binding['authority']) / 'saved/current').is_file(), 'Save did not publish a restart checkpoint'
        saved = saved_state(binding)
        for version in ('1.12.2', '1.7.10', '1.16.5', '1.18.2', '1.20.1'):
            game = ROOT / '.minecraft/versions' / (version + '-Forge')
            world = original if version == '1.20.1' else 'Epoch Restart Test ' + evidence.name[-12:]
            if version != '1.20.1':
                prepare(version, game, world, 'EpochPlayer', None, binding_file)
            client = reopen(version, world, evidence, version)
            client.ready()
            state = client.command('probe', 'PROBED')
            assert state['diagnostics'].get('restartRestored') is True, state
            camera = state['diagnostics']['camera']
            assert all(abs(camera[n] - saved[key]) < .02 for n, key in enumerate(('x', 'y', 'z'))), camera
            terrain = client.command('streaminspect', 'STREAM_INSPECTED')['diagnostics']['streamingInspect']
            assert 'emerald_block' in terrain['marker'] and 'diamond_block' in terrain['origin'], terrain
            if version == '1.20.1':
                assert 'cherry_planks' in terrain['special'], terrain
            client.stop()
            saved = saved_state(binding)
            assert saved['version'] == version, 'Shutdown retained an older version checkpoint'
            report['versions'].append(dict(version=version, state=state['diagnostics'], terrain=terrain, exitCode=client.process.returncode))
            atomic_json(evidence / 'result.json', report)
            print(version + ': saved, exited, restored terrain and player state', flush=True)
        report['passed'] = True
    except Exception as error:
        report['error'] = str(error)
        raise
    finally:
        if client is not None and client.process.poll() is None:
            client.stop()
        atomic_json(evidence / 'result.json', report)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--resume')
    run(parser.parse_args().resume)
