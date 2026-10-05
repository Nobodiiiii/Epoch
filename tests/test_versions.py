"""Version-specific launch/save regression checks using only temporary fixtures."""
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
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
import epoch_instances as instances
import launch_epoch as launch
import prepare_lab as lab

VERSIONS = {
    '1.7.10': (10, '10.13.4.1614', 0, 8),
    '1.12.2': (14, '14.23.5.2860', 1343, 8),
    '1.16.5': (36, '36.2.42', 2586, 8),
    '1.18.2': (40, '40.3.12', 2975, 17),
    '1.20.1': (47, '47.4.23', 3465, 17),
}


class Nbt:
    """Independent read-only decoder for the binary NBT written by prepare_lab."""
    def __init__(self, kind, value):
        self.kind, self.value = kind, value

    @classmethod
    def read(cls, stream, kind=None):
        def number(fmt):
            return struct.unpack(fmt, stream.read(struct.calcsize(fmt)))[0]

        def text():
            return stream.read(number('>H')).decode('utf8')

        if kind is None:
            kind = number('>B')
            text()
        if kind in (1, 2, 3, 4, 5, 6):
            value = number({1: '>b', 2: '>h', 3: '>i', 4: '>q', 5: '>f', 6: '>d'}[kind])
        elif kind == 8:
            value = text()
        elif kind == 9:
            item_kind, size = number('>B'), number('>i')
            value = [cls.read(stream, item_kind) for _ in range(size)]
        elif kind == 10:
            value = {}
            while True:
                child = number('>B')
                if not child:
                    break
                name = text()
                value[name] = cls.read(stream, child)
        elif kind in (7, 11, 12):
            size = number('>i')
            value = [number({7: '>b', 11: '>i', 12: '>q'}[kind]) for _ in range(size)]
        else:
            raise AssertionError('Unexpected NBT tag ' + str(kind))
        return cls(kind, value)

    def plain(self):
        if isinstance(self.value, dict):
            return {k: v.plain() for k, v in self.value.items()}
        if isinstance(self.value, list):
            return [v.plain() if isinstance(v, Nbt) else v for v in self.value]
        return self.value


class VersionTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='epoch-version-tests-')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.minecraft = self.root / '.minecraft'
        self.java8 = self.root / 'jdk8' / 'bin'
        self.java17 = self.root / 'jdk17' / 'bin'
        for name, value in [('ROOT', self.root), ('MINECRAFT', self.minecraft),
                            ('JAVA8', self.java8), ('JAVA17', self.java17)]:
            replacing = patch.object(launch, name, value)
            replacing.start()
            self.addCleanup(replacing.stop)

    def fixture(self, version, **overrides):
        folder = self.minecraft / 'versions' / (version + '-Forge')
        folder.mkdir(parents=True, exist_ok=True)
        metadata = dict(id=folder.name, libraries=[], mainClass='example.ForgeMain',
                        assetIndex={'id': version}, arguments={
                            'jvm': ['-Djava.library.path=${natives_directory}', '-cp', '${classpath}'],
                            'game': ['--username', '${auth_player_name}', '--gameDir', '${game_directory}']})
        metadata.update(overrides)
        profile = folder / (folder.name + '.json')
        profile.write_text(json.dumps(metadata), encoding='utf8')
        entry = dict(id=instances.instance_id(folder, version), version=version,
                     gameDir=str(folder), versionJson=str(profile),
                     libraryRoot=str(self.minecraft / 'libraries'), assetsRoot=str(self.minecraft / 'assets'))
        return entry

    def save(self, version, name='Transfer fixture'):
        folder = self.minecraft / 'versions' / version
        with contextlib.redirect_stdout(io.StringIO()):
            lab.create(version, folder, world_name=name, username='FixturePlayer')
        path = folder / 'saves' / name / 'level.dat'
        with gzip.open(path, 'rb') as stream:
            tree = Nbt.read(stream)
            self.assertEqual(b'', stream.read(), 'Trailing bytes after root NBT')
        return tree.value['Data'], folder

    def native_library(self, version='2.9.1', empty_artifact=False):
        classifier = f'org/lwjgl/lwjgl/lwjgl-platform/{version}/lwjgl-platform-{version}-natives-windows.jar'
        path = self.minecraft / 'libraries' / classifier
        path.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(path, 'w') as archive:
            archive.writestr('lwjgl64.dll', b'native fixture payload')
            archive.writestr('META-INF/irrelevant.txt', b'do not extract')
        downloads = {'classifiers': {'natives-windows': {'path': classifier}}}
        if empty_artifact:
            # Mojang metadata uses a 22-byte empty platform artifact. It is not a
            # required runtime JAR and need not exist beside the classifier.
            downloads['artifact'] = dict(size=22, path='missing-empty-platform.jar')
        return dict(name='org.lwjgl.lwjgl:lwjgl-platform:' + version,
                    natives={'windows': 'natives-windows'}, downloads=downloads)

    def test_all_supported_versions_choose_the_correct_java(self):
        for version, (major, forge, data_version, java) in VERSIONS.items():
            with self.subTest(version=version):
                entry = self.fixture(version)
                result = launch.resolve(version, entry, standalone=True)
                self.assertEqual(str((self.java8 if java == 8 else self.java17) / 'javaw.exe'), result['argv'][0])
                self.assertFalse(any(arg.startswith('-javaagent:') for arg in result['argv']))
                self.assertEqual(major, instances.SUPPORTED[version])
                self.assertEqual(result, json.loads((self.minecraft / 'epoch/control' / entry['id'] / 'launch.json').read_text()))

    def test_streaming_targets_inherit_binding_and_unbounded_world(self):
        seed = dict(gameDir='source', worldName='Source Save', username='FixturePlayer',
                    uuid='0a34619b-ecaa-4451-b3c1-c7557857423b', sessionId='fixture-session')
        binding = self.root / 'authority.json'
        source = self.root / 'source'
        lab.create('1.20.1', source, world_name='Source Save', terrain='native', seed=-918273645)
        binding.write_text(json.dumps(dict(schema=3, worldId='world-fixture',
                                          sourceWorld=str(source / 'saves' / 'Source Save'))))
        for version in ('1.7.10', '1.12.2', '1.16.5', '1.18.2', '1.20.1'):
            with self.subTest(version=version):
                entry = self.fixture(version)
                with patch.object(launch, 'streaming_binding', return_value=binding), contextlib.redirect_stdout(io.StringIO()):
                    argv = launch.resolve(version, entry, seed=seed, standalone=True)['argv']
                self.assertIn('-Depoch.streaming=true', argv)
                self.assertIn('-Depoch.authorityBinding=' + str(binding), argv)
                save = next((Path(entry['gameDir']) / 'saves').glob('*/level.dat'))
                with gzip.open(save, 'rb') as stream:
                    data = Nbt.read(stream).value['Data'].value
                self.assertEqual(59999968, data['BorderSize'].value)
                self.assertEqual(59999968, data['BorderSizeLerpTarget'].value)
                plain = {key: value.plain() for key, value in data.items()}
                if version in ('1.7.10', '1.12.2'):
                    self.assertEqual('default', plain['generatorName'])
                    self.assertEqual(-918273645, plain['RandomSeed'])
                else:
                    settings = plain['WorldGenSettings']
                    self.assertEqual(-918273645, settings['seed'])
                    self.assertEqual('minecraft:noise', settings['dimensions']['minecraft:overworld']['generator']['type'])
                # Preparation can be repeated before the game has ever opened the save.
                with patch.object(launch, 'streaming_binding', return_value=binding), contextlib.redirect_stdout(io.StringIO()):
                    again = launch.resolve(version, entry, seed=seed, standalone=True)['argv']
                self.assertEqual(argv, again)

    def test_legacy_profiles_fall_back_to_minecraft_arguments(self):
        for version in ('1.7.10', '1.12.2'):
            with self.subTest(version=version):
                entry = self.fixture(version, arguments={}, minecraftArguments=(
                    '--username ${auth_player_name} --gameDir "${game_directory}" '
                    '--userProperties ${user_properties} --tweakClass example.ForgeTweaker'))
                argv = launch.resolve(version, entry, standalone=True)['argv']
                self.assertEqual(entry['gameDir'], argv[argv.index('--gameDir') + 1])
                self.assertEqual('{}', argv[argv.index('--userProperties') + 1])
                self.assertEqual('example.ForgeTweaker', argv[argv.index('--tweakClass') + 1])
                self.assertIn('-Djava.library.path=' + str(Path(entry['gameDir']) / 'natives'), argv)
                self.assertFalse(any('${' in arg for arg in argv))

    def test_legacy_inheritance_retains_minecraft_arguments_and_parent_jar(self):
        parent = self.fixture('1.7.10', arguments={}, minecraftArguments='--username ${auth_player_name} --gameDir "${game_directory}"')
        parent_profile = Path(parent['versionJson'])
        parent_profile.with_suffix('.jar').write_bytes(b'fixture jar')
        child_folder = parent_profile.parent.parent / 'child-profile'
        child_folder.mkdir()
        child_profile = child_folder / 'child-profile.json'
        child_profile.write_text(json.dumps(dict(id='child-profile', inheritsFrom=parent_profile.stem,
                                                libraries=[], mainClass='example.InheritedMain')))
        child = dict(parent, versionJson=str(child_profile), gameDir=str(child_folder), id='child')
        argv = launch.resolve('1.7.10', child, standalone=True)['argv']
        self.assertEqual(str(parent_profile.with_suffix('.jar')), argv[argv.index('-cp') + 1])
        self.assertEqual(str(child_folder), argv[argv.index('--gameDir') + 1])
        self.assertIn('example.InheritedMain', argv)

    def test_native_only_and_empty_platform_artifacts_are_not_required_on_classpath(self):
        for empty in (False, True):
            with self.subTest(empty_artifact=empty):
                entry = self.fixture('1.7.10', libraries=[self.native_library(empty_artifact=empty)])
                argv = launch.resolve('1.7.10', entry, standalone=True)['argv']
                self.assertEqual('', argv[argv.index('-cp') + 1])
                natives = Path(entry['gameDir']) / 'natives'
                self.assertEqual(b'native fixture payload', (natives / 'lwjgl64.dll').read_bytes())
                self.assertEqual({'lwjgl64.dll'}, {p.name for p in natives.iterdir()})

    def test_metadata_detection_for_five_forge_profiles(self):
        for version, (_, forge, _, _) in VERSIONS.items():
            with self.subTest(version=version):
                meta = {'id': version + '-Forge', 'libraries': [{'name': f'net.minecraftforge:forge:{version}-{forge}'}]}
                self.assertEqual((version, forge), instances.profile_versions(meta))

    def test_legacy_agent_only_added_for_managed_targets(self):
        for file in ('epoch-legacy-agent.jar', 'epoch-legacy-window.dll'):
            path = self.root / 'host/legacy' / file
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b'component fixture')
        for version in ('1.7.10', '1.12.2'):
            with self.subTest(version=version):
                entry = self.fixture(version)
                seed = dict(gameDir='source', worldName='Source Save', username='FixturePlayer',
                            uuid='0a34619b-ecaa-4451-b3c1-c7557857423b', sessionId='fixture-session')
                with contextlib.redirect_stdout(io.StringIO()):
                    argv = launch.resolve(version, entry, seed=seed)['argv']
                self.assertIn('-javaagent:' + str(self.root / 'host/legacy/epoch-legacy-agent.jar'), argv)
                self.assertIn('-Depoch.legacyNative=' + str(self.root / 'host/legacy/epoch-legacy-window.dll'), argv)
                self.assertIn('-Depoch.managed=true', argv)
                self.assertFalse(any('org.lwjgl.glfw.libname' in arg for arg in argv))
                self.assertEqual('FixturePlayer', argv[argv.index('--username') + 1])
                self.assertNotIn('Source Save', [p.name for p in (Path(entry['gameDir']) / 'saves').iterdir()])

    def test_mcmod_info_array_and_container_detection(self):
        for index, metadata in enumerate([
            [{'modid': 'epoch', 'version': '0.3.0'}],
            {'modList': [{'modid': 'other'}, {'modid': 'epoch'}]},
            [{'modid': 'unrelated'}], {'modList': []}, None,
        ]):
            with self.subTest(metadata=metadata):
                path = self.root / f'mod-{index}.jar'
                with zipfile.ZipFile(path, 'w') as jar:
                    jar.writestr('mcmod.info', json.dumps(metadata))
                self.assertEqual(index < 2, instances.is_epoch(path))

    def test_malformed_mcmod_info_does_not_break_adapter_discovery(self):
        for index, metadata in enumerate([{'modList': None}, {'modList': 7}, '{not-json}']):
            with self.subTest(metadata=metadata):
                path = self.root / f'invalid-{index}.jar'
                with zipfile.ZipFile(path, 'w') as jar:
                    jar.writestr('mcmod.info', metadata if isinstance(metadata, str) else json.dumps(metadata))
                self.assertFalse(instances.is_epoch(path))

    def test_five_save_versions_have_matching_dataversion_and_player_dimension(self):
        for version, (_, _, expected, _) in VERSIONS.items():
            with self.subTest(version=version):
                data, folder = self.save(version)
                player = data.value['Player'].value
                if version == '1.7.10':
                    self.assertNotIn('DataVersion', data.value)
                    self.assertNotIn('Version', data.value)
                else:
                    self.assertEqual(expected, data.value['DataVersion'].value)
                    self.assertEqual(expected, data.value['Version'].value['Id'].value)
                    self.assertEqual(version, data.value['Version'].value['Name'].value)
                if version in ('1.7.10', '1.12.2'):
                    self.assertEqual((3, 0), (player['Dimension'].kind, player['Dimension'].value))
                    self.assertNotIn('UUID', player)
                    self.assertEqual(4, player['UUIDMost'].kind)
                    self.assertEqual(4, player['UUIDLeast'].kind)
                else:
                    self.assertEqual((8, 'minecraft:overworld'), (player['Dimension'].kind, player['Dimension'].value))
                    self.assertEqual(11, player['UUID'].kind)
                    self.assertEqual(4, len(player['UUID'].value))
                self.assertEqual([0.5, 64.0, -3.5], player['Pos'].plain())
                self.assertEqual('version:' + str(expected), (folder / 'options.txt').read_text().splitlines()[0])

    def test_flat_world_format_and_floor_height_for_all_versions(self):
        for version in VERSIONS:
            with self.subTest(version=version):
                tagged, _ = self.save(version)
                data = tagged.plain()
                if version in ('1.7.10', '1.12.2'):
                    self.assertNotIn('WorldGenSettings', data)
                    self.assertEqual('flat', data['generatorName'])
                    expected = ('2;7,62x3,2;1;' if version == '1.7.10'
                                else '3;minecraft:bedrock,62*minecraft:dirt,minecraft:grass;1;')
                    self.assertEqual(expected, data['generatorOptions'])
                    self.assertEqual(0, data['MapFeatures'])
                else:
                    settings = data['WorldGenSettings']['dimensions']['minecraft:overworld']['generator']
                    self.assertEqual('minecraft:flat', settings['type'])
                    flat = settings['settings']
                    self.assertEqual('minecraft:plains', flat['biome'])
                    self.assertEqual('minecraft:grass_block', flat['layers'][-1]['block'])
                    minimum_y = -64 if version in ('1.18.2', '1.20.1') else 0
                    self.assertEqual(63, minimum_y + sum(row['height'] for row in flat['layers']) - 1)

    def test_create_preserves_existing_world_and_options_for_each_version(self):
        for version in VERSIONS:
            with self.subTest(version=version):
                folder = self.minecraft / 'versions' / version
                world = folder / 'saves' / 'Existing world'
                world.mkdir(parents=True)
                (world / 'level.dat').write_bytes(b'user world sentinel')
                (folder / 'options.txt').write_bytes(b'fullscreen:true\r\nrenderDistance:12\r\n')
                with contextlib.redirect_stdout(io.StringIO()):
                    lab.create(version, folder, world_name='Existing world')
                self.assertEqual(b'user world sentinel', (world / 'level.dat').read_bytes())
                self.assertEqual(b'fullscreen:true\r\nrenderDistance:12\r\n', (folder / 'options.txt').read_bytes())


if __name__ == '__main__':
    unittest.main(verbosity=2)
