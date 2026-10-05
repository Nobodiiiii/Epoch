import contextlib
import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
import prepare_saved_worlds as api
from launch_epoch import saved_target_world
from prepare_lab import create


class SavedWorldTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.entries = [dict(version=v, supported=True, gameDir=str(self.root / '.minecraft/versions' / v))
                        for v in ('1.20.1', '1.12.2', '1.7.10', '1.16.5', '1.18.2')]
        with contextlib.redirect_stdout(io.StringIO()):
            create('1.20.1', Path(self.entries[0]['gameDir']), world_name='Original', bounded=False)
        self.source = Path(self.entries[0]['gameDir']) / 'saves/Original'
        self.binding = self.source / 'epoch/authority.json'
        self.binding.parent.mkdir(exist_ok=True)
        self.binding.write_text(json.dumps(dict(schema=3, worldId='00000000-0000-0000-0000-000000000123',
              sourceWorld=str(self.source), authority=str(self.root / '.minecraft/epoch/worlds/authority'),
              sourceVersion='1.20.1', sourceInstance='source')), encoding='utf-8')
        mock = patch.object(api, 'discover', return_value=dict(instances=self.entries))
        mock.start(); self.addCleanup(mock.stop)

    def prepare(self):
        with contextlib.redirect_stdout(io.StringIO()):
            return api.prepare_all(self.root, self.binding)

    def test_links_all_versions_and_preserves_native_saves(self):
        original = (self.source / 'level.dat').read_bytes()
        first = self.prepare()
        self.assertEqual(len(first), 5)
        hashes = {r['world']: hashlib.sha256((Path(r['world']) / 'level.dat').read_bytes()).digest() for r in first}
        self.assertEqual(self.prepare(), first)
        self.assertEqual((self.source / 'level.dat').read_bytes(), original)
        for record in first:
            world = Path(record['world'])
            self.assertEqual(hashlib.sha256((world / 'level.dat').read_bytes()).digest(), hashes[str(world)])
            self.assertEqual(saved_target_world(world.parent.parent, self.binding, 'unused'),
                             (world.name, world == self.source))

    def test_name_collision_does_not_overwrite_unrelated_save(self):
        collision = Path(self.entries[1]['gameDir']) / 'saves/Original [Epoch 00000000]'
        collision.mkdir(parents=True)
        (collision / 'level.dat').write_bytes(b'user save')
        with self.assertRaises(ValueError):
            self.prepare()
        self.assertEqual((collision / 'level.dat').read_bytes(), b'user save')

    def test_reuses_preexisting_link_name(self):
        records = self.prepare()
        old = Path(records[1]['world']); renamed = old.with_name('Previously linked save')
        old.rename(renamed)
        result = self.prepare()
        self.assertEqual(result[1]['world'], str(renamed))
        self.assertFalse(old.exists())


if __name__ == '__main__':
    unittest.main()
