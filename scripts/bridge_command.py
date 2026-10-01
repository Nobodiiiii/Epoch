"""Local test CLI for the same IPC used by the in-game phase key and host."""
import argparse,json,os,time,uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]/'.minecraft'/'epoch'
def command(version,action,path=None,timeout=60):
    control=ROOT/'control'/version
    identity=str(uuid.uuid4())
    request={'id':identity,'action':action}
    if path:request['path']=str(Path(path).resolve())
    dest=control/'command.json'
    temp=dest.with_suffix('.test.tmp')
    temp.write_text(json.dumps(request),encoding='utf8');os.replace(temp,dest)
    end=time.monotonic()+timeout
    while time.monotonic()<end:
        try:
            reply=json.loads((control/'status.json').read_text(encoding='utf8'))
            if reply.get('commandId')==identity:
                if reply.get('phase')=='ERROR':raise RuntimeError(reply)
                return reply
        except (FileNotFoundError,json.JSONDecodeError):pass
        time.sleep(.1)
    raise TimeoutError(f'{version} {action} no acknowledgement')
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('version');p.add_argument('action');p.add_argument('--path');args=p.parse_args()
    print(json.dumps(command(args.version,args.action,args.path),ensure_ascii=False,indent=2))
