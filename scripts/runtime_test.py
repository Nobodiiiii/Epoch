"""Deterministic checks against actual running Minecraft clients, never mocks."""
import argparse,gzip,json,os,time,uuid
from pathlib import Path
from bridge_command import command,ROOT
EVIDENCE=ROOT/'evidence'
def read(path):return json.loads(path.read_text(encoding='utf8'))
def wait(version=None,timeout=45):
    end=time.monotonic()+timeout
    while time.monotonic()<end:
        try:
            state=read(ROOT/'host-status.json')
            if state['phase']=='READY' and (version is None or state['activeVersion']==version):return state
            if state['phase'] in ('ERROR','DEGRADED'):raise RuntimeError(state)
        except (FileNotFoundError,json.JSONDecodeError):pass
        time.sleep(.2)
    raise TimeoutError(f'Host not ready for {version}')
def save(name,value):
    EVIDENCE.mkdir(parents=True,exist_ok=True)
    (EVIDENCE/(name+'.json')).write_text(json.dumps(value,indent=2,ensure_ascii=False),encoding='utf8')
def host_command(action):
    request={'id':str(uuid.uuid4()),'action':action}
    temp=ROOT/'host-command.test.tmp';temp.write_text(json.dumps(request),encoding='utf8');os.replace(temp,ROOT/'host-command.json')
def check(stage):
    version='1.16.5' if stage=='legacy' else '1.20.1'
    state=wait(version)
    probe=command(version,'probe')
    info=probe['diagnostics']
    with gzip.open(ROOT/'session'/'snapshot.json.gz','rt',encoding='utf8') as f:snapshot=json.load(f)
    expected=[snapshot[k] for k in ('x','y','z','yaw','pitch')]
    assert all(abs(a-b)<.002 for a,b in zip(expected,info['camera'])),(expected,info['camera'])
    baseline=read(EVIDENCE/'baseline.json')
    assert state['hostHwnd']==baseline['host']['hostHwnd'],'Top-level HWND changed'
    assert 'minecraft:chest' in info['block_-1'] and 'Epoch NBT sentinel' in info['nbt_-1'] and 'Count:7b' in info['nbt_-1'],info
    assert info.get('itemEntityCount',0)==0,'Stale container contents were dropped'
    if stage=='legacy':
        for coord in ('0','2'):
            assert 'epoch:unknown_block' in info['block_'+coord],info
            assert 'OriginalState' in info['nbt_'+coord],info
        assert 'minecraft:cherry_planks' in info['nbt_0']
        assert 'minecraft:chiseled_bookshelf' in info['nbt_2']
        assert 'Across versions, this page survives.' in info['nbt_2']
    else:
        assert 'minecraft:chiseled_bookshelf' in info['block_2'],info
        assert 'Across versions, this page survives.' in info['nbt_2']
        assert info['nbt_2']==baseline['probe']['diagnostics']['nbt_2'],'Bookshelf original NBT changed'
        if stage=='restored':
            assert 'minecraft:cherry_planks' in info['block_0']
            assert 'minecraft:stone' in info['block_1']
        else:
            assert 'minecraft:air' in info['block_0'],'Destroyed unknown resurrected'
            assert 'minecraft:dirt' in info['block_1'],'Legacy build edit lost'
    result={'stage':stage,'host':state,'probe':probe,'expectedCamera':expected,'passed':True}
    save(stage,result);print(json.dumps(result,indent=2,ensure_ascii=False))
def usekey():
    state=wait();source=state['activeVersion'];target='1.16.5' if source=='1.20.1' else '1.20.1'
    end=time.monotonic()+5
    while read(ROOT/'control'/source/'status.json').get('diagnostics',{}).get('clientFrozen',True):
        if time.monotonic()>end:raise TimeoutError('Client input remained frozen')
        time.sleep(.1)
    before=read(ROOT/'control'/source/'status.json').get('diagnostics',{}).get('phaseKeyUseCount',0)
    request={'id':str(uuid.uuid4()),'action':'usekey'}
    dest=ROOT/'control'/source/'command.json';temp=dest.with_suffix('.item-test.tmp')
    temp.write_text(json.dumps(request),encoding='utf8');os.replace(temp,dest)
    result=wait(target)
    after=read(ROOT/'control'/source/'status.json').get('diagnostics',{}).get('phaseKeyUseCount',0)
    assert after>before,'Actual registered item handler did not execute'
    assert state['hostHwnd']==result['hostHwnd'],'Host window changed'
    save('item-'+source,{'source':source,'countBefore':before,'countAfter':after,'host':result,'passed':True})
    print(json.dumps(result))
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('action',choices=['setup','wait','switch','usekey','legacy','restored','edited','edit','shutdown']);args=parser.parse_args()
    if args.action=='wait':print(json.dumps(wait()))
    elif args.action=='setup':
        state=wait('1.20.1');command('1.20.1','testsetup');time.sleep(.5);save('baseline',{'host':state,'probe':command('1.20.1','probe')});print('Fixture prepared and baseline recorded')
    elif args.action=='switch':
        state=wait();target='1.16.5' if state['activeVersion']=='1.20.1' else '1.20.1';host_command('switch');print(json.dumps(wait(target)))
    elif args.action=='usekey':usekey()
    elif args.action=='edit':
        wait('1.16.5');print(json.dumps(command('1.16.5','testedit'),indent=2))
    elif args.action=='shutdown':host_command('shutdown')
    else:check(args.action)
