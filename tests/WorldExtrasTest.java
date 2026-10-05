import dev.epoch.bridge.*;
import java.util.*;
import java.nio.file.*;
import java.io.*;

public final class WorldExtrasTest {
 static int checks;
 interface Action {void run()throws Exception;}
 static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
 static void rejects(Action action)throws Exception{checks++;try{action.run();}catch(IOException|IllegalArgumentException expected){return;}throw new AssertionError("Accepted invalid extras");}
 static Snapshot snapshot(){Snapshot s=new Snapshot();s.version="1.20.1";s.sizeX=4;s.sizeZ=4;s.sizeY=256;s.palette.add("{Name:'minecraft:air'}");s.runs=new int[]{0,4096};return s;}
 public static void main(String[] args)throws Exception{
  Snapshot s=snapshot();s.validate();check(s.schema==1&&s.extras==null,"schema 1 changed");
  s.schema=2;rejects(s::validate);s.extras=new WorldExtras();s.extras.weather=new WorldExtras.Weather();s.extras.player="{Health:20f}";
  String[] source=new String[1024];Arrays.fill(source,"minecraft:forest");for(int n=0;n<256;n++)source[n]="minecraft:lush_caves";source[16*16+1]="minecraft:desert";
  s.extras.biomes=WorldExtras.Biomes.of(source);s.validate();check(Arrays.equals(source,s.extras.biomes.expand(1024)),"RLE loss");
  Set<String> old=new HashSet<>(Arrays.asList("minecraft:plains","minecraft:forest","minecraft:desert","minecraft:extreme_hills"));
  check(BiomeCompatibility.resolve("minecraft:windswept_hills",old).equals("minecraft:extreme_hills"),"alias lost");
  check(BiomeCompatibility.resolve("mod:caves",old).equals("minecraft:plains"),"missing biome fallback");
  String[] flat=BiomeCompatibility.project(source,4,64,4,false,old);
  check(flat[1].equals("minecraft:desert")&&flat[1023].equals("minecraft:forest"),"legacy horizontal resolution");
  WorldExtras.Carry carry=new WorldExtras.Carry();carry.bounds(s);carry.original=s.extras.biomes;carry.applied=WorldExtras.Biomes.of(flat);carry.rules.put("doWardenSpawning","false");
  check(Arrays.equals(source,carry.restore(s,flat)),"3D roundtrip lost residuals");
  String[] edited=flat.clone();edited[0]="minecraft:desert";check(carry.restore(s,edited)[0].equals("minecraft:desert"),"native edit resurrected original");
  String[] quart=BiomeCompatibility.project(source,4,64,4,true,old);check(quart[256].equals(quart[257]),"modern horizontal projection");carry.applied=WorldExtras.Biomes.of(quart);check(Arrays.equals(source,carry.restore(s,quart)),"subquart boundaries not retained");
  Snapshot mismatch=snapshot();mismatch.x0++;rejects(()->carry.restore(mismatch,quart));
  WorldExtras.Biomes malformed=WorldExtras.Biomes.of(new String[]{"minecraft:plains"});malformed.runs=new int[]{0,0};rejects(()->malformed.expand(1));malformed.runs=new int[]{1,1};rejects(()->malformed.expand(1));malformed.runs=new int[]{0,2};rejects(()->malformed.expand(1));malformed.runs=new int[]{0};rejects(()->malformed.expand(1));
  WorldExtras.validateRule("keepInventory","false","true");rejects(()->WorldExtras.validateRule("keepInventory","FALSE","true"));rejects(()->WorldExtras.validateRule("randomTickSpeed","2147483648","3"));WorldExtras.validateRule("randomTickSpeed","0","3");
  s.extras.weather.rainLevel=Float.NaN;rejects(s::validate);s.extras.weather.rainLevel=0;s.extras.weather.clearTime=-1;rejects(s::validate);s.extras.weather.clearTime=0;
  Map<String,String> original=new HashMap<>(),applied=new HashMap<>(),current=new HashMap<>();original.put("SpawnX","10");original.put("SpawnAngle","90");original.put("XpSeed","123");original.put("ActiveEffects","speed");applied.put("SpawnX","10");applied.put("ActiveEffects","speed");current.put("SpawnX","10");PlayerCompatibility.restoreMissing(current,original,applied);
  check(current.get("XpSeed").equals("123")&&current.get("SpawnAngle").equals("90"),"unsupported player fields lost");check(!current.containsKey("ActiveEffects"),"expired effect resurrected");current.clear();current.put("SpawnX","11");PlayerCompatibility.restoreMissing(current,original,applied);check(!current.containsKey("SpawnAngle"),"angle restored after changing spawn");
  check(!PlayerCompatibility.absentDefault("minecraft:generic.attack_speed",4,true),"modified default dropped");
  check(PlayerCompatibility.canonicalAttribute("forge.swimSpeed").equals("forge:swim_speed"),"legacy Forge swim attribute not canonicalized");
  check(PlayerCompatibility.legacyAttribute("forge:swim_speed").equals("forge.swimSpeed"),"Forge swim attribute cannot return to legacy");
  check(PlayerCompatibility.canonicalAttribute("generic.reachDistance").equals("forge:reach_distance"),"legacy Forge reach attribute not canonicalized");
  check(PlayerCompatibility.legacyAttribute("forge:reach_distance").equals("generic.reachDistance"),"Forge reach attribute cannot return to legacy");
  check(!PlayerCompatibility.absentDefault("forge:swim_speed",2,false)&&!PlayerCompatibility.absentDefault("forge:reach_distance",5,true),"non-default Forge attributes silently dropped");
  Path root=Files.createTempDirectory("epoch-extras-");try{
   Path snapshot=root.resolve("snapshot.gz");s.write(snapshot);Snapshot read=Snapshot.read(snapshot);check(read.schema==2&&Arrays.equals(source,read.extras.biomes.expand(1024)),"schema 2 persistence");
   Path path=root.resolve("epoch/environment.json.gz");carry.playerOriginal="{XpSeed:123}";carry.playerApplied="{}";WorldExtras.writeCarry(path,carry);WorldExtras.Carry readCarry=WorldExtras.readCarry(path);check(Arrays.equals(source,readCarry.restore(s,quart)),"carry disk roundtrip");check(readCarry.rules.equals(carry.rules)&&readCarry.playerOriginal.equals(carry.playerOriginal),"carry metadata loss");
   carry.applied=null;WorldExtras.writeCarry(path,carry);rejects(()->WorldExtras.readCarry(path));Files.write(path,new byte[]{1,2,3});rejects(()->WorldExtras.readCarry(path));
  }finally{try(java.util.stream.Stream<Path> paths=Files.walk(root)){for(Path p:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(p);}}
  System.out.println("PASS: "+checks+" environment assertions (projection, residual edits, disk persistence, validation, player residuals)");
 }
}
