package dev.epoch;
import dev.epoch.bridge.*;
import java.nio.file.*;
import java.util.*;
public final class RegistryAudit {
 public static void main(String[] args) throws Exception {
  net.minecraft.util.registry.Bootstrap.bootStrap();
  Map<String,String> mods = Collections.singletonMap("minecraft",Epoch.VERSION);
  NativeStates.Context context = new NativeStates.Context(CompatibilityRules.load(CompatibilityRules.directory()),mods);
  SeamlessModernTest.run(context);
  String dry="{Name:\"minecraft:jungle_leaves\",Properties:{distance:\"1\",persistent:\"true\",waterlogged:\"false\"}}";
  if(context.materialize(context.resolve(dry))==null) throw new AssertionError("Dry jungle leaves rejected by native registry");
  boolean wet=context.resolve(dry.replace("false", "true")).compatible();
  if(wet!=Epoch.VERSION.equals("1.20.1")) throw new AssertionError("Native waterlogging boundary incorrect");
  if(!"invalid_block_id".equals(context.resolve("{Name:\"%%FILTER_ME%%\"}").failure)) throw new AssertionError("Legacy sentinel not preserved");
  for(int level=0;level<=3;level++) {
   String original="{Name:'minecraft:cauldron',Properties:{level:'"+level+"'}}";
   BlockStateCompatibility.Result converted=context.resolve(original);
   if(context.materialize(converted)==null) throw new AssertionError("Cauldron level "+level+" rejected");
   String expected=level==0||Epoch.VERSION.equals("1.16.5")?"minecraft:cauldron":"minecraft:water_cauldron";
   if(!converted.block.equals(expected)) throw new AssertionError("Wrong cauldron kind");
   if(level>0&&!Integer.toString(level).equals(converted.properties.get("level"))) throw new AssertionError("Cauldron water level changed");
   if(level>0&&context.materialize(context.resolve("{Name:'minecraft:water_cauldron',Properties:{level:'"+level+"'}}"))==null) throw new AssertionError("Water cauldron reverse mapping failed");
  }
  if(Epoch.VERSION.equals("1.16.5")) {
   for(String rail:Arrays.asList("rail","powered_rail","detector_rail","activator_rail")) {
    if(context.materialize(context.resolve("{Name:'minecraft:"+rail+"',Properties:{waterlogged:'false'}}"))==null) throw new AssertionError("Dry rail rejected");
    if(context.resolve("{Name:'minecraft:"+rail+"',Properties:{waterlogged:'true'}}").compatible()) throw new AssertionError("Wet rail lost water");
   }
   if(context.resolve("{Name:'minecraft:water_cauldron',Properties:{level:'0'}}").compatible()) throw new AssertionError("Invalid water cauldron level accepted");
  }
  Path output=Paths.get(args[0]);
  RegistryCatalog catalog=NativeStates.catalog(mods);
  catalog.write(output.resolve("catalog-"+Epoch.VERSION+".json"));
  Map<String,Object> own=context.audit(catalog);
  if (((Map<?,?>)own.get("stateTotals")).containsKey("PRESERVED")) throw new AssertionError("Same-version registry rejected states: "+own.get("failureCounts"));
  if(args.length>1)try(DirectoryStream<Path> files=Files.newDirectoryStream(output,"catalog-*.json")){
   for(Path source:files){RegistryCatalog input=RegistryCatalog.read(source);RegistryCatalog.writeJson(output.resolve("audit-"+input.version+"-to-"+Epoch.VERSION+".json"),context.audit(input));}
  }
  System.out.println("Registry verified "+Epoch.VERSION+": "+catalog.states.size()+" states");
 }
}
