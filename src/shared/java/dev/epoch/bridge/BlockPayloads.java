package dev.epoch.bridge;

import java.io.IOException;
import java.util.*;

/** Joint state/payload migrations. Unknown fields are never consumed by a known-field migration. */
public final class BlockPayloads {
    private BlockPayloads() {}
    private static final String[] COLORS={"white","orange","magenta","light_blue","yellow","lime","pink","gray","light_gray","cyan","purple","blue","brown","green","red","black"};
    private static final String[] SKULLS={"skeleton","wither_skeleton","zombie","player","creeper","dragon"};
    private static final String[] FLOWERS={"poppy","blue_orchid","allium","azure_bluet","red_tulip","orange_tulip","white_tulip","pink_tulip","oxeye_daisy"};
    private static final String[] SAPLINGS={"oak","spruce","birch","jungle","acacia","dark_oak"};
    public static final class Pair {
        public final String state,payload;
        public final boolean rejected;
        Pair(String s,String p){this(s,p,false);}
        private Pair(String s,String p,boolean rejected){state=s;payload=p;this.rejected=rejected;}
        static Pair reject(String s,String p){return new Pair(s,p,true);}
    }
    private static boolean legacy(String version){return version.equals("1.7.10")||version.equals("1.12.2");}
    private static int color(String name,String suffix){for(int i=0;i<COLORS.length;i++)if(name.equals("minecraft:"+COLORS[i]+suffix))return i;return -1;}
    private static boolean only(SnbtFields tag,String... keys){Set<String> allowed=new HashSet<String>(Arrays.asList(keys));return allowed.containsAll(tag.keys());}
    private static SnbtFields entity(String id){SnbtFields out=new SnbtFields();out.put("id",id);return out;}
    private static String plant(String item,int meta){
        if((item.equals("minecraft:sapling")||item.equals("6"))&&meta>=0&&meta<6)return SAPLINGS[meta]+"_sapling";
        if((item.equals("minecraft:red_flower")||item.equals("38"))&&meta>=0&&meta<FLOWERS.length)return FLOWERS[meta];
        if((item.equals("minecraft:yellow_flower")||item.equals("37"))&&meta==0)return "dandelion";
        if((item.equals("minecraft:tallgrass")||item.equals("31"))&&meta==2)return "fern";
        String[] from={"cactus","brown_mushroom","red_mushroom","deadbush"};int[] ids={81,39,40,32};
        for(int n=0;n<from.length;n++)if((item.equals("minecraft:"+from[n])||item.equals(Integer.toString(ids[n])))&&meta==0)return n==3?"dead_bush":from[n];
        return null;
    }
    private static SnbtFields pot(String name,String target) {
        SnbtFields out=entity("minecraft:flower_pot");String plant=name.equals("minecraft:flower_pot")?"":name.substring("minecraft:potted_".length());
        String item="minecraft:air";int id=0,meta=0;boolean found=plant.isEmpty();
        for(int n=0;n<6;n++)if(plant.equals(SAPLINGS[n]+"_sapling")){item="minecraft:sapling";id=6;meta=n;found=true;}
        for(int n=0;n<FLOWERS.length;n++)if(plant.equals(FLOWERS[n])){item="minecraft:red_flower";id=38;meta=n;found=true;}
        String[] plants={"dandelion","fern","cactus","brown_mushroom","red_mushroom","dead_bush"};String[] names={"yellow_flower","tallgrass","cactus","brown_mushroom","red_mushroom","deadbush"};int[] ids={37,31,81,39,40,32};
        for(int n=0;n<plants.length;n++)if(plant.equals(plants[n])){item="minecraft:"+names[n];id=ids[n];meta=n==1?2:0;found=true;}
        if(!found)return null;
        if(target.equals("1.7.10"))out.number("Item",id);else out.put("Item",item);out.number("Data",meta);return out;
    }
    private static String rawLegacy(String name,int meta,String version){SnbtFields out=new SnbtFields();out.put("Name",name);out.put("EpochLegacyVersion",version);out.number("EpochLegacyMeta",meta);return out.toString();}
    public static Pair convert(String state,String payload,String target) throws IOException {
        SnbtFields s=SnbtFields.parse(BlockSemantics.normalize(state));String name=s.get("Name");SnbtFields p=s.compound("Properties");
        SnbtFields n=payload==null?null:SnbtFields.parse(payload);String id=n==null?"":n.get("id");boolean old=legacy(target);
        if(n!=null&&name.equals("minecraft:note_block")&&id.equals("minecraft:noteblock")&&p.has("instrument")) {
            if(!only(n,"id","x","y","z","note","powered"))return Pair.reject(state,payload);
            int note=n.integer("note",-1),powered=n.integer("powered",0);if(note<0||note>24||powered<0||powered>1)return Pair.reject(state,payload);
            p.put("note",Integer.toString(note));p.put("powered",Boolean.toString(powered==1));n=null;
        }
        // Normalize legacy data only where its fields positively identify that encoding.
        if(n!=null&&id.equals("minecraft:bed")&&n.has("color")&&color(name,"_bed")>=0) {
            int c=n.integer("color",-1);if(c<0||c>15)return Pair.reject(state,payload);name="minecraft:"+COLORS[c]+"_bed";n.remove("color");
        }
        if(n!=null&&id.equals("minecraft:banner")&&n.has("Base")&&(color(name,"_banner")>=0||color(name,"_wall_banner")>=0)) {
            int c=n.integer("Base",-1);if(c<0||c>15)return Pair.reject(state,payload);
            name="minecraft:"+COLORS[15-c]+(name.endsWith("_wall_banner")?"_wall_banner":"_banner");n.remove("Base");
            if(!flipPatterns(n))return Pair.reject(state,payload);
        }
        if(n!=null&&id.equals("minecraft:flower_pot")&&(name.equals("minecraft:flower_pot")||name.startsWith("minecraft:potted_"))) {
            if(!only(n,"id","x","y","z","Item","Data"))return Pair.reject(state,payload);
            String item=n.get("Item");if(item.matches("[0-9]+[bBsS]"))item=item.substring(0,item.length()-1);
            String plant=plant(item,n.integer("Data",0));
            if(item.isEmpty()||item.equals("0")||item.equals("minecraft:air"))name="minecraft:flower_pot";
            else if(plant!=null)name="minecraft:potted_"+plant;else return Pair.reject(state,payload);
            p=new SnbtFields();n=null;s.remove("EpochLegacyVersion");s.remove("EpochLegacyMeta");
        }
        if(n!=null&&id.equals("minecraft:skull")&&s.has("EpochLegacyVersion")&&name.equals("minecraft:skull")) {
            if(!legacy(s.get("EpochLegacyVersion")))return Pair.reject(state,payload);
            int kind=n.integer("SkullType",-1),meta=s.integer("EpochLegacyMeta",-1);
            if(kind<0||kind>=SKULLS.length||meta<1||meta>5)return Pair.reject(state,payload);
            name="minecraft:"+SKULLS[kind]+(meta==1?"_":"_wall_")+(kind<2?"skull":"head");
            p=new SnbtFields();
            if(meta==1){int rot=n.integer("Rot",0);if(rot<0||rot>15)return Pair.reject(state,payload);p.put("rotation",Integer.toString(rot));}
            else p.put("facing",new String[]{"","","north","south","west","east"}[meta]);
            n.remove("SkullType");n.remove("Rot");s.remove("EpochLegacyVersion");s.remove("EpochLegacyMeta");
        }
        if(old) {
            if(name.equals("minecraft:note_block")&&n==null) {
                List<String> instruments=Arrays.asList("harp","basedrum","snare","hat","bass");
                if(target.equals("1.12.2"))instruments=Arrays.asList("harp","basedrum","snare","hat","bass","flute","bell","guitar","chime","xylophone");
                int note=p.integer("note",-1);if(note<0||note>24||!instruments.contains(p.get("instrument"))||!Arrays.asList("true","false").contains(p.get("powered"))||!only(p,"note","instrument","powered"))return Pair.reject(state,payload);
                n=entity("minecraft:noteblock");n.raw("note",note+"b");n.raw("powered",p.get("powered").equals("true")?"1b":"0b");
                return new Pair(rawLegacy("minecraft:noteblock",0,target),n.toString());
            }
            if(name.equals("minecraft:flower_pot")||name.startsWith("minecraft:potted_")) {
                if(n!=null)return Pair.reject(state,payload);SnbtFields pot=pot(name,target);if(pot==null)return Pair.reject(state,payload);
                return new Pair(rawLegacy("minecraft:flower_pot",0,target),pot.toString());
            }
            int bed=color(name,"_bed");
            if(bed>=0) {
                if(target.equals("1.7.10")) {
                    if(bed!=14||n!=null&&!only(n,"id","x","y","z"))return Pair.reject(state,payload);n=null;
                } else {if(n==null)n=entity("minecraft:bed");n.number("color",bed);name="minecraft:red_bed";}
            }
            int banner=color(name,"_banner"),wall=color(name,"_wall_banner");
            if(banner>=0||wall>=0) {
                if(target.equals("1.7.10"))return Pair.reject(state,payload);
                if(n==null)n=entity("minecraft:banner");n.number("Base",15-(banner>=0?banner:wall));if(!flipPatterns(n))return Pair.reject(state,payload);
                name="minecraft:white"+(wall>=0?"_wall_banner":"_banner");
            }
            for(int kind=0;kind<SKULLS.length;kind++) {
                String prefix="minecraft:"+SKULLS[kind];boolean floor=name.equals(prefix+"_"+(kind<2?"skull":"head"));boolean wallSkull=name.equals(prefix+"_wall_"+(kind<2?"skull":"head"));
                if(!floor&&!wallSkull)continue;if(target.equals("1.7.10")&&kind==5)return Pair.reject(state,payload);
                if(n==null)n=entity("minecraft:skull");int meta=1;
                if(wallSkull){meta=Arrays.asList("","","north","south","west","east").indexOf(p.get("facing"));if(meta<2)return Pair.reject(state,payload);}
                int rotation=floor?p.integer("rotation",0):0;if(rotation<0||rotation>15)return Pair.reject(state,payload);
                if(!only(p,floor?new String[]{"rotation"}:new String[]{"facing"}))return Pair.reject(state,payload);
                n.raw("SkullType",kind+"b");n.raw("Rot",rotation+"b");return new Pair(rawLegacy("minecraft:skull",meta,target),n.toString());
            }
        }
        if(n!=null&&n.get("id").equals("minecraft:sign")&&(name.equals("minecraft:oak_sign")||name.equals("minecraft:oak_wall_sign")||BlockSemantics.wood(name,"_sign")||BlockSemantics.wood(name,"_wall_sign"))) {
            SnbtFields projected=SignPayloads.project(n,target);if(projected==null)return Pair.reject(state,payload);n=projected;
        }
        if(n!=null&&n.get("id").equals("minecraft:mob_spawner")&&name.equals("minecraft:spawner")) {
            SnbtFields projected=SpawnerPayloads.project(n,target);if(projected==null)return Pair.reject(state,payload);n=projected;
        }
        s.put("Name",name);if(p.empty())s.remove("Properties");else s.raw("Properties",p.toString());
        return new Pair(s.toString(),n==null?null:n.toString());
    }
    private static boolean flipPatterns(SnbtFields n)throws IOException {
        if(!n.has("Patterns"))return true;List<String> result=new ArrayList<String>();
        for(String item:SnbtFields.list(n.raw("Patterns"))){SnbtFields pattern=SnbtFields.parse(item);int c=pattern.integer("Color",-1);if(c<0||c>15)return false;pattern.number("Color",15-c);result.add(pattern.toString());}
        n.raw("Patterns","["+String.join(",",result)+"]");return true;
    }
    /** Atomic per cell; no partial NBT consumption is published if a conversion declines. */
    public static void project(Snapshot snapshot,String target)throws IOException {
        if(!target.equals("canonical"))snapshot.preserveBlocks();
        List<String> palette=new ArrayList<String>();Map<String,Integer> ids=new HashMap<String,Integer>();List<Integer> runs=new ArrayList<Integer>();Map<String,String> entities=new LinkedHashMap<String,String>();
        Map<String,Pair> emptyCache=new HashMap<String,Pair>();int index=0,previous=-1,count=0;
        for(int r=0;r<snapshot.runs.length;r+=2){String state=snapshot.palette.get(snapshot.runs[r]);
            for(int k=0;k<snapshot.runs[r+1];k++,index++) {
                String key=Integer.toString(index),payload=snapshot.blockEntities.get(key);Pair pair;
                if(payload==null){pair=emptyCache.get(state);if(pair==null){pair=convert(state,null,target);emptyCache.put(state,pair);}}
                else pair=convert(state,payload,target);
                if(pair.rejected&&!target.equals("canonical")) {
                    SnbtFields blocked=SnbtFields.parse(state);blocked.put("EpochLegacyVersion","epoch-unrepresentable-v1");blocked.put("EpochMigrationFailure","payload_not_equivalent");
                    pair=new Pair(blocked.toString(),payload);
                }
                if(pair.payload!=null)entities.put(key,pair.payload);
                Integer id=ids.get(pair.state);if(id==null){id=palette.size();ids.put(pair.state,id);palette.add(pair.state);}
                if(previous==id)count++;else{if(count>0){runs.add(previous);runs.add(count);}previous=id;count=1;}
            }
        }
        runs.add(previous);runs.add(count);snapshot.palette=palette;snapshot.blockEntities=entities;snapshot.runs=new int[runs.size()];for(int i=0;i<runs.size();i++)snapshot.runs[i]=runs.get(i);
    }
    public static ChunkSnapshot project(ChunkSnapshot source,String target)throws IOException {
        Snapshot s=source.blocks();project(s,target);ChunkSnapshot out=new ChunkSnapshot();out.schema=source.schema;out.version=source.version;out.chunkX=source.chunkX;out.chunkZ=source.chunkZ;
        out.preservation=s.preservation;out.sourceInstance=source.sourceInstance;out.appearanceSources=source.appearanceSources;out.biomes=source.biomes;out.palette=s.palette;out.runs=s.runs;out.blockEntities=s.blockEntities;return out;
    }
}
