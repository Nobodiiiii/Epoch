"""Launch existing Forge profiles or coordinate a running Epoch instance."""
import argparse, json, os, pathlib, re, subprocess, uuid, zipfile, sys, time, hashlib, shlex
from contextlib import contextmanager
from epoch_instances import discover, read_profile, profile_versions, SUPPORTED, install_adapter, atomic_json, load_json, inside
from prepare_lab import create, force_windowed
from prepare_transfer import prepare as prepare_transfer

ROOT = pathlib.Path(__file__).resolve().parents[1]
MINECRAFT = ROOT / '.minecraft'
JAVA17 = pathlib.Path(r'C:\Program Files\Java\jdk-17.0.15.6-hotspot\bin')
JAVA8 = pathlib.Path(r'C:\Users\Nobodiiiii\.jdks\corretto-1.8.0_492\bin')

def allowed(obj):
    if 'rules' not in obj: return True
    result = False
    for rule in obj['rules']:
        system = rule.get('os', {})
        if system.get('name', 'windows') != 'windows': continue
        if system.get('arch', 'x86_64') not in ('x86_64', 'amd64'): continue
        if any(rule.get('features', {}).values()): continue
        result = rule['action'] == 'allow'
    return result

def streaming_binding(seed):
    control = seed.get('controlDir')
    if not control:
        if not seed.get('instanceId'):
            return None
        control = ROOT / '.minecraft' / 'epoch' / 'control' / seed['instanceId']
    control = pathlib.Path(control)
    try:
        diagnostics = load_json(control / 'status.json').get('diagnostics', {})
    except FileNotFoundError:
        return None
    if diagnostics.get('streamingProtocol') != 3:
        return None
    binding = pathlib.Path(diagnostics['streamingBinding']).resolve()
    if not inside(binding, seed['gameDir']):
        raise ValueError('Source authority binding is outside its game directory')
    value = load_json(binding)
    if value.get('schema') != 3 or value.get('worldId') != diagnostics.get('streamingWorldId'):
        raise ValueError('Source authority binding does not match its running world')
    return binding

def saved_target_world(game, binding_file, fallback):
    game = pathlib.Path(game).resolve()
    binding = load_json(binding_file)
    source = pathlib.Path(binding['sourceWorld']).resolve()
    if source.parent == game / 'saves':
        return source.name, True
    for candidate in (game / 'saves').glob('*/epoch/authority.json'):
        try:
            if load_json(candidate).get('worldId') == binding['worldId']:
                return candidate.parent.parent.name, False
        except (OSError, ValueError):
            continue
    return fallback, False


