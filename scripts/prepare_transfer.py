"""Prepare native terrain in Epoch's streaming projection saves before client startup."""
import gzip
import json
import os
import struct
import tempfile
from pathlib import Path

import level_nbt
from epoch_instances import atomic_json
from save_format import create, native_generation, payload, s


def _set_fields(compound, fields):
    for name, (kind, value) in fields.items():
        compound.set(name, kind, payload(kind, value))


def _atomic_write(path, content, replace=True):
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=path.parent, prefix='epoch-worldgen-', suffix='.tmp', delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        if replace:
            os.replace(temporary, path)
        else:
            # Windows rename refuses an existing destination, preserving an earlier backup.
            os.rename(temporary, path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def _is_lab_flat(version, data):
    """Only migrate the old Epoch factory preset, preserving customized generators."""
    if version in ('1.7.10', '1.12.2'):
        options = ('2;7,62x3,2;1;' if version == '1.7.10'
                   else '3;minecraft:bedrock,62*minecraft:dirt,minecraft:grass;1;')
        return (data.get('generatorName', 8) == payload(*s('flat'))
                and data.get('generatorOptions', 8) == payload(*s(options))
                and data.get('RandomSeed', 4) == struct.pack('>q', 0))
    settings = data.compound('WorldGenSettings')
    generator = settings.compound('dimensions').compound('minecraft:overworld').compound('generator')
    if generator.get('type', 8) != payload(*s('minecraft:flat')):
        return False
    flat = generator.compound('settings')
    layers = flat.get('layers', 9)
    if layers[:5] != b'\x0a\x00\x00\x00\x03' or settings.get('seed', 4) != struct.pack('>q', 0):
        return False
    offset = 5
    expected = [('minecraft:bedrock', 1), ('minecraft:dirt', 62 if version == '1.16.5' else 126),
                ('minecraft:grass_block', 1)]
    for block, height in expected:
        end = level_nbt._end(layers, offset, 10)
        layer = level_nbt.Compound(layers[offset:end])
        if layer.get('block', 8) != payload(*s(block)) or layer.get('height', 3) != struct.pack('>i', height):
            return False
        offset = end
    return flat.get('biome', 8) == payload(*s('minecraft:plains'))


def prepare(version, folder, world_name, username, player_uuid, binding_file):
    """Existing chunks are untouched; one backed-up metadata edit upgrades old flat caches."""
    binding = json.loads(Path(binding_file).read_text(encoding='utf-8'))
    source = Path(binding['sourceWorld']).resolve()
    # Validate the name before inspecting or changing any existing save.
    if not world_name or world_name in ('.', '..') or '/' in world_name or '\\' in world_name:
        raise ValueError('world_name must be a single save directory name')
    save = (Path(folder) / 'saves' / world_name).resolve()
    if save.parent != (Path(folder) / 'saves').resolve() or save == source:
        raise ValueError('Transfer preparation cannot modify the source save')
    local_binding = save / 'epoch' / 'authority.json'
    if local_binding.exists():
        local = json.loads(local_binding.read_text(encoding='utf-8'))
        if local.get('worldId') != binding['worldId']:
            raise ValueError('Target save belongs to a different authority')
    level = save / 'level.dat'
    if not level.exists():
        create(version, folder, world_name=world_name, username=username, player_uuid=player_uuid,
               bounded=False, terrain='native', seed=level_nbt.seed(source / 'level.dat'))
        atomic_json(local_binding, binding)
        return
    # Never change an unrelated or pre-streaming save. The launcher chooses a fresh name for those.
    if not local_binding.exists():
        raise ValueError('Existing transfer save has no matching authority binding')
    header, root = level_nbt.read(level)
    data = root.compound('Data')
    if not _is_lab_flat(version, data):
        return
    world_seed = level_nbt.seed(source / 'level.dat')
    if version in ('1.7.10', '1.12.2'):
        _set_fields(data, native_generation(version, world_seed))
    else:
        settings = data.compound('WorldGenSettings')
        dimensions = settings.compound('dimensions')
        overworld = dimensions.compound('minecraft:overworld')
        _set_fields(overworld, native_generation(version, world_seed))
        dimensions.set('minecraft:overworld', 10, overworld.encode())
        # 1.16's stable-world check compares ALL vanilla dimension/biome seeds.
        # Preserve dimension contents and custom generators, but keep default seeds consistent.
        for name, preset in (('minecraft:the_nether', 'minecraft:nether'), ('minecraft:the_end', 'minecraft:end')):
            dimension = dimensions.compound(name)
            generator = dimension.compound('generator')
            if (generator.get('type', 8) != payload(*s('minecraft:noise'))
                    or generator.fields.get(b'settings', ())[:2] != (8, payload(*s(preset)))):
                continue
            generator.set('seed', 4, struct.pack('>q', world_seed))
            biome = generator.compound('biome_source')
            if b'seed' in biome.fields:
                biome.set('seed', 4, struct.pack('>q', world_seed))
                generator.set('biome_source', 10, biome.encode())
            dimension.set('generator', 10, generator.encode())
            dimensions.set(name, 10, dimension.encode())
        settings.set('dimensions', 10, dimensions.encode())
        settings.set('seed', 4, struct.pack('>q', world_seed))
        settings.set('generate_features', 1, b'\x01')
        data.set('WorldGenSettings', 10, settings.encode())
    root.set('Data', 10, data.encode())
    # Keep the exact previous metadata; do not touch level.dat_old or any region/player files.
    backup = save / 'epoch' / 'level.dat.before-native-generation'
    if not backup.exists():
        _atomic_write(backup, level.read_bytes(), replace=False)
    _atomic_write(level, gzip.compress(header + root.encode()))
    print(f'Enabled native {version} terrain for unexplored chunks in {save}')
