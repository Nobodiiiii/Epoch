"""Verify the destination's real terrain generator in isolated Forge saves (all five versions)."""
import argparse
import gzip
import json
from pathlib import Path
import struct
import uuid
import zlib

from epoch_instances import atomic_json
from launch_epoch import ROOT
import level_nbt
from prepare_lab import create
from test_streaming_handoff import Client


def compounds(parent, name):
    raw = parent.get(name, 9)
    kind, count = struct.unpack('>Bi', raw[:5])
    if count == 0:
        return
    if kind != 10:
        raise ValueError('Expected compound list')
    offset = 5
    for _ in range(count):
        end = level_nbt._end(raw, offset, 10)
        yield level_nbt.Compound(raw[offset:end])
        offset = end


def inspect_regions(save, version):
    checked = stone_chunks = 0
    for path in sorted((save / 'region').glob('*.mca')):
        with path.open('rb') as region:
            locations = struct.unpack('>1024I', region.read(4096))
            for location in locations:
                if not location:
                    continue
                region.seek((location >> 8) * 4096)
                size = struct.unpack('>I', region.read(4))[0]
                compression = region.read(1)[0]
                encoded = region.read(size - 1)
                raw = {1: gzip.decompress, 2: zlib.decompress, 3: lambda data: data}[compression](encoded)
                header = level_nbt._string_end(raw, 1)
                chunk = level_nbt.Compound(raw[header:])
                modern = version in ('1.18.2', '1.20.1')
                level = chunk if modern else chunk.compound('Level')
                stone = False
                for section in compounds(level, 'sections' if modern else 'Sections'):
                    if version in ('1.7.10', '1.12.2'):
                        if b'Blocks' not in section.fields:
                            continue
                        stone |= 1 in section.get('Blocks', 7)[4:]
                    else:
                        if modern:
                            if b'block_states' not in section.fields:
                                continue
                            section = section.compound('block_states')
                        elif b'Palette' not in section.fields:
                            continue  # Vanilla can save lighting-only sections without block data.
                        for state in compounds(section, 'palette' if modern else 'Palette'):
                            stone |= state.get('Name', 8) == b'\x00\x0fminecraft:stone'
                checked += 1
                stone_chunks += int(stone)
                if stone_chunks >= 4:
                    return dict(checkedChunks=checked, chunksWithNativeStone=stone_chunks)
    raise AssertionError(f'Expected noise terrain with stone, inspected {checked} chunks, found {stone_chunks}')


def run(version, migrate_flat=False, empty_source_regions=False):
    evidence = ROOT / '.minecraft/epoch/evidence' / ('native-generation-' + uuid.uuid4().hex)
    evidence.mkdir(parents=True)
    print('Evidence: ' + str(evidence), flush=True)
    # A bound source with no chunks makes these target chunks genuinely unexplored.
    # There is no source client to accidentally generate the target's terrain.
    create('1.20.1', evidence / 'source', world_name='Original', terrain='native', seed=-918273645, bounded=False)
    source = evidence / 'source/saves/Original'
    empty_regions = []
    if empty_source_regions:
        # Vanilla creates zero-byte region files while probing unexplored chunks.
        # Cover every startup region so the real target load hook must inspect one.
        (source / 'region').mkdir()
        for x in range(-2, 3):
            for z in range(-2, 3):
                path = source / 'region' / f'r.{x}.{z}.mca'
                path.touch()
                empty_regions.append(path)
    identity = str(uuid.uuid4())
    binding = evidence / 'source-binding.json'
    atomic_json(binding, dict(schema=3, worldId=identity, sourceWorld=str(source),
                             sourceVersion='1.20.1', sourceInstance='source-fixture', authority=str(evidence / 'authority')))
    client = None
    report = dict(passed=False, version=version, migrated=migrate_flat, emptySourceRegions=empty_source_regions)
    try:
        client = Client(version, evidence, 'target', str(binding), native_terrain=True, migrate_flat=migrate_flat)
        state = client.ready()
        client.settle(state)
        client.stop()
        run_info = json.loads((client.directory / 'run.json').read_text(encoding='utf-8'))
        save = Path(run_info['gameDir']) / 'saves' / run_info['world']
        # The native game must successfully decode and save the original signed 64-bit seed.
        assert level_nbt.seed(save / 'level.dat') == -918273645
        report.update(inspect_regions(save, version))
        if empty_source_regions:
            assert all(path.is_file() and path.stat().st_size == 0 for path in empty_regions)
            report['emptySourceRegionsPreserved'] = len(empty_regions)
        report.update(passed=True, cleanShutdown=True, exitCode=client.process.returncode, save=str(save))
        if migrate_flat:
            assert (save / 'epoch/level.dat.before-native-generation').is_file()
        print(json.dumps(report), flush=True)
    except Exception as error:
        report['passed'] = False
        report['error'] = str(error)
        raise
    finally:
        if client is not None and client.process.poll() is None:
            client.stop()
        atomic_json(evidence / 'result.json', report)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--version', choices=('1.7.10', '1.12.2', '1.16.5', '1.18.2', '1.20.1'), default='1.20.1')
    parser.add_argument('--migrate-flat', action='store_true')
    parser.add_argument('--empty-source-regions', action='store_true')
    args = parser.parse_args()
    run(args.version, args.migrate_flat, args.empty_source_regions)