def resolve(version, entry, seed=None, standalone=False, lab=False):
    name = version + '-Forge'
    profile = pathlib.Path(entry['versionJson'])
    data = read_profile(profile)
    game = pathlib.Path(entry['gameDir'])
    libraries = pathlib.Path(entry['libraryRoot'])
    assets = pathlib.Path(entry['assetsRoot'])
    native = game / 'natives'
    native.mkdir(parents=True, exist_ok=True)
    control = MINECRAFT / 'epoch' / 'control' / entry['id']
    control.mkdir(parents=True, exist_ok=True)
    cp = []
    for lib in data['libraries']:
        if not allowed(lib): continue
        if 'natives-' in lib['name'] and not lib['name'].endswith('natives-windows'): continue
        download = lib.get('downloads', {})
        artifact = download.get('artifact')
        if artifact and artifact.get('size')!=22:
            path = libraries / artifact['path']
        elif lib.get('natives') and download.get('classifiers'):
            path = None
        else:
            parts = lib['name'].split(':')
            path = libraries / parts[0].replace('.', '/') / parts[1] / parts[2] / (parts[1]+'-'+parts[2]+('-'+parts[3] if len(parts)>3 else '')+'.jar')
        if path is not None:
            if not path.exists(): raise FileNotFoundError(path)
            cp.append(str(path))
        classifier = lib.get('natives', {}).get('windows')
        if classifier:
            item = download['classifiers'][classifier.replace('${arch}', '64')]
            with zipfile.ZipFile(libraries / item['path']) as archive:
                for zip_entry in archive.infolist():
                    if zip_entry.filename.lower().endswith('.dll'):
                        (native / pathlib.Path(zip_entry.filename).name).write_bytes(archive.read(zip_entry))
    jar = pathlib.Path(data.get('_baseJar',str(profile.with_suffix('.jar'))))
    if jar.exists(): cp.append(str(jar))
    variables = dict(auth_player_name='EpochPlayer',version_name=name,game_directory=str(game),assets_root=str(assets),assets_index_name=data['assetIndex']['id'],auth_uuid=uuid.UUID(bytes=__import__('hashlib').md5(b'OfflinePlayer:EpochPlayer').digest(),version=3).hex,auth_access_token='0',clientid='',auth_xuid='',user_type='legacy',version_type='Epoch',natives_directory=str(native),launcher_name='Epoch',launcher_version='0.2',classpath=';'.join(cp),classpath_separator=';',library_directory=str(libraries),primary_jar_name=jar.name,user_properties='{}',resolution_width='1280',resolution_height='720')
    variables.update(assets_root=str(assets),library_directory=str(libraries),version_name=data.get('id',name))
    if seed: variables.update(auth_player_name=seed['username'],auth_uuid=seed['uuid'].replace('-',''))
    def expand(value):
        value = re.sub(r'\$\{([^}]+)\}', lambda m:variables[m[1]], value)
        return value.replace('jna-5.10.0.jar,jna-platform-5.10.0.jar','jna-5.12.1.jar,jna-platform-5.12.1.jar')
    def arguments(items):
        result=[]
        for item in items:
            if isinstance(item,dict):
                if not allowed(item): continue
                item=item['value']
            result.extend(expand(x) for x in (item if isinstance(item,list) else [item]))
        return result
    legacy=version in ('1.7.10','1.12.2')
    argv=[str((JAVA17 if version in ('1.18.2','1.20.1') else JAVA8)/'javaw.exe'),'-Xms512M','-Xmx3G','-Depoch.projectRoot='+str(ROOT)]
    if seed:
        binding = streaming_binding(seed)
        if binding and version not in ('1.7.10', '1.12.2', '1.16.5', '1.18.2', '1.20.1'):
            raise ValueError('This target adapter has not yet enabled streaming terrain')
        world='Epoch Transfer '+hashlib.sha256((seed['gameDir']+'|'+seed['worldName']).encode()).hexdigest()[:16]
        # Never adopt snapshot-only caches as newly generated authority terrain.
        # Preserve those saves, including any edits made there before streaming was enabled.
        legacy_save = game / 'saves' / world
        if binding and (legacy_save / 'level.dat').exists() and not (legacy_save / 'epoch' / 'authority.json').exists():
            world += ' Stream ' + load_json(binding)['worldId']
        if binding:
            world, original = saved_target_world(game, binding, world)
            if not original:
                prepare_transfer(version, game, world, seed['username'], seed['uuid'], binding)
        else:
            create(version,folder=game,world_name=world,username=seed['username'],player_uuid=seed['uuid'])
        force_windowed(game)
        argv+=['-Depoch.autoStart=true','-Depoch.world='+world,'-Depoch.managed=true','-Depoch.sessionId='+seed['sessionId'],'-Depoch.instanceId='+entry['id'],'-Depoch.controlDir='+str(control)]
        if binding:
            argv+=['-Depoch.streaming=true','-Depoch.authorityBinding='+str(binding)]
    elif standalone and lab:
        create(version,folder=game,world_name='Epoch Lab')
        argv+=['-Depoch.autoStart=true','-Depoch.world=Epoch Lab']
    glfw = ROOT / 'host' / 'native' / ('epoch-glfw-child.dll' if seed else 'epoch-glfw.dll')
    if not standalone and not legacy and not glfw.is_file():
        raise FileNotFoundError('Epoch requires its cross-process-safe GLFW build: '+str(glfw))
    if not standalone and legacy:
        agent=ROOT/'host/legacy/epoch-legacy-agent.jar';native_hook=ROOT/'host/legacy/epoch-legacy-window.dll'
        if not agent.is_file() or not native_hook.is_file():raise FileNotFoundError('Missing Epoch LWJGL 2 window components')
        argv+=['-javaagent:'+str(agent),'-Depoch.legacyNative='+str(native_hook)]
    elif not standalone: argv += ['-Dorg.lwjgl.glfw.libname='+str(glfw)]
    jvm=data.get('arguments',{}).get('jvm') or ['-Djava.library.path=${natives_directory}','-cp','${classpath}']
    game_args=data.get('arguments',{}).get('game') or shlex.split(data.get('minecraftArguments',''))
    argv += arguments(jvm) + [data['mainClass']] + arguments(game_args)
    argv += ['--width','1280','--height','720']
    launch={'argv':argv,'cwd':str(game)}
    atomic_json(control/'launch.json',launch)
    return launch

