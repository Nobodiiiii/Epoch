"""Run real-client streaming checks in a new isolated save inside an existing Forge profile."""
import argparse
import json
import os
import subprocess
import time
import uuid
from pathlib import Path

from epoch_instances import discover, install_adapter, atomic_json, instance_id
from launch_epoch import ROOT, JAVA17, resolve
from prepare_lab import create


def run(profile, restore_run=None, version='1.20.1', explore=False, jfr=False, teleport_mode=None):
    if teleport_mode and (version not in ('1.20.1', '1.18.2') or not explore):
        raise ValueError('Native teleport mode requires 1.20.1 or 1.18.2 exploration')
    if jfr and not explore:
        raise ValueError('JFR recording requires the exploration route')
    if explore and restore_run:
        raise ValueError('Exploration requires a fresh test')
    game = (ROOT / '.minecraft' / 'versions' / profile).resolve()
    if game.parent != (ROOT / '.minecraft' / 'versions').resolve():
        raise ValueError('Profile must be a directory in .minecraft/versions')
    catalog = discover(ROOT, dict(instanceId=instance_id(game, version), gameDir=str(game),
                                  version=version, sessionId='streaming-test-discovery'))
    entry = next(item for item in catalog['instances'] if Path(item['gameDir']).resolve() == game)
    if entry['version'] != version or not entry['supported']:
        raise ValueError('Profile does not match the requested streaming adapter')
    install_adapter(ROOT, entry)
    identity = uuid.uuid4().hex
    evidence = ROOT / '.minecraft' / 'epoch' / 'evidence' / ('streaming-' + identity)
    control = evidence / 'control'
    control.mkdir(parents=True)
    world = 'Epoch Streaming Test ' + identity[:12]
    if restore_run:
        previous_path = Path(restore_run).resolve()
        if previous_path.parent != (ROOT / '.minecraft' / 'epoch' / 'evidence').resolve() or not previous_path.name.startswith('streaming-'):
            raise ValueError('Restore source must be a streaming test evidence directory')
        previous = json.loads((previous_path / 'run.json').read_text(encoding='utf-8'))
        if Path(previous['gameDir']).resolve() != game or not previous['world'].startswith('Epoch Streaming Test '):
            raise ValueError('Restore must use this profile and an isolated streaming test save')
        world = previous['world']
    create(version, game, world_name=world, bounded=False)
    launch = resolve(version, entry, standalone=True)
    argv = launch['argv'][:]
    argv[1:1] = ['-Depoch.streaming=true', '-Depoch.testHarness=true', '-Depoch.autoStart=true',
                 '-Depoch.world=' + world, '-Depoch.controlDir=' + str(control)]
    if teleport_mode:
        argv.insert(1, '-Depoch.testTeleportMode=' + teleport_mode)
    with (evidence / 'client.log').open('w', encoding='utf-8') as output:
        process = subprocess.Popen(argv, cwd=launch['cwd'], stdout=output, stderr=subprocess.STDOUT)
    atomic_json(evidence / 'run.json', dict(pid=process.pid, world=world, gameDir=str(game), controlDir=str(control)))
    print(json.dumps(dict(pid=process.pid, evidence=str(evidence), world=world)), flush=True)

    def wait_for(predicate, timeout):
        deadline = time.monotonic() + timeout
        last_phase = None
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError(f'Client exited {process.returncode}; see {evidence / "client.log"}')
            try:
                state = json.loads((control / 'status.json').read_text(encoding='utf-8'))
                if state.get('phase') != last_phase:
                    last_phase = state.get('phase')
                    print('Client phase: ' + str(last_phase), flush=True)
                if state.get('diagnostics', {}).get('serverLoopError'):
                    raise RuntimeError(state['diagnostics']['serverLoopError'])
                if state.get('phase') == 'ERROR':
                    raise RuntimeError(state.get('detail', str(state)))
                if predicate(state):
                    return state
            except (FileNotFoundError, PermissionError, json.JSONDecodeError):
                pass
            time.sleep(.25)
        raise TimeoutError(f'Client did not reach expected state; see {evidence}')

    def command(action):
        command_id = uuid.uuid4().hex
        atomic_json(control / 'command.json', dict(id=command_id, action=action))
        return command_id

    try:
        wait_for(lambda state: state.get('phase') == 'READY' and state.get('diagnostics', {}).get('streamingWorldId'), 240)
        command_id = command('streamrestore' if restore_run else 'streamtest')
        phase = 'STREAM_RESTORED' if restore_run else 'STREAM_TESTED'
        state = wait_for(lambda state: state.get('commandId') == command_id and state.get('phase') == phase, 120)
        report = state['diagnostics']['streamingRestore' if restore_run else 'streamingTest']
        if restore_run:
            assert report.get('restartRestored') is True
            expected_id = json.loads((previous_path / 'result.json').read_text(encoding='utf-8'))['report']['worldId']
            assert report['worldId'] == expected_id
        else:
            for key in ('nativeDirtyTracking', 'staleNativeReplaced', 'deletedContainerAbsent',
                        'lowerHeightPreserved', 'absentNativeRestored', 'projectionEmbedded'):
                if key == 'lowerHeightPreserved' and version in ('1.16.5', '1.12.2', '1.7.10'):
                    continue
                assert report.get(key) is True, (key, report)
            assert report.get('pendingReloadRestored') is True, report
            assert report.get('destinationCancellationChecked') is True, report
            if version == '1.16.5':
                assert report.get('missingPrefetchReconciled') is True, report
                assert report.get('lateFullRegistrationReconciled') is True, report
            if version == '1.7.10':
                assert report.get('metadataDirtyTracking') is True, report
                assert report.get('authorityNeighbourPopulation') is True, report
            assert abs(report['playerX']) > 32 and abs(report['playerZ']) > 32
        ticks = state['diagnostics']['streamingTicks']
        wait_for(lambda state: state.get('diagnostics', {}).get('streamingTicks', 0) >= ticks + 40, 60)
        position_id = command('streamposition')
        position = wait_for(lambda state: state.get('commandId') == position_id and state.get('phase') == 'STREAM_POSITION', 30)
        report['positionAfter40Ticks'] = position['diagnostics']['streamingPosition']
        if version == '1.7.10':
            received = wait_for(lambda state: state.get('clientReady') and
                               state.get('diagnostics', {}).get('clientReceivedNearbyChunks') == 9, 60)
            report['clientReceivedNearbyChunks'] = received['diagnostics']['clientReceivedNearbyChunks']
        if explore:
            if jfr:
                subprocess.run([str(JAVA17 / 'jcmd.exe'), str(process.pid), 'JFR.start', 'name=EpochExploration', 'settings=profile', 'filename=' + str(evidence / 'exploration.jfr'), 'dumponexit=true'], check=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=20)
            explore_id = command('streamexplore')
            state = wait_for(lambda state: state.get('commandId') == explore_id and state.get('diagnostics', {}).get('streamingExploration', {}).get('complete'), 240)
            report['exploration'] = state['diagnostics']['streamingExploration']
            if version in ('1.20.1', '1.18.2'):
                assert report['exploration'].get('nativeTeleportCompleted') is True, report
                assert report['exploration'].get('nativeTeleportMode') == (teleport_mode or 'rotation'), report
            destination = report['exploration']['destination']
            assert destination['status'] == 'complete' and destination['ready'] == destination['total'] == 25, destination
            assert destination['heldTickets'] == 0, destination
            if version in ('1.12.2', '1.7.10'):
                assert destination['lightReady'] == 9, destination
                if version == '1.7.10':
                    received = wait_for(lambda state: state.get('clientReady') and
                                       state.get('diagnostics', {}).get('clientReceivedNearbyChunks') == 9, 60)
                    report['clientReceivedAfterExploration'] = received['diagnostics']['clientReceivedNearbyChunks']
            else:
                assert destination['peakPending'] <= destination['pendingLimit'] == 4, destination
            if jfr:
                subprocess.run([str(JAVA17 / 'jcmd.exe'), str(process.pid), 'JFR.stop', 'name=EpochExploration'], check=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=20)
                report['exploration']['recording'] = 'exploration.jfr'
        atomic_json(evidence / 'result.json', dict(terrainChecksPassed=True, cleanShutdown=False, report=report))
        print(json.dumps(report, ensure_ascii=False), flush=True)
    except Exception as error:
        atomic_json(evidence / 'result.json', dict(passed=False, error=str(error)))
        raise
    finally:
        if process.poll() is None:
            command('shutdown')
            try:
                process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                with (evidence / 'shutdown-threads.txt').open('w', encoding='utf-8') as dump:
                    subprocess.run([str(JAVA17 / 'jcmd.exe'), str(process.pid), 'Thread.print'], stdout=dump, stderr=subprocess.STDOUT, timeout=10)
                # This is exclusively the process created above for the new disposable test save.
                process.terminate()
                process.wait(timeout=15)
                atomic_json(evidence / 'result.json', dict(passed=False, error='Clean shutdown timed out; see shutdown-threads.txt'))
                raise RuntimeError('Clean shutdown timed out: ' + str(evidence)) from None
        result = json.loads((evidence / 'result.json').read_text(encoding='utf-8'))
        if result.get('terrainChecksPassed'):
            result.update(passed=process.returncode == 0, cleanShutdown=process.returncode == 0, exitCode=process.returncode)
            atomic_json(evidence / 'result.json', result)
            if process.returncode != 0:
                raise RuntimeError('Client exited abnormally: ' + str(process.returncode))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--profile')
    parser.add_argument('--explore', action='store_true')
    parser.add_argument('--jfr', action='store_true')
    parser.add_argument('--teleport-mode', choices=('rotation', 'relative', 'facing'))
    parser.add_argument('--restore-run')
    parser.add_argument('--version', choices=('1.20.1', '1.18.2', '1.16.5', '1.12.2', '1.7.10'), default='1.20.1')
    args = parser.parse_args()
    run(args.profile or args.version + '-Forge', args.restore_run, args.version, args.explore, args.jfr, args.teleport_mode)
