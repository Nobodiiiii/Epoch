"""Explicit maintenance commands for the current world session."""
import argparse, json, pathlib, re, time, uuid
from epoch_instances import atomic_json, load_json

ROOT = pathlib.Path(__file__).resolve().parents[1] / '.minecraft/epoch'


def reload_rules(instance=None):
    host = load_json(ROOT / 'host-status.json')
    if host.get('phase') != 'READY' or not -5000 <= time.time()*1000-host.get('heartbeat', 0) < 15000:
        raise RuntimeError('Wait for an active, connected world before reloading rules')
    instance = instance or host['activeInstanceId']
    if not re.fullmatch(r'[A-Za-z0-9_.-]{1,128}', instance):
        raise ValueError('Invalid instance identity')
    control = ROOT / 'control' / instance
    status = load_json(control / 'status.json')
    if (status.get('sessionId'), status.get('worldId')) != (host['sessionId'], host['worldId']):
        raise RuntimeError('The requested instance belongs to another world session')
    identity = str(uuid.uuid4())
    atomic_json(control / 'command.json', dict(id=identity, action='reloadrules', transactionId='',
                sessionId=host['sessionId'], worldId=host['worldId']))
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        result = load_json(control / 'status.json')
        if result.get('commandId') == identity:
            if result.get('phase') != 'RULES_RELOADED':
                raise RuntimeError(result.get('detail', 'Rule reload failed'))
            return result
        time.sleep(.1)
    raise TimeoutError('Rule reload acknowledgement timed out')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['reloadrules'])
    parser.add_argument('--instance')
    print(json.dumps(reload_rules(parser.parse_args().instance), ensure_ascii=False))
