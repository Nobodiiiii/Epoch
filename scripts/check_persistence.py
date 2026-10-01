"""Run after a deliberate shutdown on 1.16.5 and a normal launcher restart."""
from runtime_test import wait,read,save,ROOT,EVIDENCE,usekey
from bridge_command import command
state=wait('1.16.5')
assert read(ROOT/'session'/'session.json')['activeVersion']=='1.16.5'
probe=command('1.16.5','probe');info=probe['diagnostics']
assert 'minecraft:air' in info['block_0']
assert 'minecraft:dirt' in info['block_1']
assert 'epoch:unknown_block' in info['block_2']
assert 'Across versions, this page survives.' in info['nbt_2']
usekey()
returned=command('1.20.1','probe')
expected=read(EVIDENCE/'baseline.json')['probe']['diagnostics']['nbt_2']
assert returned['diagnostics']['nbt_2']==expected
assert 'minecraft:air' in returned['diagnostics']['block_0']
assert 'minecraft:dirt' in returned['diagnostics']['block_1']
save('persistence',{'restartedHost':state,'legacyProbe':probe,'restoredProbe':returned,'passed':True})
print('PASS: last active version, edits, unknown NBT survive shutdown/restart; item handler restores original book')
