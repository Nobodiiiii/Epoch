"""Isolated discovery/deployment regression checks; no installed games are touched."""
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
import epoch_instances as api


class InstanceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='epoch-instances-')
        self.root = Path(self.temp.name).resolve()
        self.addCleanup(self.temp.cleanup)
        self.source = self.root / '.minecraft' / 'instances' / '1.20.1'
        self.source.mkdir(parents=True)
        self.seed = dict(sessionId='session-test', gameDir=str(self.source), version='1.20.1', instanceId=api.instance_id(self.source, '1.20.1'))

    def write(self, path, data):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(data), encoding='utf8')
        return path

    def profile(self, name, version='1.20.1', forge='47.3.0', **extra):
        data = dict(id=name, arguments=dict(game=['--fml.mcVersion', version, '--fml.forgeVersion', forge] if forge else []), libraries=[])
        data.update(extra)
        return self.write(self.root / 'versions' / name / (name + '.json'), data)

    def entries(self, running=None):
        return api.discover(self.root, self.seed, running=[] if running is None else running)['instances']

    def jar(self, path, mod='epoch', payload='new'):
        path.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(path, 'w') as jar:
            jar.writestr('META-INF/mods.toml', '[[mods]]\nmodId="' + mod + '"\nversion="0.2.0"\n')
            jar.writestr('payload.txt', payload)
        return path

    def target(self):
        return dict(gameDir=str(self.root / 'instances' / 'target'), version='1.16.5', supported=True)

    def test_java_compatible_normalized_ids(self):
        for path, version in [(self.source, '1.20.1'), (self.root/'instances'/'..'/'instances'/'MixedCASE', '1.16.5')]:
            normalized=str(path.resolve()).replace('\\', '/').lower()+'|'+version
            expected=version.replace('.', '-')+'-'+hashlib.sha256(normalized.encode('utf8')).hexdigest()[:12]
            self.assertEqual(expected, api.instance_id(path,version))
        if os.name=='nt':
            self.assertEqual('1-20-1-'+hashlib.sha256(b'd:/files/epoch/.minecraft/instances/1.20.1|1.20.1').hexdigest()[:12], api.instance_id('D:/Files/Epoch/.minecraft/instances/1.20.1','1.20.1'))

    def test_starting_managed_client_stays_available_before_window_attachment(self):
        self.profile('forge120')
        target=next(item for item in self.entries() if item['label']=='forge120')
        running=[dict(gameDir=target['gameDir'],pid=1234)]
        status=self.root/'.minecraft/epoch/host-status.json'
        self.write(status,dict(sessionId=self.seed['sessionId'],children={},managedProcesses={target['id']:1234}))
        self.assertTrue(next(item for item in self.entries(running) if item['id']==target['id'])['supported'])
        for session,pid in [(self.seed['sessionId'],9999),('other-session',1234)]:
            self.write(status,dict(sessionId=session,children={},managedProcesses={target['id']:pid}))
            self.assertFalse(next(item for item in self.entries(running) if item['id']==target['id'])['supported'])

    def test_inherited_forge_profile_merges_arguments_and_libraries(self):
        base=self.profile('1.16.5', '1.16.5', '', libraries=[{'name':'example:library:1'}, {'name':'example:keep:1'}], arguments={'game':['--base','yes'],'jvm':['-Dbase=true']})
        child=self.profile('forge116','1.16.5','36.2.42', inheritsFrom='1.16.5', libraries=[{'name':'example:library:2'}])
        merged=api.read_profile(child)
        self.assertEqual(('1.16.5','36.2.42'),api.profile_versions(merged))
        self.assertEqual(['--base','yes'],merged['arguments']['game'][:2])
        self.assertEqual(['-Dbase=true'],merged['arguments']['jvm'])
        self.assertEqual({'example:library:2','example:keep:1'}, {p['name'] for p in merged['libraries']})
        self.assertEqual(str(base.with_suffix('.jar')),merged['_baseJar'])

    def test_inheritance_rejects_cycles_and_traversal(self):
        a=self.profile('a',inheritsFrom='b');self.profile('b',inheritsFrom='a')
        with self.assertRaises(ValueError):api.read_profile(a)
        for parent in ('../outside','..\\outside','C:outside','a/b'):
            with self.subTest(parent=parent):
                p=self.profile('bad',inheritsFrom=parent)
                with self.assertRaises(ValueError):api.read_profile(p)

    def test_same_version_multiple_game_dirs_have_stable_unique_ids(self):
        self.profile('forge120')
        other=self.root/'instances'/'second'
        other.mkdir(parents=True)
        self.write(self.root/'launcher_profiles.json',{'profiles':{'a':{'lastVersionId':'forge120','gameDir':str(other),'name':'Second'}}})
        first=self.entries();second=self.entries()
        self.assertEqual([x['id'] for x in first],[x['id'] for x in second])
        matching=[x for x in first if x['version']=='1.20.1']
        self.assertGreaterEqual(len(matching),3)
        self.assertEqual(len(matching),len({x['id'] for x in matching}))
        self.assertTrue(next(x for x in matching if x['id']==self.seed['instanceId'])['current'])

    def test_vanilla_unsupported_and_invalid_profiles_visible_disabled(self):
        self.profile('1.16.5','1.16.5','')
        self.profile('forge1194','1.19.4','45.0.0')
        invalid=self.root/'versions'/'broken'/'broken.json';invalid.parent.mkdir(parents=True);invalid.write_text('{broken',encoding='utf8')
        entries=self.entries()
        for label in ('1.16.5','forge1194','broken'):
            item=next(x for x in entries if x['label']==label)
            self.assertFalse(item['supported']);self.assertTrue(item['reason'])

    def test_malformed_forge_version_does_not_abort_discovery(self):
        self.profile('forge120')
        self.profile('malformed', forge='invalid')
        entries=self.entries()
        item=next(x for x in entries if x['label']=='malformed')
        self.assertFalse(item['supported']);self.assertTrue(item['reason'])
        self.assertTrue(any(x['current'] for x in entries))

    def test_running_targets_disabled_except_current_or_managed(self):
        profile=self.profile('forge120')
        current={'gameDir':str(self.source),'pid':100}
        target={'gameDir':str(profile.parent),'pid':200}
        entries=self.entries([current,target]);target_id=api.instance_id(profile.parent,'1.20.1')
        self.assertTrue(next(x for x in entries if x['current'])['supported'])
        self.assertFalse(next(x for x in entries if x['id']==target_id)['supported'])
        self.write(self.root/'.minecraft'/'epoch'/'host-status.json', {'sessionId':self.seed['sessionId'],'children':{target_id:{'pid':200}}})
        self.assertTrue(next(x for x in self.entries([current,target]) if x['id']==target_id)['supported'])
        self.write(self.root/'.minecraft'/'epoch'/'host-status.json', {'sessionId':'other','children':{target_id:{}}})
        self.assertFalse(next(x for x in self.entries([current,target]) if x['id']==target_id)['supported'])

    def test_outside_launcher_directory_disabled(self):
        self.profile('forge120')
        outside=self.root.parent/(self.root.name+'-outside')
        outside.mkdir()
        self.addCleanup(outside.rmdir)
        self.write(self.root/'launcher_profiles.json',{'profiles':{'external':{'lastVersionId':'forge120','gameDir':str(outside),'name':'External'}}})
        item=next(x for x in self.entries() if x['label']=='External')
        self.assertFalse(item['supported']);self.assertTrue(item['reason'])

    def test_installs_latest_version_artifact_and_backs_up_old_epoch(self):
        old_artifact=self.jar(self.root/'forge116'/'build'/'libs'/'epoch-old.jar',payload='old-artifact')
        source=self.jar(old_artifact.with_name('epoch-new.jar'),payload='correct')
        os.utime(old_artifact,(100,100));os.utime(source,(200,200))
        self.jar(old_artifact.with_name('epoch-sources.jar'),payload='sources')
        self.jar(self.root/'forge120'/'build'/'libs'/'epoch-new.jar',payload='wrong-version')
        target=self.target();mods=Path(target['gameDir'])/'mods'
        prior=self.jar(mods/'previous.jar',payload='previous');prior_bytes=prior.read_bytes()
        unrelated=self.jar(mods/'other-mod.jar',mod='unrelated');unrelated_bytes=unrelated.read_bytes()
        installed=api.install_adapter(self.root,target,running=[])
        self.assertEqual(source.read_bytes(),installed.read_bytes())
        self.assertEqual(unrelated_bytes,unrelated.read_bytes())
        backups=list((Path(target['gameDir'])/'epoch'/'mod-backups').glob('*/previous.jar'))
        self.assertEqual(1,len(backups));self.assertEqual(prior_bytes,backups[0].read_bytes())
        self.assertEqual(installed,api.install_adapter(self.root,target,running=[]))
        self.assertEqual(1,len(list((Path(target['gameDir'])/'epoch'/'mod-backups').glob('*'))))

    def test_install_refuses_running_and_unsupported_targets(self):
        target=self.target()
        with self.assertRaises(RuntimeError):api.install_adapter(self.root,target,running=[{'gameDir':target['gameDir'],'pid':1}])
        with self.assertRaises(ValueError):api.install_adapter(self.root,dict(target,supported=False),running=[])
        with self.assertRaises(ValueError):api.install_adapter(self.root,dict(target,gameDir=str(self.root.parent)),running=[])

    def test_identical_adapter_refreshes_moved_installation_metadata(self):
        artifact=self.jar(self.root/'forge116'/'build'/'libs'/'epoch.jar')
        target=dict(gameDir=str(self.root/'.minecraft'/'versions'/'1.16.5-Forge'),version='1.16.5',supported=True)
        game=Path(target['gameDir'])
        installed=game/'mods'/'epoch.jar';installed.parent.mkdir(parents=True)
        installed.write_bytes(artifact.read_bytes())
        old_path=self.root/'versions'/'1.16.5-Forge'/'mods'/'epoch.jar'
        self.write(game/'epoch'/'adapter.json',{'installed':str(old_path),'artifact':'old-artifact'})
        result=api.install_adapter(self.root,target,running=[])
        self.assertEqual(installed,result)
        self.assertEqual(artifact.read_bytes(),installed.read_bytes())
        metadata=api.load_json(game/'epoch'/'adapter.json')
        self.assertEqual(str(installed),metadata['installed'])
        self.assertEqual(str(artifact),metadata['artifact'])
        self.assertFalse((game/'epoch'/'mod-backups').exists())

    def test_manifest_failure_rolls_back_all_old_adapters(self):
        self.jar(self.root/'forge116'/'build'/'libs'/'epoch.jar')
        target=self.target();mods=Path(target['gameDir'])/'mods'
        previous=self.jar(mods/'epoch-managed.jar',payload='original')
        extra=self.jar(mods/'old-epoch.jar',payload='older')
        originals={p.name:p.read_bytes() for p in (previous,extra)}
        with patch.object(api,'atomic_json',side_effect=PermissionError('locked manifest')):
            with self.assertRaises(PermissionError):api.install_adapter(self.root,target,running=[])
        for name,data in originals.items():self.assertEqual(data,(mods/name).read_bytes())
        self.assertEqual(set(originals),{p.name for p in mods.iterdir()})

    def test_partial_backup_failure_restores_already_moved_adapter(self):
        self.jar(self.root/'forge116'/'build'/'libs'/'epoch.jar')
        target=self.target();mods=Path(target['gameDir'])/'mods'
        first=self.jar(mods/'first.jar',payload='first')
        second=self.jar(mods/'second.jar',payload='second')
        originals={p.name:p.read_bytes() for p in (first,second)}
        original_rename=Path.rename
        def fail_second(path,destination):
            if path==second:raise PermissionError('second adapter locked')
            return original_rename(path,destination)
        with patch.object(Path,'rename',fail_second):
            with self.assertRaises(PermissionError):api.install_adapter(self.root,target,running=[])
        for name,data in originals.items():self.assertEqual(data,(mods/name).read_bytes())
        self.assertEqual(set(originals),{p.name for p in mods.iterdir()})

    def test_bad_launcher_entry_does_not_hide_valid_later_entry(self):
        self.profile('forge120')
        good=self.root/'instances'/'good'
        good.mkdir(parents=True)
        self.write(self.root/'launcher_profiles.json',{'profiles':{'bad':{'lastVersionId':'forge120','gameDir':None},'alsoBad':42,'good':{'lastVersionId':'forge120','gameDir':str(good),'name':'Good'}}})
        self.assertTrue(any(x['label']=='Good' for x in self.entries()))

    def test_migrated_hmcl_profiles_use_new_game_root_without_deleted_instances(self):
        modern=self.profile('1.20.1-Forge')
        self.profile('1.16.5-Forge','1.16.5','36.2.42')
        for directory in ('assets','libraries'):(self.root/directory).mkdir()
        self.write(self.root/'launcher_profiles.json',{'profiles':{
            'moved':{'lastVersionId':'1.20.1-Forge','gameDir':str(modern.parent),'name':'Old HMCL path'},
            'deleted':{'lastVersionId':'1.20.1-Forge','gameDir':str(self.source),'name':'Deleted lab'},
            'relative':{'lastVersionId':'1.16.5-Forge','gameDir':'versions/1.16.5-Forge','name':'HMCL 1.16.5'}
        }})
        minecraft=self.root/'.minecraft'
        for name in ('assets','libraries','versions','launcher_profiles.json'):
            (self.root/name).rename(minecraft/name)
        self.source.rmdir();self.source.parent.rmdir()
        self.source=minecraft/'versions'/'1.20.1-Forge'
        self.seed.update(gameDir=str(self.source),instanceId=api.instance_id(self.source,'1.20.1'))
        entries=self.entries()
        self.assertEqual(2,len(entries))
        self.assertEqual({'1.20.1','1.16.5'},{entry['version'] for entry in entries})
        self.assertEqual(1,sum(entry['current'] for entry in entries))
        for entry in entries:
            self.assertTrue(entry['supported'])
            self.assertTrue(api.inside(entry['gameDir'],minecraft/'versions'))
            self.assertTrue(api.inside(entry['versionJson'],minecraft/'versions'))
            self.assertEqual(str(minecraft/'libraries'),entry['libraryRoot'])
            self.assertEqual(str(minecraft/'assets'),entry['assetsRoot'])
        self.assertFalse((self.root/'versions').exists())
        self.assertFalse((minecraft/'instances').exists())
        self.assertFalse(any(entry['label'] in ('Old HMCL path','Deleted lab') for entry in entries))

    def test_unrelated_managed_filename_is_never_overwritten(self):
        self.jar(self.root/'forge116'/'build'/'libs'/'epoch.jar')
        target=self.target();collision=self.jar(Path(target['gameDir'])/'mods'/'epoch-managed.jar',mod='unrelated')
        before=collision.read_bytes()
        try:api.install_adapter(self.root,target,running=[])
        except (ValueError,FileExistsError):pass
        self.assertEqual(before,collision.read_bytes(),'Unrelated mods must survive even if their filename collides')

if __name__=='__main__':unittest.main(verbosity=2)
