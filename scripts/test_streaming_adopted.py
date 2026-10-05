"""Exercise the real adopted host with isolated streaming saves and owned processes."""
import argparse
import hashlib
import json
import subprocess
import time
import uuid
from pathlib import Path
from test_streaming_handoff import Client
from epoch_instances import atomic_json, instance_id
from launch_epoch import ROOT, JAVA17, build_host, bootstrap_lock, host_lock_busy, session_catalog


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def run(target_version, native_terrain=False):
    epoch=ROOT/'.minecraft'/'epoch'
    if host_lock_busy():
        raise RuntimeError('An existing host owns the session; this test will not replace it')
    evidence=epoch/'evidence'/('streaming-adopted-'+uuid.uuid4().hex)
    evidence.mkdir(parents=True)
    print('Evidence: '+str(evidence),flush=True)
    source=None
    host=None
    session=uuid.uuid4().hex
    report={'passed':False,'sessionId':session,'nativeTerrain':native_terrain}
    def host_wait(predicate,timeout=300):
        deadline=time.monotonic()+timeout
        last={}
        while time.monotonic()<deadline:
            if host.poll() is not None:raise RuntimeError('Host exited: '+str(host.returncode))
            try:
                last=read(epoch/'host-status.json')
                if last.get('sessionId')==session:
                    if last.get('error') or last.get('phase') in ('ERROR','DEGRADED'):
                        raise RuntimeError('Host failed: '+str(last))
                    if predicate(last):return last
            except (FileNotFoundError,PermissionError,json.JSONDecodeError):pass
            time.sleep(.2)
        raise TimeoutError('Host did not settle: '+str(last))
    try:
        source=Client('1.20.1',evidence,'source',native_terrain=native_terrain)
        source.ready()
        source.settle(source.command('streamtest','STREAM_TESTED'))
        state=source.command('streamedit','STREAM_INSPECTED')
        run_info=read(source.directory/'run.json')
        game=Path(run_info['gameDir'])
        source_id=instance_id(game,'1.20.1')
        target_id=instance_id(ROOT/'.minecraft'/'versions'/(target_version+'-Forge'),target_version)
        seed_path=epoch/'control'/('streaming-adopted-'+session)/'seed.json'
        seed=dict(sessionId=session,instanceId=source_id,version='1.20.1',gameDir=str(game),
                  controlDir=str(source.control),worldName=run_info['world'],windowHandle=state['windowHandle'],
                  pid=source.process.pid,username='EpochPlayer',
                  uuid=uuid.UUID(bytes=hashlib.md5(b'OfflinePlayer:EpochPlayer').digest(),version=3).hex,
                  projectRoot=str(ROOT),createdAt=int(time.time()*1000))
        atomic_json(seed_path,seed)
        classes,cp=build_host()
        with bootstrap_lock():
            if host_lock_busy():raise RuntimeError('Another host started during test setup')
            for relative in ('catalog.json','host-status.json','session/session.json','host-command.json'):
                original=epoch/relative
                if original.is_file():
                    backup=evidence/'previous-state'/relative
                    backup.parent.mkdir(parents=True,exist_ok=True);backup.write_bytes(original.read_bytes())
            session_catalog(seed_path,locked=True)
            with (evidence/'host-console.log').open('w',encoding='utf-8') as output:
                host=subprocess.Popen([str(JAVA17/'javaw.exe'),'-cp',str(classes)+';'+cp,'EpochHost',str(ROOT),'--adopt',str(seed_path)],cwd=ROOT,stdout=output,stderr=subprocess.STDOUT)
            initial=host_wait(lambda value:value.get('phase')=='READY',60)
        original_window=initial['hostHwnd']
        assert original_window==seed['windowHandle']
        def switch(target):
            atomic_json(epoch/'host-command.json',dict(id=uuid.uuid4().hex,action='switch',targetId=target))
            result=host_wait(lambda value:value.get('phase')=='READY' and value.get('activeInstanceId')==target)
            assert result['hostHwnd']==original_window and result.get('adoptedRoot') is True
            assert result['rootInstanceId']==source_id
            return result
        report['outbound']=switch(target_id)
        control=epoch/'control'/target_id
        identity=uuid.uuid4().hex
        atomic_json(control/'command.json',dict(id=identity,action='probe'))
        deadline=time.monotonic()+30
        while time.monotonic()<deadline:
            try:
                target=read(control/'status.json')
                if target.get('commandId')==identity:break
            except (FileNotFoundError,PermissionError,json.JSONDecodeError):pass
            time.sleep(.1)
        else:raise TimeoutError('Target probe was not acknowledged')
        assert target.get('phase')=='PROBED',target
        initial_ticks=target['diagnostics']['streamingTicks']
        deadline=time.monotonic()+60
        while time.monotonic()<deadline:
            if host.poll() is not None:raise RuntimeError('Host exited before target became playable')
            try:
                target=read(control/'status.json')
                info=target.get('diagnostics',{})
                if target.get('clientReady') and info.get('clientFrozen') is False and info.get('streamingTicks',0)>=initial_ticks+40:
                    break
            except (FileNotFoundError,PermissionError,json.JSONDecodeError):pass
            time.sleep(.2)
        else:raise TimeoutError('Target did not release input and advance 40 ticks')
        report['targetInputReleased']=True
        diagnostics=target['diagnostics']
        if target_version=='1.7.10':
            assert diagnostics.get('clientReceivedNearbyChunks')==9,diagnostics
        assert diagnostics['streamingWorldId']==state['diagnostics']['streamingWorldId']
        camera=diagnostics['camera']
        assert abs(camera[0]+1596.5)<.01 and abs(camera[2]-4805.5)<.01,camera
        if native_terrain:
            assert state['diagnostics']['streamingInspect'].get('surfaceView') is True,state
            assert abs(camera[1]-state['diagnostics']['streamingInspect']['y'])<.01,camera
        report['targetProbe']=target
        report['return']=switch(source_id)
        source.settle(source.command('probe','PROBED'))
        restored=source.command('streaminspect','STREAM_INSPECTED')['diagnostics']['streamingInspect']
        assert 'emerald_block' in restored['marker'] and 'diamond_block' in restored['origin'],restored
        assert 'cherry_planks' in restored['special'],restored
        report['restored']=restored
        report['passed']=True
    except Exception as error:
        report['error']=str(error)
        raise
    finally:
        errors=[]
        if host is not None and host.poll() is None:
            try:
                atomic_json(epoch/'host-command.json',dict(id=uuid.uuid4().hex,action='shutdown'))
                host.wait(timeout=90)
                if host.returncode!=0:errors.append('Host exit '+str(host.returncode))
                stopped=read(epoch/'host-status.json')
                if stopped.get('sessionId')!=session or stopped.get('phase')!='STOPPED' or host_lock_busy():
                    errors.append('Host did not release this test session')
                report['hostStopped']=stopped
            except Exception as error:
                errors.append('Host shutdown: '+str(error))
                # This process was created by this test. Leave the source until host releases it.
        if source is not None and (host is None or host.poll() is not None):
            try:source.stop()
            except Exception as error:errors.append('Source shutdown: '+str(error))
        if errors:report['passed']=False;report['shutdownErrors']=errors
        if (epoch/'host.log').is_file():
            (evidence/'host.log').write_bytes((epoch/'host.log').read_bytes())
        atomic_json(evidence/'result.json',report)
        print(json.dumps(report),flush=True)
        if errors:raise RuntimeError('; '.join(errors))


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--target-version',choices=['1.16.5','1.18.2','1.12.2','1.7.10'],default='1.16.5')
    parser.add_argument('--native-terrain',action='store_true')
    args=parser.parse_args()
    run(args.target_version,args.native_terrain)
