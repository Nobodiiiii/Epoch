"""Bounded local discovery and non-destructive Epoch adapter deployment."""
import hashlib,json,os,re,subprocess,sys,time,zipfile,tempfile
from pathlib import Path

SUPPORTED={'1.7.10':10,'1.12.2':14,'1.16.5':36,'1.18.2':40,'1.20.1':47}
MODULES={'1.7.10':'forge1710','1.12.2':'forge112','1.16.5':'forge116','1.18.2':'forge118','1.20.1':'forge120'}

def instance_id(game_dir,version):
    key=str(Path(game_dir).resolve()).replace('\\','/').lower()+'|'+version
    return version.replace('.','-')+'-'+hashlib.sha256(key.encode()).hexdigest()[:12]

def atomic_json(path,value):
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True)
    temp=path.with_name(path.name+'.'+str(os.getpid())+'.tmp')
    temp.write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf8')
    for attempt in range(30):
        try:os.replace(temp,path);return
        except PermissionError:
            if attempt==29:raise
            time.sleep(.01)

def load_json(path):return json.loads(Path(path).read_text(encoding='utf-8-sig'))

def inside(path,root):
    try:Path(path).resolve().relative_to(Path(root).resolve());return True
    except ValueError:return False

def read_profile(path,seen=None):
    path=Path(path).resolve();seen=set() if seen is None else seen
    if path in seen:raise ValueError('版本继承出现循环')
    seen.add(path);child=load_json(path)
    if not isinstance(child,dict):raise ValueError('版本描述必须是 JSON 对象')
    parent=child.get('inheritsFrom')
    if not parent:return child
    if not isinstance(parent,str) or Path(parent).name!=parent or any(x in parent for x in ('/','\\',':')):raise ValueError('非法继承路径')
    parent_path=path.parent.parent/parent/(parent+'.json')
    base=read_profile(parent_path,seen)
    merged={**base,**child};merged['arguments']={}
    for kind in ('game','jvm'):merged['arguments'][kind]=base.get('arguments',{}).get(kind,[])+child.get('arguments',{}).get(kind,[])
    libraries={}
    for lib in base.get('libraries',[])+child.get('libraries',[]):
        parts=lib['name'].split(':');key=':'.join(parts[:2]+parts[3:]);libraries[key]=lib
    merged['libraries']=list(libraries.values());merged['_baseJar']=base.get('_baseJar',str(parent_path.with_suffix('.jar')))
    return merged

def profile_versions(meta):
    args=[x for x in meta.get('arguments',{}).get('game',[]) if isinstance(x,str)]
    def argument(name):
        try:return args[args.index(name)+1]
        except (ValueError,IndexError):return ''
    version=argument('--fml.mcVersion');forge=argument('--fml.forgeVersion')
    if not version or not forge:
        for lib in meta.get('libraries',[]):
            match=re.match(r'net\.minecraftforge:forge:(1\.[\d.]+)-([\d.]+)',lib.get('name',''))
            if match:version,forge=match.groups();break
    if not version:
        version=str(meta.get('id',''));match=re.search(r'1\.\d+(?:\.\d+)?',version)
        if match:version=match[0]
    return version,forge