@contextmanager
def bootstrap_lock(timeout=90):
    """Serialize helper metadata publication until a launched host owns its session."""
    import msvcrt
    path=ROOT/'.minecraft/epoch/bootstrap.lock'
    path.parent.mkdir(parents=True,exist_ok=True)
    with open(path,'a+b') as stream:
        if path.stat().st_size==0:stream.write(b'\0');stream.flush()
        end=time.monotonic()+timeout
        while True:
            try:
                stream.seek(0);msvcrt.locking(stream.fileno(),msvcrt.LK_NBLCK,1)
                break
            except OSError:
                if time.monotonic()>=end:raise TimeoutError('另一个 Epoch 启动/检测操作尚未完成')
                time.sleep(.1)
        try:yield
        finally:
            stream.seek(0);msvcrt.locking(stream.fileno(),msvcrt.LK_UNLCK,1)


def host_lock_busy():
    """Probe the same byte range protected by Java FileChannel's host.lock."""
    import msvcrt
    path=ROOT/'.minecraft/epoch/host.lock'
    path.parent.mkdir(parents=True,exist_ok=True)
    with open(path,'a+b') as stream:
        try:
            stream.seek(0);msvcrt.locking(stream.fileno(),msvcrt.LK_NBLCK,1)
        except OSError:return True
        stream.seek(0);msvcrt.locking(stream.fileno(),msvcrt.LK_UNLCK,1)
        return False


def host_status():
    try:return load_json(ROOT/'.minecraft/epoch/host-status.json')
    except (OSError,ValueError):return {}


def fresh_host(status,session_id):
    age=time.time()*1000-status.get('heartbeat',0)
    return status.get('sessionId')==session_id and -5000<=age<15000


def clear_bootstrap_error(seed_path,session_id):
    error_path=pathlib.Path(seed_path).parent/'bootstrap-error.json'
    try:
        if load_json(error_path).get('sessionId')==session_id:error_path.unlink()
    except (OSError,ValueError):pass


def session_catalog(seed_path,locked=False):
    if not locked:
        with bootstrap_lock():return session_catalog(seed_path,locked=True)
    seed_path=pathlib.Path(seed_path).resolve()
    if not inside(seed_path,ROOT/'.minecraft'/'epoch'/'control'):raise ValueError('Invalid seed location')
    seed=load_json(seed_path)
    if not inside(seed['gameDir'],ROOT):raise ValueError('Source outside Epoch directory')
    status=host_status()
    if host_lock_busy() and status.get('sessionId')!=seed['sessionId']:
        raise RuntimeError('另一个 Epoch 宿主仍持有会话锁，请先关闭其原始实例；错误状态不会释放会话所有权')
    seed.update(seedPath=str(seed_path),pythonExecutable=sys.executable)
    atomic_json(seed_path,seed)
    catalog=discover(ROOT,seed)
    atomic_json(ROOT/'.minecraft/epoch/catalog.json',catalog)
    return seed,catalog


def adopt(seed_path):
    # Keep the bootstrap lock through the first confirmed host heartbeat. A
    # second source cannot replace catalog.json in the gap before host.lock.
    with bootstrap_lock():
        seed,catalog=session_catalog(seed_path,locked=True)
        if host_lock_busy():
            status=host_status()
            if fresh_host(status,seed['sessionId']) and status.get('phase') not in ('STOPPED','STOPPING','ERROR'):
                clear_bootstrap_error(seed_path,seed['sessionId'])
                print('Epoch coordinator already owns this session')
                return
            raise RuntimeError('当前 Epoch 宿主持锁但未响应；请关闭原始实例后重试')
        classes,cp=build_host()
        started=int(time.time()*1000)
        process=subprocess.Popen([str(JAVA17/'javaw.exe'),'-cp',str(classes)+';'+cp,'EpochHost',str(ROOT),'--adopt',str(pathlib.Path(seed_path).resolve())],cwd=ROOT)
        end=time.monotonic()+30
        while time.monotonic()<end:
            if process.poll() is not None:raise RuntimeError('Epoch 宿主启动失败，退出码 '+str(process.returncode))
            status=host_status()
            if fresh_host(status,seed['sessionId']) and status.get('heartbeat',0)>=started and host_lock_busy():
                if status.get('phase') not in ('STOPPED','STOPPING','ERROR'):
                    clear_bootstrap_error(seed_path,seed['sessionId'])
                    print('Adopted coordinator PID:',process.pid)
                    return
                raise RuntimeError('Epoch 宿主初始化失败：'+str(status.get('error') or status.get('phase')))
            time.sleep(.1)
        # An uncertain host may still own its root. Never kill that user's root
        # or start a second coordinator; future helpers also check host.lock.
        raise TimeoutError('Epoch 宿主未在时限内确认会话；请查看 host-status.json 和 host.log')

