"""Checks against real adopted Minecraft clients. Never launches or mocks clients.

baseline edits the active 1.20.1 world to create the existing Epoch test fixture.
switch TARGET uses the registered mod IPC select action, preserving TARGET as an ID.
Evidence lives under evidence/adopted-SESSION and never overwrites v0.1 evidence.
"""
import argparse
import gzip
import json
import os
import re
import time
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / '.minecraft' / 'epoch'


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def context():
    catalog = read(ROOT / 'catalog.json')
    seed = read(Path(catalog['seedPath']))
    assert catalog['sessionId'] == seed['sessionId'], 'Catalog/seed session mismatch'
    return catalog, seed


def verify_host(host, catalog, seed):
    assert host.get('adoptedRoot'), 'Host is not in adopted mode'
    assert host['sessionId'] == catalog['sessionId'] == seed['sessionId'], 'Host session mismatch'
    assert host['hostHwnd'] == seed['windowHandle'], 'Original Minecraft window was replaced'
    assert host['rootInstanceId'] == seed['instanceId'], 'Original instance identity changed'
    assert -5000 <= time.time() * 1000 - host['heartbeat'] < 15000, 'Host heartbeat expired'
    assert host['activeInstanceId'] in {entry['id'] for entry in catalog['instances']}


def wait(target=None, version=None, timeout=240):
    catalog, seed = context()
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        try:
            last = read(ROOT / 'host-status.json')
            if last.get('sessionId') != seed['sessionId']:
                raise AssertionError('Another host session replaced this test session')
            if last['phase'] in ('ERROR', 'DEGRADED'):
                raise RuntimeError(last)
            if last['phase'] == 'READY' and (target is None or last['activeInstanceId'] == target) and (version is None or last['activeVersion'] == version):
                verify_host(last, catalog, seed)
                return last
            if last.get('error'):
                raise RuntimeError(last)
        except (FileNotFoundError, json.JSONDecodeError):
            pass
        time.sleep(.2)
    raise TimeoutError(f'Adopted host did not become ready: {last}')


def command(instance, action, path=None, timeout=60):
    catalog, seed = context()
    entry = next(item for item in catalog['instances'] if item['id'] == instance)
    control = Path(seed['controlDir']) if instance == seed['instanceId'] else Path(entry.get('controlDir', ROOT / 'control' / instance))
    identity = str(uuid.uuid4())
    request = {'id': identity, 'action': action}
    if path is not None:
        request['path'] = path  # select expects a catalog ID, NOT a filesystem path.
    destination = control / 'command.json'
    temporary = destination.with_name('command.adopted-test-' + identity + '.tmp')
    temporary.write_text(json.dumps(request), encoding='utf-8')
    os.replace(temporary, destination)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            reply = read(control / 'status.json')
            if reply.get('commandId') == identity:
                if reply.get('phase') == 'ERROR':
                    raise RuntimeError(reply)
                return reply
        except (FileNotFoundError, json.JSONDecodeError):
            pass
        time.sleep(.1)
    raise TimeoutError(f'{instance}: {action} was not acknowledged')


def evidence_dir():
    _, seed = context()
    directory = ROOT / 'evidence' / ('adopted-' + seed['sessionId'])
    directory.mkdir(parents=True, exist_ok=True)
    return directory


def save(stage, value):
    path = evidence_dir() / f'{stage}-{time.time_ns()}.json'
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False), encoding='utf-8')
    print(json.dumps({'evidence': str(path), **value}, indent=2, ensure_ascii=False))
    return path


def baseline():
    host = wait(version='1.20.1')
    destination = evidence_dir() / 'baseline.json'
    if destination.exists():
        raise FileExistsError(f'Baseline already exists; refusing to overwrite {destination}')
    # Host READY acknowledges resume before the client's delayed input release.
    # Wait through that barrier BEFORE teleporting the test fixture; otherwise
    # the old held pose can overwrite the fixture's client teleport packet.
    deadline = time.monotonic() + 15
    stable = 0
    while time.monotonic() < deadline:
        ready = command(host['activeInstanceId'], 'probe')
        if ready['diagnostics'].get('clientFrozen') is False:
            stable += 1
            if stable >= 3:
                break
        else:
            stable = 0
        time.sleep(.3)
    else:
        raise TimeoutError('Client did not release the resume barrier before fixture setup')
    command(host['activeInstanceId'], 'testsetup')
    deadline = time.monotonic() + 15
    expected = [.5, 64., -3.5, 0., 10.]
    stable = 0
    while time.monotonic() < deadline:
        time.sleep(.3)
        probe = command(host['activeInstanceId'], 'probe')
        info = probe['diagnostics']
        cameras = [info.get('camera', []), info.get('clientCamera', [])]
        settled = info.get('clientFrozen') is False and all(
            len(camera) == 5 and all(abs(a - b) < .002 for a, b in zip(expected, camera))
            for camera in cameras)
        stable = stable + 1 if settled else 0
        if stable >= 3:
            break
    else:
        save('baseline-unsettled', {'host': host, 'probe': probe, 'expectedCamera': expected, 'passed': False})
        raise TimeoutError('Fixture client/server cameras did not settle; baseline was not recorded')
    assert probe['diagnostics'].get('itemEntityCount') == 0, 'Fixture has pre-existing dropped items'
    catalog = command(host['activeInstanceId'], 'catalog')
    value = {'stage': 'baseline', 'host': host, 'probe': probe, 'catalog': catalog, 'passed': True}
    destination.write_text(json.dumps(value, indent=2, ensure_ascii=False), encoding='utf-8')
    save('baseline-record', value)


