package dev.epoch;
import dev.epoch.bridge.*;
import java.util.*;
import net.minecraft.block.Block;
final class LegacyRegistry {
 static Map<String,String> mods(){Map<String,String> out=new TreeMap<String,String>();for(cpw.mods.fml.common.ModContainer mod:cpw.mods.fml.common.Loader.instance().getModList())out.put(mod.getModId(),mod.getVersion());return out;}
 static RegistryCatalog catalog(Map<String,String> mods)throws Exception{
  RegistryCatalog out=new RegistryCatalog(Epoch.VERSION,mods);Set<String> seen=new HashSet<String>();
  for(Object value:Block.blockRegistry){Block block=(Block)value;for(int meta=0;meta<16;meta++){
   String snbt=LegacyStates.encode(block,meta);if(seen.add(snbt))out.states.add(new RegistryCatalog.State(LegacyNbt.string(LegacyNbt.map(LegacyNbt.parse(snbt)).get("Name")),snbt,block.hasTileEntity(meta)));
  }}
  Collections.sort(out.states,new Comparator<RegistryCatalog.State>(){public int compare(RegistryCatalog.State a,RegistryCatalog.State b){int n=a.block.compareTo(b.block);return n==0?a.snbt.compareTo(b.snbt):n;}});return out;
 }
 static Map<String,Object> audit(RegistryCatalog source,Map<String,String> mods)throws Exception{
  return RegistryCatalog.audit(source,Epoch.VERSION,mods,"legacy-native-metadata-v1",new RegistryCatalog.Decoder(){public RegistryCatalog.Outcome decode(String text)throws Exception{LegacyStates.State state=LegacyStates.decode(text);return RegistryCatalog.Outcome.legacy(state!=null,state==null?null:String.valueOf(Block.blockRegistry.getNameForObject(state.block)));}});
 }
}
