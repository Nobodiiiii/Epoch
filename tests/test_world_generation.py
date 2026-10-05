"""Native terrain settings and lossless migration, using isolated saves only."""
import contextlib
import gzip
import io
import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
import level_nbt
import prepare_lab as lab
import prepare_transfer as transfer
from test_versions import Nbt, VERSIONS


class GenerationTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='epoch-generation-')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.world = 'Epoch Transfer fixture'
        self.seed = -9223372036854775000
        with contextlib.redirect_stdout(io.StringIO()):
            lab.create('1.20.1', self.root / 'source', world_name='Original', terrain='native', seed=self.seed)
        self.source = self.root / 'source/saves/Original'
        self.binding = self.root / 'binding.json'
        self.binding.write_text(json.dumps(dict(schema=3, worldId='fixture', sourceWorld=str(self.source))))
        self.original_source = (self.source / 'level.dat').read_bytes()

    def prepare(self, version):
        with contextlib.redirect_stdout(io.StringIO()):
            transfer.prepare(version, self.root / version, self.world, 'FixturePlayer', None, self.binding)
        self.assertEqual(self.original_source, (self.source / 'level.dat').read_bytes())
        return self.root / version / 'saves' / self.world

    def data(self, save):
        with gzip.open(save / 'level.dat', 'rb') as stream:
            return Nbt.read(stream).plain()['Data']

    def check_native(self, version, data):
        if version in ('1.7.10', '1.12.2'):
            self.assertEqual(('default', '', 1, self.seed, 1),
                             tuple(data[key] for key in ('generatorName', 'generatorOptions', 'generatorVersion', 'RandomSeed', 'MapFeatures')))
        else:
            settings = data['WorldGenSettings']
            self.assertEqual((self.seed, 1), (settings['seed'], settings['generate_features']))
            generator = settings['dimensions']['minecraft:overworld']['generator']
            self.assertEqual(('minecraft:noise', 'minecraft:overworld', self.seed),
                             (generator['type'], generator['settings'], generator['seed']))
            biome = generator['biome_source']
            if version == '1.16.5':
                self.assertEqual(dict(type='minecraft:vanilla_layered', seed=self.seed, large_biomes=0), biome)
            else:
                self.assertEqual(dict(type='minecraft:multi_noise', preset='minecraft:overworld'), biome)
            for name in ('minecraft:the_nether', 'minecraft:the_end'):
                other = settings['dimensions'][name]['generator']
                self.assertEqual(self.seed, other['seed'])
                if 'seed' in other['biome_source']:
                    self.assertEqual(self.seed, other['biome_source']['seed'])

    def old_save(self, version):
        with contextlib.redirect_stdout(io.StringIO()):
            lab.create(version, self.root / version, world_name=self.world, bounded=False)
        save = self.root / version / 'saves' / self.world
        (save / 'epoch').mkdir()
        (save / 'epoch/authority.json').write_bytes(self.binding.read_bytes())
        return save

    def test_five_versions_create_native_world_and_retain_seed(self):
        for version in VERSIONS:
            with self.subTest(version=version):
                save = self.prepare(version)
                self.check_native(version, self.data(save))
                before = (save / 'level.dat').read_bytes()
                self.prepare(version)
                self.assertEqual(before, (save / 'level.dat').read_bytes())

    def test_read_seed_from_every_source_version(self):
        for index, version in enumerate(VERSIONS):
            with contextlib.redirect_stdout(io.StringIO()):
                lab.create(version, self.root / version, terrain='native', seed=self.seed + index)
            self.assertEqual(self.seed + index, level_nbt.seed(self.root / version / 'saves/Epoch Lab/level.dat'))

    def test_five_versions_migrate_only_generation_and_keep_backup(self):
        for version in VERSIONS:
            with self.subTest(version=version):
                save = self.old_save(version)
                header, root = level_nbt.read(save / 'level.dat')
                # All array types and Java modified UTF-8 survive without re-encoding.
                custom = (b'\x07\x00\x01b\x00\x00\x00\x02\xff\x00'
                          b'\x0b\x00\x01i\x00\x00\x00\x01\x80\x00\x00\x00'
                          b'\x0c\x00\x01l\x00\x00\x00\x01' + struct.pack('>q', self.seed)
                          + b'\x08\x00\x01s\x00\x08\xc0\x80\xed\xa0\xbd\xed\xb8\x80\x00')
                root.set('ForgeFixture', 10, custom)
                original = gzip.compress(header + root.encode())
                (save / 'level.dat').write_bytes(original)
                (save / 'region').mkdir()
                (save / 'region/r.0.0.mca').write_bytes(b'Existing terrain and player buildings')
                (save / 'level.dat_old').write_bytes(b'Previous vanilla backup')
                before = level_nbt.read(save / 'level.dat')[1].compound('Data')
                self.prepare(version)
                after_root = level_nbt.read(save / 'level.dat')[1]
                after = after_root.compound('Data')
                changed = {'WorldGenSettings'} if version not in ('1.7.10', '1.12.2') else {
                    'generatorName', 'generatorOptions', 'generatorVersion', 'RandomSeed', 'MapFeatures'}
                for key, row in before.fields.items():
                    if key.decode('ascii') not in changed:
                        self.assertEqual(row, after.fields[key], key)
                self.assertEqual(custom, after_root.get('ForgeFixture', 10))
                self.assertEqual(original, (save / 'epoch/level.dat.before-native-generation').read_bytes())
                self.assertEqual(b'Existing terrain and player buildings', (save / 'region/r.0.0.mca').read_bytes())
                self.assertEqual(b'Previous vanilla backup', (save / 'level.dat_old').read_bytes())
                # Independent decoder does not support Java modified UTF-8; remove just the fixture.
                del after_root.fields[b'ForgeFixture']
                data = Nbt.read(io.BytesIO(header + after_root.encode())).plain()['Data']
                self.check_native(version, data)
                current = (save / 'level.dat').read_bytes()
                self.prepare(version)
                self.assertEqual(current, (save / 'level.dat').read_bytes())

    def test_custom_generator_is_preserved(self):
        for version in VERSIONS:
            save = self.prepare(version)
            header, root = level_nbt.read(save / 'level.dat')
            data = root.compound('Data')
            if version in ('1.7.10', '1.12.2'):
                data.set('generatorName', 8, lab.payload(*lab.s('amplified')))
            else:
                settings = data.compound('WorldGenSettings')
                dimensions = settings.compound('dimensions')
                overworld = dimensions.compound('minecraft:overworld')
                overworld.set('generator', 10, lab.payload(10, dict(type=lab.s('example:custom'))))
                dimensions.set('minecraft:overworld', 10, overworld.encode())
                settings.set('dimensions', 10, dimensions.encode())
                data.set('WorldGenSettings', 10, settings.encode())
            root.set('Data', 10, data.encode())
            original = gzip.compress(header + root.encode())
            (save / 'level.dat').write_bytes(original)
            self.prepare(version)
            self.assertEqual(original, (save / 'level.dat').read_bytes())

    def test_binding_mismatch_and_source_alias_are_rejected_before_writing(self):
        save = self.old_save('1.20.1')
        original = (save / 'level.dat').read_bytes()
        (save / 'epoch/authority.json').write_text('{"worldId":"other"}')
        with self.assertRaisesRegex(ValueError, 'different authority'):
            self.prepare('1.20.1')
        self.assertEqual(original, (save / 'level.dat').read_bytes())
        with self.assertRaisesRegex(ValueError, 'source save'):
            transfer.prepare('1.20.1', self.root / 'source', 'Original', 'FixturePlayer', None, self.binding)
        self.assertEqual(self.original_source, (self.source / 'level.dat').read_bytes())

    def test_failed_atomic_replace_preserves_save_and_backup(self):
        save = self.old_save('1.20.1')
        original = (save / 'level.dat').read_bytes()
        with patch.object(transfer.os, 'replace', side_effect=OSError('disk failure')):
            with self.assertRaisesRegex(OSError, 'disk failure'):
                self.prepare('1.20.1')
        self.assertEqual(original, (save / 'level.dat').read_bytes())
        self.assertEqual(original, (save / 'epoch/level.dat.before-native-generation').read_bytes())
        self.assertEqual([], list(save.glob('epoch-worldgen-*.tmp')))

    def test_failed_backup_does_not_publish_partial_backup_or_change_save(self):
        save = self.old_save('1.20.1')
        original = (save / 'level.dat').read_bytes()
        with patch.object(transfer.os, 'fsync', side_effect=OSError('backup failure')):
            with self.assertRaisesRegex(OSError, 'backup failure'):
                self.prepare('1.20.1')
        self.assertEqual(original, (save / 'level.dat').read_bytes())
        self.assertFalse((save / 'epoch/level.dat.before-native-generation').exists())
        self.assertEqual([], list(save.rglob('epoch-worldgen-*.tmp')))

    def test_malformed_nbt_is_rejected_without_replacement(self):
        save = self.old_save('1.20.1')
        for raw in (b'', b'\x0a\x00\x00', b'\x0a\x00\x00\x07\x00\x01a\xff\xff\xff\xff\x00',
                    b'\x0a\x00\x00\x09\x00\x01a\x00\x7f\xff\xff\xff\x00'):
            original = gzip.compress(raw)
            (save / 'level.dat').write_bytes(original)
            with self.assertRaises(ValueError):
                self.prepare('1.20.1')
            self.assertEqual(original, (save / 'level.dat').read_bytes())
        self.assertFalse((save / 'epoch/level.dat.before-native-generation').exists())


if __name__ == '__main__':
    unittest.main()
