"""Create projection saves in native formats; never overwrite an existing save."""
import gzip
import hashlib
import re
import struct
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def b(v): return (1, v)
def i(v): return (3, v)
def l(v): return (4, v)
def f(v): return (5, v)
def d(v): return (6, v)
def s(v): return (8, v)
def ls(t, v): return (9, (t, v))
def c(v): return (10, v)
def ia(v): return (11, v)
def string(v):
    data = v.encode('utf-8')
    return struct.pack('>H', len(data)) + data
def payload(t, v):
    if t in (1, 2, 3, 4, 5, 6): return struct.pack({1:'>b',2:'>h',3:'>i',4:'>q',5:'>f',6:'>d'}[t],v)
    if t == 8: return string(v)
    if t == 9:
        child, values = v
        return bytes([child]) + struct.pack('>i',len(values)) + b''.join(payload(child,x) for x in values)
    if t == 10: return b''.join(bytes([typ])+string(key)+payload(typ,value) for key,(typ,value) in v.items())+b'\0'
    if t == 11: return struct.pack('>i',len(v))+b''.join(struct.pack('>i',x) for x in v)
    raise ValueError(t)

def force_windowed(folder):
    """Change only fullscreen in options.txt; preserve all other user options."""
    folder = Path(folder)
    folder.mkdir(parents=True, exist_ok=True)
    optionsfile = folder / 'options.txt'
    original = optionsfile.read_bytes() if optionsfile.exists() else b''
    updated, count = re.subn(rb'(?m)^fullscreen:[^\r\n]*', b'fullscreen:false', original)
    if not count:
        newline = b'\r\n' if b'\r\n' in original else b'\n'
        updated = original + (newline if original and not original.endswith((b'\n', b'\r')) else b'') + b'fullscreen:false' + newline
    if updated != original:
        temporary = optionsfile.with_name('options.txt.epoch.tmp')
        temporary.write_bytes(updated)
        temporary.replace(optionsfile)


def native_generation(version, seed):
    """Default Overworld settings decoded by the destination's own registries/generator."""
    if version in ('1.7.10', '1.12.2'):
        return dict(generatorName=s('default'), generatorVersion=i(1), generatorOptions=s(''),
                    RandomSeed=l(seed), MapFeatures=b(1))
    biome = dict(type=s('minecraft:multi_noise'), preset=s('minecraft:overworld'))
    if version == '1.16.5':
        biome = dict(type=s('minecraft:vanilla_layered'), seed=l(seed), large_biomes=b(0))
    overworld = dict(type=s('minecraft:noise'), seed=l(seed), settings=s('minecraft:overworld'), biome_source=c(biome))
    return dict(generator=c(overworld))