def build_host():
    libraries=MINECRAFT/'libraries'
    jars=[libraries/'net/java/dev/jna/jna/5.12.1/jna-5.12.1.jar',libraries/'net/java/dev/jna/jna-platform/5.12.1/jna-platform-5.12.1.jar',libraries/'com/google/code/gson/gson/2.10.1/gson-2.10.1.jar']
    for jar in jars:
        if not jar.is_file():raise FileNotFoundError(jar)
    classes=ROOT/'host'/'build';classes.mkdir(parents=True,exist_ok=True)
    cp=';'.join(map(str,jars))
    subprocess.run([str(JAVA17/'javac.exe'),'-encoding','UTF-8','-cp',cp,'-d',str(classes),str(ROOT/'host'/'EpochHost.java')],check=True,timeout=45)
    return classes,cp

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--prepare-only',action='store_true')
    parser.add_argument('--build-only',action='store_true')
    parser.add_argument('--adopt')
    parser.add_argument('--seed')
    parser.add_argument('--discover',action='store_true')
    parser.add_argument('--prepare-target')
    parser.add_argument('--standalone',choices=list(SUPPORTED),help='Start one installed .minecraft/versions profile at its main menu')
    parser.add_argument('--profile',help='Existing profile directory name; defaults to <version>-Forge')
    parser.add_argument('--lab',action='store_true',help='Explicitly create and enter Epoch Lab in the selected profile')
    args=parser.parse_args()
    if (args.profile or args.lab or args.prepare_only) and not args.standalone:
        parser.error('--profile, --lab and --prepare-only require --standalone')
    if (args.discover or args.prepare_target) and not args.seed:
        parser.error('--discover and --prepare-target require --seed')
    if args.discover or args.prepare_target:
        with bootstrap_lock():
            seed,catalog=session_catalog(args.seed,locked=True)
            if args.prepare_target:
                entry=next((x for x in catalog['instances'] if x['id']==args.prepare_target),None)
                if entry is None or not entry['supported'] or entry['id']==seed['instanceId']:raise ValueError('Target unavailable')
                install_adapter(ROOT,entry)
                resolve(entry['version'],entry,seed)
            clear_bootstrap_error(args.seed,seed['sessionId'])
        return
    if args.standalone:
        from epoch_instances import instance_id
        name=args.profile or args.standalone+'-Forge'
        if pathlib.Path(name).name!=name or any(x in name for x in ('/','\\',':')):
            raise ValueError('Profile must be a directory name under .minecraft/versions')
        game=(MINECRAFT/'versions'/name).resolve()
        profile=game/(name+'.json')
        if not inside(game,MINECRAFT/'versions') or not profile.is_file():
            raise FileNotFoundError('Existing Forge profile required: '+str(profile))
        installed_version,forge=profile_versions(read_profile(profile))
        if installed_version!=args.standalone or not re.fullmatch(r'\d+(?:\.\d+)*',forge) or int(forge.split('.')[0])!=SUPPORTED[args.standalone]:
            raise ValueError('Selected profile does not provide the requested Forge version')
        seed=dict(instanceId=instance_id(game,args.standalone),gameDir=str(game),version=args.standalone,sessionId='standalone-discovery')
        entry=next((x for x in discover(ROOT,seed)['instances'] if x['id']==seed['instanceId'] and pathlib.Path(x.get('versionJson','')).resolve()==profile),None)
        if entry is None or not entry['supported']:
            raise ValueError('Selected profile does not provide the requested Forge version')
        install_adapter(ROOT,entry)
        launch=resolve(args.standalone,entry,standalone=True,lab=args.lab)
        if args.prepare_only:
            print('Prepared existing profile:',game)
            return
        output=open(game/'epoch-standalone.log','a',encoding='utf8')
        process=subprocess.Popen(launch['argv'],cwd=launch['cwd'],stdout=output,stderr=output)
        print('Standalone Minecraft PID:',process.pid)
        return
    if args.adopt:
        adopt(args.adopt)
        return
    if args.build_only:
        build_host()
        return
    parser.error('Start Minecraft from HMCL, or use --standalone VERSION for an existing profile')

if __name__=='__main__':
    try:main()
    except Exception as error:
        for flag in (() if '--prepare-target' in sys.argv else ('--adopt','--seed')):
            if flag in sys.argv:
                try:
                    path=pathlib.Path(sys.argv[sys.argv.index(flag)+1]);seed=load_json(path)
                    atomic_json(path.parent/'bootstrap-error.json',dict(sessionId=seed['sessionId'],error=str(error),timestamp=int(time.time()*1000)))
                except Exception:pass
                break
        raise