def switch(target):
    host = wait()
    catalog, seed = context()
    entry = next((item for item in catalog['instances'] if item['id'] == target), None)
    assert entry and entry['supported'], f'Unsupported or unknown target {target}'
    assert target != host['activeInstanceId'], 'Target is already active'
    started = time.monotonic()
    reply = command(host['activeInstanceId'], 'select', target)
    result = wait(target=target)
    assert result['hostHwnd'] == host['hostHwnd'] == seed['windowHandle']
    save('switch', {'source': host, 'target': result, 'selection': reply,
                    'totalSeconds': time.monotonic() - started, 'passed': True})


def check(stage, version=None):
    host = wait(version=version or ('1.16.5' if stage == 'legacy' else '1.20.1'))
    deadline = time.monotonic() + 15
    while True:
        probe = command(host['activeInstanceId'], 'probe')
        if probe['diagnostics'].get('clientFrozen') is False:
            break
        if time.monotonic() > deadline:
            raise TimeoutError('Client did not release input after switching')
        time.sleep(.2)
    info = probe['diagnostics']
    base = read(evidence_dir() / 'baseline.json')
    assert base['host']['sessionId'] == host['sessionId']
    assert base['host']['hostHwnd'] == host['hostHwnd'], 'Original window changed'
    with gzip.open(ROOT / 'session' / 'snapshot.json.gz', 'rt', encoding='utf-8') as stream:
        snapshot = json.load(stream)
    expected = [snapshot[key] for key in ('x', 'y', 'z', 'yaw', 'pitch')]
    actual = info['camera']
    assert len(actual) == 5 and all(abs(a - b) < .002 for a, b in zip(expected, actual)), (expected, actual)
    client = info.get('clientCamera', [])
    assert len(client) == 5 and all(abs(a - b) < .002 for a, b in zip(expected, client)), (expected, client)
    assert all(abs(a - b) < .002 for a, b in zip(base['probe']['diagnostics']['camera'], actual)), 'Baseline camera moved'
    assert 'minecraft:chest' in info['block_-1']
    facing = r'"?facing"?\s*:\s*"([^\"]+)"'
    expected_facing = re.search(facing, base['probe']['diagnostics']['block_-1']).group(1)
    actual_facing = re.search(facing, info['block_-1']).group(1)
    assert actual_facing == expected_facing, ('Chest rotated during import', expected_facing, actual_facing)
    assert 'Epoch NBT sentinel' in info['nbt_-1'] and re.search(r'(?:"Count"|Count):7[bB]', info['nbt_-1'])
    assert info.get('itemEntityCount') == 0, 'Container migration dropped duplicate items'
    if stage == 'legacy':
        for coordinate in ('0', '2'):
            assert 'epoch:unknown_block' in info['block_' + coordinate]
            assert 'OriginalState' in info['nbt_' + coordinate]
        assert 'minecraft:cherry_planks' in info['nbt_0']
        assert 'minecraft:chiseled_bookshelf' in info['nbt_2']
        assert 'Across versions, this page survives.' in info['nbt_2']
    else:
        assert info['nbt_-1'] == base['probe']['diagnostics']['nbt_-1'], 'Chest NBT was not restored exactly'
        assert 'minecraft:chiseled_bookshelf' in info['block_2']
        assert info['nbt_2'] == base['probe']['diagnostics']['nbt_2'], 'Bookshelf NBT was not restored exactly'
        if stage == 'restored':
            assert 'minecraft:cherry_planks' in info['block_0']
            assert 'minecraft:stone' in info['block_1']
        else:
            assert 'minecraft:air' in info['block_0'], 'Deleted unknown block resurrected'
            assert 'minecraft:dirt' in info['block_1'], 'Legacy block edit was lost'
    save(stage, {'stage': stage, 'host': host, 'probe': probe, 'expectedCamera': expected, 'passed': True})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['baseline', 'switch', 'legacy', 'restored', 'edit', 'edited', 'probe'])
    parser.add_argument('target', nargs='?')
    parser.add_argument('--version', choices=['1.7.10', '1.12.2', '1.16.5', '1.18.2', '1.20.1'])
    args = parser.parse_args()
    if args.action == 'baseline':
        baseline()
    elif args.action == 'switch':
        if not args.target:
            parser.error('switch requires the exact target ID from catalog.json')
        switch(args.target)
    elif args.action == 'edit':
        host = wait(version=args.version or '1.16.5')
        reply = command(host['activeInstanceId'], 'testedit')
        probe = command(host['activeInstanceId'], 'probe')
        assert 'minecraft:air' in probe['diagnostics']['block_0']
        assert 'minecraft:dirt' in probe['diagnostics']['block_1']
        save('edit', {'host': host, 'reply': reply, 'probe': probe, 'passed': True})
    elif args.action == 'probe':
        host = wait()
        save('probe', {'host': host, 'probe': command(host['activeInstanceId'], 'probe'),
                       'catalog': command(host['activeInstanceId'], 'catalog')})
    else:
        check(args.action, args.version)


if __name__ == '__main__':
    main()