def create(version, folder, world_name, username='EpochPlayer', player_uuid=None, bounded=True,
           terrain='flat', seed=0):
    versions={'1.7.10':0,'1.12.2':1343,'1.16.5':2586,'1.18.2':2975,'1.20.1':3465}
    if version not in versions:
        raise ValueError('Unsupported save format version: ' + str(version))
    if terrain not in ('flat', 'native'):
        raise ValueError('Unsupported terrain: ' + str(terrain))
    if not world_name or world_name in ('.', '..') or Path(world_name).name != world_name or '/' in world_name or '\\' in world_name:
        raise ValueError('world_name must be a single save directory name')
    modern = version in ('1.18.2','1.20.1')
    legacy = version in ('1.7.10','1.12.2')
    folder = Path(folder)
    save = folder / 'saves' / world_name
    if (save/'level.dat').exists():
        print(f'Preserved existing {save}')
        return
    save.mkdir(parents=True, exist_ok=True)
    layers = [dict(block=s('minecraft:bedrock'),height=i(1)),dict(block=s('minecraft:dirt'),height=i(126 if modern else 62)),dict(block=s('minecraft:grass_block'),height=i(1))]
    flat = dict(biome=s('minecraft:plains'),layers=ls(10,layers),lakes=b(0),features=b(0),structures=c(dict(structures=c({}))))
    if modern: flat['structure_overrides'] = ls(8,[])
    dims = {
        'minecraft:overworld':c(dict(type=s('minecraft:overworld'),generator=c(dict(type=s('minecraft:flat'),settings=c(flat))))),
        'minecraft:the_nether':c(dict(type=s('minecraft:the_nether'),generator=c(dict(type=s('minecraft:noise'),seed=l(0),settings=s('minecraft:nether'),biome_source=c(dict(type=s('minecraft:multi_noise'),preset=s('minecraft:nether'))))))),
        'minecraft:the_end':c(dict(type=s('minecraft:the_end'),generator=c(dict(type=s('minecraft:noise'),seed=l(0),settings=s('minecraft:end'),biome_source=c(dict(type=s('minecraft:the_end'),seed=l(0)))))))
    }
    if not modern:
        dims['minecraft:the_nether'][1]['generator'][1]['biome_source'][1]['seed'] = l(0)
    # Java UUID.nameUUIDFromBytes, matching offline launcher identity.
    if player_uuid is not None:
        raw = uuid.UUID(str(player_uuid)).bytes
    else:
        raw=bytearray(hashlib.md5(('OfflinePlayer:' + username).encode('utf-8')).digest());raw[6]=(raw[6]&15)|48;raw[8]=(raw[8]&63)|128
    uid=struct.unpack('>iiii',raw)
    player=dict(id=s('minecraft:player'),UUID=ia(uid),Pos=ls(6,[0.5,64.,-3.5]),Motion=ls(6,[0.,0.,0.]),Rotation=ls(5,[0.,10.]),Dimension=s('minecraft:overworld'),Health=f(20.),foodLevel=i(20),Air=(2,300),OnGround=b(1),Inventory=ls(10,[]),SelectedItemSlot=i(0),playerGameType=i(1),abilities=c(dict(walkSpeed=f(.1),flySpeed=f(.05),mayfly=b(1),flying=b(0),invulnerable=b(1),instabuild=b(1),mayBuild=b(1))))
    data=dict(DataVersion=i(3465 if modern else 2586),Version=c(dict(Id=i(3465 if modern else 2586),Name=s(version),Snapshot=b(0))),version=i(19133),LevelName=s(world_name),GameType=i(1),hardcore=b(0),allowCommands=b(1),initialized=b(1),Difficulty=b(0),DifficultyLocked=b(0),SpawnX=i(0),SpawnY=i(64),SpawnZ=i(0),Time=l(1000),DayTime=l(6000),LastPlayed=l(0),SizeOnDisk=l(0),raining=b(0),thundering=b(0),rainTime=i(1000000),thunderTime=i(1000000),clearWeatherTime=i(1000000),BorderCenterX=d(0),BorderCenterZ=d(0),BorderSize=d(56),BorderSizeLerpTarget=d(56),BorderSizeLerpTime=l(0),BorderSafeZone=d(0),BorderDamagePerBlock=d(.2),BorderWarningBlocks=d(2),BorderWarningTime=d(5),GameRules=c({name:s(value) for name,value in {'doDaylightCycle':'false','doWeatherCycle':'false','doMobSpawning':'false','randomTickSpeed':'0','keepInventory':'true','doTraderSpawning':'false','doInsomnia':'false','spawnRadius':'0'}.items()}),WorldGenSettings=c(dict(seed=l(0),generate_features=b(0),bonus_chest=b(0),dimensions=c(dims))),Player=c(player))
    data['DataVersion']=i(versions[version]);data['Version']=c(dict(Id=i(versions[version]),Name=s(version),Snapshot=b(0)))
    if not bounded:
        data['BorderSize']=d(59999968);data['BorderSizeLerpTarget']=d(59999968)
    if legacy:
        data.pop('WorldGenSettings')
        data.update(generatorName=s('flat'),generatorVersion=i(0),generatorOptions=s('2;7,62x3,2;1;' if version=='1.7.10' else '3;minecraft:bedrock,62*minecraft:dirt,minecraft:grass;1;'),RandomSeed=l(0),MapFeatures=b(0))
        player['Dimension']=i(0);player.pop('UUID');player['UUIDMost']=l(struct.unpack('>q',bytes(raw[:8]))[0]);player['UUIDLeast']=l(struct.unpack('>q',bytes(raw[8:]))[0])
        if version=='1.7.10':data.pop('DataVersion');data.pop('Version')
    if terrain == 'native':
        if legacy:
            data.update(native_generation(version, seed))
        else:
            settings = data['WorldGenSettings'][1]
            settings.update(seed=l(seed), generate_features=b(1))
            dims['minecraft:overworld'][1].update(native_generation(version, seed))
            for dimension in ('minecraft:the_nether', 'minecraft:the_end'):
                generator = dims[dimension][1]['generator'][1]
                generator['seed'] = l(seed)
                if 'seed' in generator['biome_source'][1]:
                    generator['biome_source'][1]['seed'] = l(seed)
    with gzip.open(save/'level.dat','wb') as stream:stream.write(bytes([10])+string('')+payload(10,{'Data':c(data)}))
    force_windowed(folder)
    print(f'Created {save}')

