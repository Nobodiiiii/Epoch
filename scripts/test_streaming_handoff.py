"""Real 1.20 -> 1.18 -> 1.20 authority handoff using separate isolated native saves."""
import argparse
import json
import subprocess
import time
import uuid
from pathlib import Path

from epoch_instances import discover, install_adapter, atomic_json, instance_id
from launch_epoch import ROOT, JAVA17, resolve
from prepare_lab import create
from prepare_transfer import prepare as prepare_transfer


class Client:
    def __init__(self, version, evidence, role, binding=None, native_terrain=False, migrate_flat=False):
        game = ROOT / '.minecraft' / 'versions' / (version + '-Forge')
        seed = dict(instanceId=instance_id(game, version), gameDir=str(game), version=version, sessionId='streaming-handoff-test')
        entry = next(item for item in discover(ROOT, seed)['instances'] if item['id'] == seed['instanceId'])
        install_adapter(ROOT, entry)
        self.directory = evidence / role
        self.control = self.directory / 'control'
        self.control.mkdir(parents=True)
        world = 'Epoch Streaming Test ' + role + ' ' + evidence.name[-12:]
        if native_terrain and binding:
            if migrate_flat:
                create(version, game, world_name=world, bounded=False)
                atomic_json(game / 'saves' / world / 'epoch/authority.json', json.loads(Path(binding).read_text(encoding='utf-8')))
            prepare_transfer(version, game, world, 'EpochPlayer', None, binding)
        else:
            create(version, game, world_name=world, bounded=False, terrain='native' if native_terrain else 'flat')
        launch = resolve(version, entry, standalone=True)
        argv = launch['argv'][:]
        argv[1:1] = ['-Depoch.streaming=true', '-Depoch.testHarness=true', '-Depoch.autoStart=true',
                     '-Depoch.world=' + world, '-Depoch.controlDir=' + str(self.control)]
        if native_terrain and version == '1.20.1':
            argv.insert(1, '-Depoch.testSurfaceView=true')
        if binding:
            argv.insert(1, '-Depoch.authorityBinding=' + binding)
        with (self.directory / 'client.log').open('w', encoding='utf-8') as output:
            self.process = subprocess.Popen(argv, cwd=launch['cwd'], stdout=output, stderr=subprocess.STDOUT)
        atomic_json(self.directory / 'run.json', dict(pid=self.process.pid, world=world, version=version, gameDir=str(game)))
        print(f'{role} started: PID {self.process.pid}, {version}', flush=True)

    def wait(self, predicate, timeout=120, command_id=None):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                raise RuntimeError(f'{self.directory.name} exited {self.process.returncode}')
            try:
                state = json.loads((self.control / 'status.json').read_text(encoding='utf-8'))
                # A previous command's ERROR can remain until the new command is consumed.
                if command_id is not None and state.get('commandId') != command_id:
                    time.sleep(.2)
                    continue
                if predicate(state):
                    return state
                if state.get('phase') == 'ERROR':
                    raise RuntimeError(state.get('detail', str(state)))
            except (FileNotFoundError, PermissionError, json.JSONDecodeError):
                # Windows may briefly deny reads while the adapter replaces status.json.
                pass
            time.sleep(.2)
        raise TimeoutError('Client stalled: ' + str(self.directory))

    def ready(self):
        return self.wait(lambda state: state.get('phase') == 'READY' and state.get('clientReady')
                         and state.get('diagnostics', {}).get('streamingProtocol') == 3, 240)

    def command(self, action, phase, path=None, timeout=120):
        identity = uuid.uuid4().hex
        value = dict(id=identity, action=action)
        if path:
            value['path'] = str(path)
        atomic_json(self.control / 'command.json', value)
        result = self.wait(lambda state: state.get('commandId') == identity and state.get('phase') == phase
                           and (phase != 'IMPORTED' or state.get('clientReady')), timeout, command_id=identity)
        print(self.directory.name + ': ' + action + ' -> ' + phase, flush=True)
        return result

    def settle(self, state):
        tick = state['diagnostics']['streamingTicks']
        return self.wait(lambda state: state.get('diagnostics', {}).get('streamingTicks', 0) >= tick + 40, 90)

    def stop(self):
        if self.process.poll() is None:
            atomic_json(self.control / 'command.json', dict(id=uuid.uuid4().hex, action='shutdown'))
            try:
                self.process.wait(timeout=60)
            except subprocess.TimeoutExpired:
                with (self.directory / 'threads.txt').open('w', encoding='utf-8') as output:
                    subprocess.run([str(JAVA17 / 'jcmd.exe'), str(self.process.pid), 'Thread.print'], stdout=output, stderr=subprocess.STDOUT, timeout=10)
                self.process.terminate()
                self.process.wait(timeout=15)
                raise RuntimeError('Test client did not shut down cleanly: ' + str(self.directory))
        if self.process.returncode != 0:
            raise RuntimeError('Test client exited abnormally: ' + str(self.process.returncode))


