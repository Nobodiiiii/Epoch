"""Summarize server-thread samples and waits from an exploration-only JFR recording."""
import argparse
from collections import Counter
import json
from pathlib import Path
import re
import subprocess
from launch_epoch import JAVA17


def summarize(recording):
    output = recording.with_suffix('.events.json')
    with output.open('w', encoding='utf-8') as stream:
        subprocess.run([str(JAVA17 / 'jfr.exe'), 'print', '--json', '--stack-depth', '64', '--events',
                        'jdk.ExecutionSample,jdk.ThreadPark,jdk.JavaMonitorEnter,jdk.GCPhasePause',
                        str(recording)], stdout=stream, check=True)
    events = json.loads(output.read_text(encoding='utf-8'))['recording']['events']
    samples, waits, wait_sites, monitor_owners = Counter(), Counter(), Counter(), Counter()
    worst = []
    gc_ms = []
    for event in events:
        values = event['values']
        duration = values.get('duration', 'PT0S')
        match = re.fullmatch(r'PT([0-9.]+)S', duration)
        millis = float(match[1]) * 1000 if match else 0
        if event['type'] == 'jdk.GCPhasePause':
            gc_ms.append(millis)
            continue
        thread = values.get('sampledThread') or values.get('eventThread') or {}
        if thread.get('javaName') != 'Server thread':
            continue
        frames = (values.get('stackTrace') or {}).get('frames', [])
        methods = [frame['method']['type']['name'].replace('/', '.') + '.' + frame['method']['name'] for frame in frames]
        epoch = next((method for method in methods if method.startswith('dev.epoch.')), None)
        label = epoch or (methods[0] if methods else 'unknown')
        if event['type'] == 'jdk.ExecutionSample':
            samples[label] += 1
        else:
            waits[label] += millis
            if event['type'] == 'jdk.JavaMonitorEnter':
                owner = (values.get('previousOwner') or {}).get('javaName', 'unknown')
                monitor_owners[owner] += millis
            site = next((method for method in methods if method in ('dev.epoch.StreamingWorld.unloaded', 'dev.epoch.StreamingWorld.encoded', 'dev.epoch.StreamingWorld.tick', 'dev.epoch.StreamingWorld.decoded')), label)
            wait_sites[site] += millis
            worst.append(dict(type=event['type'], durationMs=millis, stack=methods))
    result = dict(serverSamples=samples.most_common(20), serverWaitTotalMs=waits.most_common(20), serverWaitCallSitesMs=wait_sites.most_common(20),
                  serverMonitorPreviousOwnerMs=monitor_owners.most_common(20),
                  longestServerWaits=sorted(worst, key=lambda row: row['durationMs'], reverse=True)[:10],
                  gcPauseCount=len(gc_ms), gcPauseTotalMs=sum(gc_ms), gcPauseMaxMs=max(gc_ms, default=0),
                  caveat='Execution samples are counts, not elapsed time. Wait totals include normal server pacing.')
    recording.with_suffix('.summary.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('recording', type=Path)
    summarize(parser.parse_args().recording)
