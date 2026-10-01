"""Repeat actual item-driven handoffs and inspect the live server after each."""
import time
from runtime_test import usekey,wait,save,read,EVIDENCE,ROOT
from bridge_command import command
initial=wait();records=[]
for n in range(6):
    usekey();host=wait();version=host['activeVersion'];time.sleep(.7)
    probe=command(version,'probe');info=probe['diagnostics']
    assert host['hostHwnd']==initial['hostHwnd']
    assert 'minecraft:air' in info['block_0'] and 'minecraft:dirt' in info['block_1']
    assert 'Across versions, this page survives.' in info['nbt_2']
    assert info['itemEntityCount']==0
    assert all(abs(a-b)<.002 for a,b in zip(info['camera'],[.5,64.,-3.5,0.,10.])),info['camera']
    assert not info['clientFrozen']
    assert all(abs(a-b)<.002 for a,b in zip(info['clientCamera'],[.5,64.,-3.5,0.,10.])),info['clientCamera']
    if version=='1.20.1':
        assert info['nbt_2']==read(EVIDENCE/'baseline.json')['probe']['diagnostics']['nbt_2']
    else:assert 'epoch:unknown_block' in info['block_2']
    records.append({'host':host,'probe':probe})
save('cycles',{'passed':True,'count':len(records),'records':records})
print('PASS: six item-driven switches, same HWND, exact standing pose, saved book, no duplicate drops')