def run(target_version='1.18.2', round_trips=1):
    if not 1 <= round_trips <= 10:
        raise ValueError('round_trips must be between 1 and 10')
    evidence = ROOT / '.minecraft' / 'epoch' / 'evidence' / ('streaming-handoff-' + uuid.uuid4().hex)
    evidence.mkdir(parents=True)
    print('Evidence: ' + str(evidence), flush=True)
    clients = []
    report = dict(passed=False)
    try:
        source = Client('1.20.1', evidence, 'source'); clients.append(source)
        source.ready()
        source.settle(source.command('streamtest', 'STREAM_TESTED'))
        state = source.command('streaminspect', 'STREAM_INSPECTED')
        binding = state['diagnostics']['streamingBinding']
        assert 'cherry_planks' in state['diagnostics']['streamingInspect']['special']
        target = Client(target_version, evidence, 'target', binding); clients.append(target)
        target.ready(); target.command('freeze', 'FROZEN')
        first = evidence / 'outbound.json.gz'
        source.command('export', 'EXPORTED', first)
        target.command('import', 'IMPORTED', first)
        target.settle(target.command('resume', 'READY'))
        report['target'] = target.command('streaminspect', 'STREAM_INSPECTED')['diagnostics']['streamingInspect']
        assert 'unknown_block' in report['target']['special'] and 'cherry_planks' in report['target']['originalSpecial']
        assert 'gold_block' in report['target']['marker']
        # A stale source must not regain write ownership merely because it receives resume.
        rejected = source.command('resume', 'ERROR')
        assert 'ownership' in rejected['detail'].lower()
        report['staleSourceRejected'] = True
        target.command('streamedit', 'STREAM_INSPECTED')
        returning = evidence / 'return.json.gz'
        target.command('export', 'EXPORTED', returning)
        source.command('import', 'IMPORTED', returning)
        source.settle(source.command('resume', 'READY'))
        report['restored'] = source.command('streaminspect', 'STREAM_INSPECTED')['diagnostics']['streamingInspect']
        assert 'emerald_block' in report['restored']['marker']
        assert 'cherry_planks' in report['restored']['special']
        assert 'diamond_block' in report['restored']['origin'], 'Edit outside the handoff window was lost'
        report['farEditRestored'] = True
        for cycle in range(1, round_trips):
            outbound = evidence / ('outbound-' + str(cycle) + '.json.gz')
            source.command('export', 'EXPORTED', outbound)
            target.command('import', 'IMPORTED', outbound)
            target.settle(target.command('resume', 'READY'))
            state = target.command('streaminspect', 'STREAM_INSPECTED')['diagnostics']['streamingInspect']
            assert 'emerald_block' in state['marker'] and 'cherry_planks' in state['originalSpecial'], state
            returning = evidence / ('return-' + str(cycle) + '.json.gz')
            target.command('export', 'EXPORTED', returning)
            source.command('import', 'IMPORTED', returning)
            source.settle(source.command('resume', 'READY'))
            state = source.command('streaminspect', 'STREAM_INSPECTED')['diagnostics']['streamingInspect']
            assert 'emerald_block' in state['marker'] and 'cherry_planks' in state['special'], state
            assert 'diamond_block' in state['origin'], state
        report['roundTrips'] = round_trips
        report['passed'] = True
    except Exception as error:
        report['error'] = str(error)
        raise
    finally:
        failures = []
        for client in reversed(clients):
            try:
                client.stop()
            except Exception as error:
                failures.append(str(error))
        if failures:
            report['passed'] = False
            report['shutdownErrors'] = failures
        atomic_json(evidence / 'result.json', report)
        print(json.dumps(report, ensure_ascii=False), flush=True)
        if failures:
            raise RuntimeError('; '.join(failures))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--target-version', choices=('1.18.2', '1.16.5', '1.12.2', '1.7.10'), default='1.18.2')
    parser.add_argument('--round-trips', type=int, default=1)
    args = parser.parse_args()
    run(args.target_version, args.round_trips)
