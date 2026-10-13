import dev.epoch.bridge.*;
import java.util.*;

public final class SeamlessBlocksTest {
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static String prop(String text,String key)throws Exception{return SnbtFields.parse(text).compound("Properties").get(key);}
    static String name(String text)throws Exception{return SnbtFields.parse(text).get("Name");}
    static class World implements BlockContext.Lookup {
        final Map<String,String> states=new HashMap<>();boolean missing;
        void put(int x,int y,int z,String state){states.put(x+","+y+","+z,state);}
        public String state(int x,int y,int z){String s=states.get(x+","+y+","+z);return s==null?(missing?null:"{Name:\"minecraft:air\"}"):s;}
        public boolean solid(int x,int y,int z)throws java.io.IOException{return "{Name:\"minecraft:stone\"}".equals(state(x,y,z));}
    }
    public static void main(String[] args)throws Exception {
        String opaque="{id:\"example:machine\",energy:9223372036854775807L,a:[L;1L,-9223372036854775808L],b:[B;1b,-128b],text:'a\\\'b',nested:{items:[{id:\"x:y\",Count:64b}]}}";
        SnbtFields data=SnbtFields.parse(opaque);data.put("id","example:renamed");SnbtFields reread=SnbtFields.parse(data.toString());
        check(reread.raw("energy").equals("9223372036854775807L"),"Long narrowed");check(reread.raw("a").equals("[L;1L,-9223372036854775808L]"),"Long array changed");check(reread.get("text").equals("a'b"),"Quoted string damaged");
        boolean rejected=false;try{SnbtFields.parse("{a:1,a:2}");}catch(Exception e){rejected=true;}check(rejected,"Duplicate field accepted");
        String dirt="{Name:\"minecraft:dirt\",EpochLegacyVersion:\"1.7.10\",EpochLegacyMeta:1}";
        check(name(BlockSemantics.normalize(dirt)).equals("minecraft:coarse_dirt"),"Legacy dirt not recovered");
        check(SnbtFields.parse(BlockSemantics.normalize(dirt.replace("Meta:1","Meta:3"))).has("EpochLegacyVersion"),"Invalid dirt meta accepted");
        check(SnbtFields.parse(BlockSemantics.normalize(dirt.replace("1.7.10","1.12.2"))).has("EpochLegacyVersion"),"Legacy version restriction lost");
        check(name(BlockSemantics.normalize("{Name:\"minecraft:oak_bark\"}")).equals("minecraft:oak_wood"),"Bark alias");
        String leaf="{Name:\"minecraft:oak_leaves\",Properties:{decayable:\"true\",check_decay:\"false\"}}";
        World world=new World();world.put(1,64,0,leaf);world.put(2,64,0,"{Name:\"minecraft:oak_log\",Properties:{axis:\"y\"}}");
        String restored=BlockContext.resolve(leaf,0,64,0,world);
        check(prop(restored,"distance").equals("2")&&prop(restored,"persistent").equals("false"),"Leaf distance or persistence wrong");
        check(prop(BlockSemantics.legacy(restored),"decayable").equals("true"),"Leaf reverse projection");
        world.missing=true;check(prop(BlockContext.resolve(leaf,0,64,0,world),"check_decay").equals("false"),"Missing neighbor treated as air");
        check(prop(BlockContext.resolve(leaf.replace("minecraft:oak_leaves","example:oak_leaves"),0,64,0,world),"check_decay").equals("false"),"Rule leaked into mod block");
        world=new World();String stair="{Name:\"minecraft:oak_stairs\",Properties:{facing:\"north\",half:\"bottom\",shape:\"straight\"}}";
        world.put(0,64,-1,stair.replace("north","west"));check(prop(BlockContext.resolve(stair,0,64,0,world),"shape").equals("outer_left"),"Stair outer corner wrong");
        world.put(1,64,0,stair);check(prop(BlockContext.resolve(stair,0,64,0,world),"shape").equals("straight"),"Stair side blocker ignored");
        world=new World();String lower="{Name:\"minecraft:oak_door\",Properties:{half:\"lower\",facing:\"west\",open:\"true\",hinge:\"right\",powered:\"false\"}}";
        String upper="{Name:\"minecraft:oak_door\",Properties:{half:\"upper\",facing:\"north\",open:\"false\",hinge:\"left\",powered:\"true\"}}";
        world.put(0,65,0,upper);world.put(0,64,0,lower);
        check(prop(BlockContext.resolve(lower,0,64,0,world),"hinge").equals("left"),"Door hinge not read from upper half");
        check(prop(BlockContext.resolve(upper,0,65,0,world),"facing").equals("west"),"Door facing not read from lower half");
        BlockPayloads.Pair pair=BlockPayloads.convert("{Name:\"minecraft:potted_cactus\"}","{id:\"minecraft:flower_pot\",Item:\"minecraft:red_flower\",Data:1}","1.20.1");
        check(name(pair.state).equals("minecraft:potted_blue_orchid")&&pair.payload==null,"Pot NBT ignored");
        pair=BlockPayloads.convert(pair.state,pair.payload,"1.7.10");check(SnbtFields.parse(pair.payload).integer("Item",-1)==38,"Pot native Item wrong");
        pair=BlockPayloads.convert(pair.state,pair.payload,"1.20.1");check(name(pair.state).equals("minecraft:potted_blue_orchid"),"Pot roundtrip");
        String extra="{id:\"minecraft:flower_pot\",Item:81,Data:0,Energy:9223372036854775807L}";
        pair=BlockPayloads.convert("{Name:\"minecraft:potted_cactus\"}",extra,"1.20.1");check(extra.equals(pair.payload),"Unknown pot NBT dropped");
        pair=BlockPayloads.convert("{Name:\"minecraft:red_bed\",Properties:{part:\"head\",facing:\"north\",occupied:\"false\"}}","{id:\"minecraft:bed\",color:5}","1.20.1");
        check(name(pair.state).equals("minecraft:lime_bed"),"Bed color lost");pair=BlockPayloads.convert(pair.state,pair.payload,"1.12.2");check(SnbtFields.parse(pair.payload).integer("color",-1)==5,"Bed color reverse lost");
        String banner="{Name:\"minecraft:white_banner\",Properties:{rotation:\"3\"}}";String patterns="{id:\"minecraft:banner\",Base:1,Patterns:[{Pattern:\"bs\",Color:0}]}";
        pair=BlockPayloads.convert(banner,patterns,"1.20.1");check(name(pair.state).equals("minecraft:red_banner"),"Banner base conversion");
        check(SnbtFields.parse(SnbtFields.list(SnbtFields.parse(pair.payload).raw("Patterns")).get(0)).integer("Color",-1)==15,"Banner dye inversion");
        pair=BlockPayloads.convert(pair.state,pair.payload,"1.12.2");check(SnbtFields.parse(pair.payload).integer("Base",-1)==1,"Banner roundtrip");
        String skull="{Name:\"minecraft:skull\",EpochLegacyVersion:\"1.12.2\",EpochLegacyMeta:1}";
        pair=BlockPayloads.convert(skull,"{id:\"minecraft:skull\",SkullType:4b,Rot:7b}","1.20.1");check(name(pair.state).equals("minecraft:creeper_head")&&prop(pair.state,"rotation").equals("7"),"Skull kind or rotation lost");
        pair=BlockPayloads.convert(pair.state,pair.payload,"1.12.2");check(SnbtFields.parse(pair.payload).integer("SkullType",-1)==4&&SnbtFields.parse(pair.payload).integer("Rot",-1)==7,"Skull roundtrip");
        String wet="{Name:\"minecraft:oak_leaves\",Properties:{persistent:\"true\",distance:\"1\",waterlogged:\"true\"}}";
        check(prop(BlockSemantics.legacy(wet),"waterlogged").equals("true"),"Wet leaf lost its water");
        String note="{Name:\"minecraft:note_block\",Properties:{instrument:\"bass\"}}";
        pair=BlockPayloads.convert(note,"{id:\"minecraft:noteblock\",note:23b,powered:1b}","1.20.1");
        check(prop(pair.state,"note").equals("23")&&pair.payload==null,"Note payload not lifted");
        pair=BlockPayloads.convert(pair.state,pair.payload,"1.7.10");check(SnbtFields.parse(pair.payload).integer("note",-1)==23,"Note reverse conversion");
        String sign="{Name:\"minecraft:oak_sign\",Properties:{rotation:\"0\"}}";
        pair=BlockPayloads.convert(sign,"{id:\"minecraft:sign\",Text1:\"hello\",Text2:\"\",Text3:\"\",Text4:\"\"}","1.20.1");
        check(SnbtFields.parse(pair.payload).has("front_text"),"Sign not upgraded");
        BlockPayloads.Pair oldSign=BlockPayloads.convert(pair.state,pair.payload,"1.7.10");
        check(SnbtFields.parse(oldSign.payload).get("Text1").equals("hello"),"Sign plain-text roundtrip");
        SnbtFields waxed=SnbtFields.parse(pair.payload);waxed.raw("is_waxed","1b");
        check(BlockPayloads.convert(pair.state,waxed.toString(),"1.18.2").payload.equals(waxed.toString()),"Wax discarded");
        String spawner="{Name:\"minecraft:mob_spawner\"}";
        pair=BlockPayloads.convert(spawner,"{id:\"minecraft:mob_spawner\",EntityId:\"Pig\",Delay:17s,SpawnData:{},SpawnPotentials:[{Type:\"Zombie\",Properties:{},Weight:3}]}","1.20.1");
        SnbtFields spawn=SnbtFields.parse(pair.payload);
        check(spawn.compound("SpawnData").compound("entity").get("id").equals("minecraft:pig")&&spawn.raw("Delay").equals("17s"),"Spawner ID or delay migration");
        pair=BlockPayloads.convert(pair.state,pair.payload,"1.7.10");check(SnbtFields.parse(pair.payload).get("EntityId").equals("Pig"),"Spawner reverse ID migration");
        String rich="{id:\"minecraft:mob_spawner\",SpawnData:{entity:{id:\"minecraft:zombie\",Health:8f}}}";
        check(BlockPayloads.convert(spawner,rich,"1.7.10").payload.equals(rich),"Unverified entity fields consumed");
        world=new World();String wall="{Name:\"minecraft:cobblestone_wall\",Properties:{north:\"false\",south:\"false\",east:\"false\",west:\"false\",up:\"false\"}}";
        world.put(0,64,-1,wall);world.put(0,64,1,wall);
        restored=BlockContext.resolve(wall,0,64,0,world);check(prop(restored,"north").equals("low")&&prop(restored,"up").equals("false"),"Straight wall reconstructed incorrectly");
        world.put(0,65,0,"{Name:\"minecraft:stone\"}");check(prop(BlockContext.resolve(wall,0,64,0,world),"north").equals("tall"),"Covered wall height wrong");
        world=new World();world.missing=true;restored=BlockContext.resolve(stair,0,64,0,world);
        check(SnbtFields.parse(restored).get("EpochLegacyVersion").equals("epoch-context-v1"),"Incomplete context silently accepted");
        world.missing=false;restored=BlockContext.resolve(restored,0,64,0,world);check(!SnbtFields.parse(restored).has("EpochLegacyVersion"),"Complete context did not recover");
        restored=BlockContext.resolve("{Name:\"minecraft:note_block\"}",0,64,0,world);
        check(SnbtFields.parse(restored).has("EpochContextRequired"),"Missing instrument context silently defaulted to harp");
        World unknownGeometry=new World(){public boolean solid(int x,int y,int z)throws java.io.IOException{throw BlockContext.unavailable();}};
        restored=BlockContext.resolve(wall,0,64,0,unknownGeometry);
        check(SnbtFields.parse(restored).has("EpochContextRequired"),"Placeholder geometry used as source wall geometry");
        Snapshot snapshot=new Snapshot();snapshot.sizeX=1;snapshot.sizeY=1;snapshot.sizeZ=1;snapshot.y0=64;
        snapshot.palette.add("{Name:\"minecraft:red_bed\",Properties:{facing:\"north\",part:\"head\",occupied:\"false\"}}");snapshot.runs=new int[]{0,1};snapshot.blockEntities.put("0","{id:\"minecraft:bed\",color:5,CustomData:9L}");
        String originalState=snapshot.palette.get(0),originalPayload=snapshot.blockEntities.get("0");
        BlockPayloads.project(snapshot,"1.20.1");
        check(name(snapshot.palette.get(0)).equals("minecraft:lime_bed"),"Batch payload projection failed");
        check(snapshot.originalState(0).equals(originalState)&&snapshot.originalPayload(0).equals(originalPayload),"Failed native import cannot recover exact original");
        check(!new com.google.gson.Gson().toJson(snapshot).contains("preservation"),"Import originals leaked into disk schema");
        Snapshot unsupported=new Snapshot();unsupported.sizeX=1;unsupported.sizeY=1;unsupported.sizeZ=1;unsupported.palette.add(spawner);unsupported.runs=new int[]{0,1};unsupported.blockEntities.put("0",rich);
        BlockPayloads.project(unsupported,"1.7.10");
        check(SnbtFields.parse(unsupported.palette.get(0)).has("EpochMigrationFailure")&&unsupported.originalPayload(0).equals(rich),"Declined semantic conversion could still enter a native factory");
        System.out.println("Seamless block semantics: "+checks+" checks passed");
    }
}
