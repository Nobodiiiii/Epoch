package dev.epoch;
import dev.epoch.bridge.*;
import java.util.*;
import net.minecraft.block.*;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.*;
final class LegacyRegistry {
 static Map<String,String> mods(){Map<String,String> out=new TreeMap<>();for(net.minecraftforge.fml.common.ModContainer mod:net.minecraftforge.fml.common.Loader.instance().getModList())out.put(mod.getModId(),mod.getVersion());return out;}
 static RegistryCatalog catalog(Map<String,String> mods)throws Exception{
  RegistryCatalog out=new RegistryCatalog(Epoch.VERSION,mods);Set<String> seen=new HashSet<>();
  for(Block block:Block.REGISTRY)for(IBlockState state:block.getBlockState().getValidStates()){
   String snbt=LegacyStates.encode(state);if(seen.add(snbt))out.states.add(new RegistryCatalog.State(Snbt.compound(snbt).getString("Name"),snbt,block.hasTileEntity(state)));
  }
  out.states.sort(Comparator.comparing((RegistryCatalog.State s)->s.block).thenComparing(s->s.snbt));return out;
 }
 static Map<String,Object> audit(RegistryCatalog source,Map<String,String> mods)throws Exception{
  return RegistryCatalog.audit(source,Epoch.VERSION,mods,"legacy-native-metadata-v1",text->{IBlockState state=LegacyStates.decode(text);return RegistryCatalog.Outcome.legacy(state!=null,state==null?null:state.getBlock().getRegistryName().toString());});
 }
}
