"""Extract numeric vanilla block-state mappings from a local Forge decompile jar.

The map stores registry data, not Minecraft bytecode. Pass the local 1.20.1
decompile jar explicitly; no game is started and no saves are read.
"""
import argparse,json,re,zipfile
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
RENAMES={'minecraft:grass_path':'minecraft:dirt_path','minecraft:sign':'minecraft:oak_sign','minecraft:wall_sign':'minecraft:oak_wall_sign','minecraft:melon_block':'minecraft:melon'}

def extract(source):
    result={}
    for raw,text in re.findall(r'(?:m_14942_|register)\((\d+), "((?:[^"\\]|\\.)*)"',source):
        text=json.loads('"'+text+'"')
        text=re.sub(r'([,{])\s*([A-Za-z0-9_]+)\s*:',r'\1"\2":',text.replace("'",'"'))
        state=json.loads(text);state['Name']=RENAMES.get(state['Name'],state['Name'])
        result[raw]=state
    if len(result)<1000 or result.get('32',{}).get('Name')!='minecraft:grass_block':
        raise ValueError('Unexpected BlockStateData format; refusing partial map')
    return result

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('decompile_jar',type=Path);args=parser.parse_args()
    with zipfile.ZipFile(args.decompile_jar) as jar:
        mapping=extract(jar.read('net/minecraft/util/datafix/fixes/BlockStateData.java').decode('utf8'))
    path=ROOT/'src/shared/resources/assets/epoch/legacy-blockstates.json';path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(mapping,separators=(',',':')),encoding='utf8')
    print('Wrote',len(mapping),'explicit legacy block states to',path)