def running_instances():
    """Return only game directory and PID, never account tokens/command lines."""
    if os.name!='nt':return []
    script=r'''$result=@(); Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" | ForEach-Object { if ($_.CommandLine -match '--gameDir\s+(?:"([^"]+)"|(\S+))') { $dir=if($Matches[1]){$Matches[1]}else{$Matches[2]}; $result += [pscustomobject]@{pid=$_.ProcessId;gameDir=$dir} } }; ConvertTo-Json -InputObject @($result) -Compress'''
    result=subprocess.run(['powershell','-NoProfile','-NonInteractive','-Command',script],capture_output=True,text=True,timeout=20,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
    if result.returncode:raise RuntimeError('无法检查目标实例是否正在运行')
    value=json.loads(result.stdout.strip() or '[]');return value if isinstance(value,list) else [value]

def discover(root,seed,running=None):
    root=Path(root).resolve();running=running_instances() if running is None else running
    epoch=root/'.minecraft'/'epoch';profiles=[];errors=[]
    for versions in (root/'versions',root/'.minecraft'/'versions'):
        if not versions.is_dir():continue
        for folder in sorted(versions.iterdir()):
            if not folder.is_dir() or not inside(folder,root):continue
            path=folder/(folder.name+'.json')
            if not path.is_file():continue
            try:
                meta=read_profile(path);version,forge=profile_versions(meta)
                profiles.append((path,meta,version,forge))
            except Exception as error:errors.append({'id':'invalid-'+hashlib.sha256(str(path).encode()).hexdigest()[:12],'label':folder.name,'version':'未知','forgeVersion':'','gameDir':str(folder),'versionJson':str(path),'supported':False,'reason':str(error),'current':False})
    entries={}
    live={str(Path(p['gameDir']).resolve()).lower():int(p['pid']) for p in running}
    managed=set()
    try:
        status=load_json(epoch/'host-status.json')
        if status.get('sessionId')==seed['sessionId']:
            managed=set(status.get('children',{}));managed.add(seed['instanceId'])
    except (OSError,ValueError,KeyError,TypeError,AttributeError):pass
    def add(game_dir,profile,label=None):
        game_dir=Path(game_dir).resolve();path,meta,version,forge=profile
        identity=instance_id(game_dir,version)
        is_current=identity==seed['instanceId']
        reason=''
        if not inside(game_dir,root):reason='实例目录不在当前 Epoch 目录下'
        elif version not in SUPPORTED:reason='当前支持 Minecraft '+', '.join(SUPPORTED)
        elif not forge:reason='需要对应版本 Forge，不能在纯净/Fabric 实例中加载'
        elif not re.fullmatch(r'\d+(?:\.\d+)*',forge):reason='Forge 版本格式无效'
        elif int(forge.split('.')[0])!=SUPPORTED[version]:reason='Forge 主版本与适配器不匹配'
        elif str(game_dir).lower() in live and not is_current and identity not in managed:reason='实例正在独立运行，请先关闭该实例'
        entry=dict(label=label or game_dir.name,id=identity,version=version,forgeVersion=forge,gameDir=str(game_dir),versionJson=str(path),supported=not reason,reason=reason,current=is_current)
        entry['libraryRoot']=str(next((p/'libraries' for p in (path.parent.parent.parent,root/'.minecraft',root) if (p/'libraries').is_dir()),root/'.minecraft'/'libraries'))
        entry['assetsRoot']=str(next((p/'assets' for p in (path.parent.parent.parent,root/'.minecraft',root) if (p/'assets').is_dir()),root/'.minecraft'/'assets'))
        entry['epochInstalled']=any('epoch' in f.name.lower() for f in (game_dir/'mods').glob('*.jar'))
        entries[identity]=entry
    for profile in profiles:add(profile[0].parent,profile)
    for base in (root/'instances',root/'.minecraft'/'instances'):
        if not base.is_dir():continue
        for folder in sorted(base.iterdir()):
            if not folder.is_dir() or not inside(folder,root):continue
            marker=folder/'epoch-instance.json'
            candidates=[]
            if marker.exists():
                try:
                    marker_data=load_json(marker);candidates=[p for p in profiles if str(p[0])==str(Path(marker_data['versionJson']).resolve())]
                except (OSError,ValueError,KeyError,TypeError,AttributeError):pass
            if not candidates:candidates=[p for p in profiles if p[2]==folder.name]
            if candidates:add(folder,candidates[0],folder.name+' · '+base.parent.name)
            elif (folder/'mmc-pack.json').exists():errors.append(dict(id='unsupported-'+hashlib.sha256(str(folder).encode()).hexdigest()[:12],label=folder.name,version='未知',forgeVersion='',gameDir=str(folder),supported=False,current=False,reason='暂不支持 Prism/MultiMC 启动元数据'))
    for profile_file in (root/'launcher_profiles.json',root/'.minecraft'/'launcher_profiles.json'):
        if not profile_file.exists():continue
        try:
            launcher_profiles=load_json(profile_file).get('profiles',{})
            if not isinstance(launcher_profiles,dict):continue
        except (OSError,ValueError,TypeError,AttributeError):continue
        for profile in launcher_profiles.values():
            try:
                if not isinstance(profile,dict) or 'gameDir' not in profile:continue
                game_dir=Path(profile['gameDir'])
                if not game_dir.is_absolute():game_dir=profile_file.parent/game_dir
                # Launcher records may retain old paths after moving the game root.
                # Do not advertise those records or recreate deleted instances.
                if not game_dir.is_dir():continue
                matching=[p for p in profiles if p[1].get('id')==profile.get('lastVersionId')]
                if matching:add(game_dir,matching[0],str(profile.get('name') or ''))
            except (OSError,ValueError,TypeError,AttributeError):continue
    matching=[p for p in profiles if p[2]==seed['version'] and p[3]]
    if seed['instanceId'] not in entries and matching:add(Path(seed['gameDir']),matching[0],Path(seed['gameDir']).name+' · 当前')
    if seed['instanceId'] in entries:entries[seed['instanceId']]['current']=True;entries[seed['instanceId']]['supported']=True;entries[seed['instanceId']]['reason']=''
    result={'schema':1,'sessionId':seed['sessionId'],'sourceId':seed['instanceId'],'seedPath':seed.get('seedPath',''),'updatedAt':int(time.time()*1000),'instances':sorted(entries.values(),key=lambda x:(not x['current'],x['version'],x['label']))+errors}
    return result

def artifact(root,version):
    if version not in SUPPORTED:raise ValueError('不支持的 Minecraft 版本')
    module=MODULES[version]
    candidates=list((Path(root)/module/'build'/'libs').glob('*.jar'))
    candidates=[p for p in candidates if not any(x in p.name for x in ('-sources','-dev','-javadoc')) and is_epoch(p)]
    if not candidates:raise FileNotFoundError('缺少 '+version+' 对应的 Epoch 模组构建产物')
    return max(candidates,key=lambda p:p.stat().st_mtime)

def is_epoch(path):
    try:
        with zipfile.ZipFile(path) as jar:
            if 'META-INF/mods.toml' in jar.namelist():
                text=jar.read('META-INF/mods.toml').decode('utf8')
                return re.search(r'modId\s*=\s*[\"\']epoch[\"\']',text) is not None
            metadata=json.loads(jar.read('mcmod.info').decode('utf8'))
            entries=metadata if isinstance(metadata,list) else metadata.get('modList',[]) if isinstance(metadata,dict) else []
            if not isinstance(entries,list):return False
            return any(isinstance(entry,dict) and entry.get('modid')=='epoch' for entry in entries)
    except (OSError,KeyError,ValueError,zipfile.BadZipFile):return False

def install_adapter(root,entry,running=None):
    root=Path(root).resolve();game=Path(entry['gameDir']).resolve()
    if not entry['supported'] or not inside(game,root):raise ValueError('不允许向该实例部署模组')
    running=running_instances() if running is None else running
    if any(Path(p['gameDir']).resolve()==game for p in running):raise RuntimeError('目标实例正在运行，不能替换其模组')
    source=artifact(root,entry['version']);data=source.read_bytes();digest=hashlib.sha256(data).hexdigest()
    mods=game/'mods'
    backup=game/'epoch'/'mod-backups'/str(time.time_ns())
    if not inside(mods,root) or not inside(backup,root):raise ValueError('模组或备份目录指向 Epoch 目录之外')
    mods.mkdir(parents=True,exist_ok=True)
    destination=mods/'epoch-managed.jar'
    if destination.exists() and not is_epoch(destination):raise FileExistsError('epoch-managed.jar 已由其他模组占用')
    old=[p for p in mods.glob('*.jar') if is_epoch(p)]
    if len(old)==1 and hashlib.sha256(old[0].read_bytes()).hexdigest()==digest:
        atomic_json(game/'epoch'/'adapter.json',{'version':entry['version'],'sha256':digest,'artifact':str(source),'installed':str(old[0])})
        return old[0]
    descriptor,temp_name=tempfile.mkstemp(prefix='epoch-install-',suffix='.tmp',dir=mods)
    temp=Path(temp_name);moved=[];installed=False
    try:
        with os.fdopen(descriptor,'wb') as stream:stream.write(data)
        for path in old:
            backup.mkdir(parents=True,exist_ok=True)
            saved=backup/path.name;path.rename(saved);moved.append((path,saved))
        if destination.exists():raise FileExistsError('部署目标在安装过程中发生变化')
        temp.rename(destination);installed=True
        atomic_json(game/'epoch'/'adapter.json',{'version':entry['version'],'sha256':digest,'artifact':str(source),'installed':str(destination)})
        return destination
    except Exception:
        if installed:destination.unlink()
        for original,saved in reversed(moved):saved.rename(original)
        raise
    finally:
        if temp.exists():temp.unlink()
