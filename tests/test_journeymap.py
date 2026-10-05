import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
import install_journeymap as installer


class JourneyMapInstallerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'scripts').mkdir()
        (self.root / 'build/journeymap').mkdir(parents=True)
        self.game = self.root / '.minecraft/versions/1.16.5-Forge'
        self.mods = self.game / 'mods'
        self.mods.mkdir(parents=True)
        source = self.root / 'build/journeymap/journeymap-new.jar'
        self.jar(source, 'journeymap')
        manifest = [dict(game='1.16.5', file=dict(filename=source.name,
            hashes=dict(sha512=hashlib.sha512(source.read_bytes()).hexdigest()), url='https://unused.invalid/'))]
        (self.root / 'scripts/journeymap-files.json').write_text(json.dumps(manifest))
        self.patch_root = patch.object(installer, 'ROOT', self.root)
        self.patch_root.start()
        self.addCleanup(self.patch_root.stop)

    @staticmethod
    def jar(path, identity):
        with zipfile.ZipFile(path, 'w') as jar:
            jar.writestr('META-INF/mods.toml', f'[[mods]]\nmodId="{identity}"\n'
                f'[[dependencies.{identity}]]\nmodId="forge"\n')

    def test_dependency_is_not_mod_identity(self):
        source = self.root / 'build/journeymap/journeymap-new.jar'
        self.assertEqual(installer.mod_ids(source), {'journeymap'})

    @patch.object(installer, 'running_instances', return_value=[])
    def test_replace_only_matching_mod_and_keep_backup(self, _):
        self.jar(self.mods / 'old-map.jar', 'journeymap')
        self.jar(self.mods / 'journeymap-addon.jar', 'someaddon')
        addon = (self.mods / 'journeymap-addon.jar').read_bytes()
        installer.run()
        self.assertTrue((self.mods / 'journeymap-new.jar').exists())
        self.assertFalse((self.mods / 'old-map.jar').exists())
        self.assertEqual((self.mods / 'journeymap-addon.jar').read_bytes(), addon)
        self.assertEqual(len(list(self.game.glob('epoch/map-mod-backups/*/old-map.jar'))), 1)

    def test_running_profile_untouched(self):
        with patch.object(installer, 'running_instances', return_value=[dict(gameDir=str(self.game))]):
            with self.assertRaisesRegex(RuntimeError, 'Close the running game'):
                installer.run()
        self.assertEqual(list(self.mods.iterdir()), [])

    @patch.object(installer, 'running_instances', return_value=[])
    def test_failed_publish_restores_old_mod(self, _):
        self.jar(self.mods / 'old-map.jar', 'journeymap')
        original = (self.mods / 'old-map.jar').read_bytes()
        with patch.object(Path, 'replace', side_effect=OSError('simulated publish failure')):
            with self.assertRaises(OSError):
                installer.run()
        self.assertEqual((self.mods / 'old-map.jar').read_bytes(), original)


if __name__ == '__main__':
    unittest.main()
