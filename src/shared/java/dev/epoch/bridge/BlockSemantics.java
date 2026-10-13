package dev.epoch.bridge;

import java.io.IOException;
import java.util.*;

/** Explicit vanilla semantic boundaries, shared by both legacy codecs and modern imports. */
public final class BlockSemantics {
    private BlockSemantics() {}
    public static final Set<String> WOODS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList("oak","spruce","birch","jungle","acacia","dark_oak")));
    public static boolean leaves(String id) { return wood(id, "_leaves"); }
    public static boolean wood(String id, String suffix) {
        return id.startsWith("minecraft:") && id.endsWith(suffix) && WOODS.contains(id.substring(10,id.length()-suffix.length()));
    }
    public static String alias(String id) {
        if (wood(id,"_bark")) return id.substring(0,id.length()-5)+"_wood";
        if (id.equals("minecraft:mob_spawner")) return "minecraft:spawner";
        if (id.equals("minecraft:portal")) return "minecraft:nether_portal";
        return id;
    }
    /** Safe format repairs; leaves/structures remain the responsibility of the context pass. */
    public static String normalize(String text) throws IOException {
        SnbtFields s=SnbtFields.parse(text); String name=s.get("Name");
        if (s.has("EpochLegacyVersion")) {
            if (s.get("EpochLegacyVersion").equals("1.7.10") && name.equals("minecraft:dirt") && s.integer("EpochLegacyMeta",-1)==1
                    && s.keys().size()==3) {
                s.remove("EpochLegacyVersion");s.remove("EpochLegacyMeta");s.put("Name","minecraft:coarse_dirt");
            }
            return s.toString();
        }
        s.put("Name",alias(name));
        return s.toString();
    }
    public static Map<String,String> properties(SnbtFields s) throws IOException {
        SnbtFields p=s.compound("Properties"); Map<String,String> out=new TreeMap<String,String>();
        for(String k:p.keys())out.put(k,p.get(k));return out;
    }
    public static String state(String name, Map<String,String> properties) {
        SnbtFields s=new SnbtFields();s.put("Name",name);SnbtFields p=new SnbtFields();
        for(Map.Entry<String,String> e:properties.entrySet())p.put(e.getKey(),e.getValue());
        if(!p.empty())s.raw("Properties",p.toString());return s.toString();
    }
    /** Metadata lacks these fields; the legacy game derives them from its world. */
    public static boolean derived(String block,String key,String value) {
        if(!block.startsWith("minecraft:"))return false;
        String b=block.substring(10);
        boolean bool=value.equals("true")||value.equals("false");
        boolean side=Arrays.asList("north","south","east","west").contains(key);
        if(b.endsWith("_stairs") && key.equals("shape"))return Arrays.asList("straight","inner_left","inner_right","outer_left","outer_right").contains(value);
        if((b.endsWith("_fence")||b.endsWith("_glass_pane")||b.equals("glass_pane")||b.equals("iron_bars"))&&side)return bool;
        if(b.endsWith("_fence_gate")&&key.equals("in_wall"))return bool;
        if((b.equals("chest")||b.equals("trapped_chest"))&&key.equals("type"))return Arrays.asList("single","left","right").contains(value);
        if(b.equals("cobblestone_wall")||b.equals("mossy_cobblestone_wall")) {
            if(side)return Arrays.asList("false","true","none","low","tall").contains(value);
            if(key.equals("up"))return bool;
        }
        if(Arrays.asList("grass_block","mycelium","podzol").contains(b)&&key.equals("snowy"))return bool;
        if(b.equals("repeater")&&key.equals("locked"))return bool;
        if(b.equals("vine")&&key.equals("up"))return bool;
        if(b.equals("tripwire")&&side)return bool;
        if(b.equals("chorus_plant")&&(side||key.equals("up")||key.equals("down")))return bool;
        if(b.equals("fire")&&(side||key.equals("up")))return bool;
        // Redstone's none/side distinction can be player selected in modern versions; do not discard it here.
        return false;
    }
    public static boolean defaultProperty(String block,String key,String value) {
        if(key.equals("waterlogged")&&value.equals("false"))return true;
        return derived(block,key,value);
    }
    /** Projects only representable state fields; payload/structure validation still runs afterwards. */
    public static String legacy(String text) throws IOException {
        SnbtFields s=SnbtFields.parse(normalize(text));if(s.has("EpochLegacyVersion"))return s.toString();
        String name=s.get("Name");SnbtFields p=s.compound("Properties");
        if(leaves(name)&&p.has("persistent")&&p.has("distance")) {
            if(!Arrays.asList("true","false").contains(p.get("persistent"))||!p.get("distance").matches("[1-7]"))return s.toString();
            p.put("decayable",Boolean.toString(p.get("persistent").equals("false")));p.put("check_decay","true");
            p.remove("persistent");p.remove("distance");
        }
        if(name.equals("minecraft:water_cauldron")&&p.get("level").matches("[1-3]"))s.put("Name","minecraft:cauldron");
        else if(name.equals("minecraft:cauldron")&&!p.has("level"))p.put("level","0");
        if(name.equals("minecraft:grass_path"))s.put("Name","minecraft:dirt_path");
        if(Arrays.asList("minecraft:sunflower","minecraft:lilac","minecraft:tall_grass","minecraft:large_fern","minecraft:rose_bush","minecraft:peony").contains(name)&&p.get("half").equals("upper"))s.put("Name","minecraft:peony");
        if(BlockSemantics.wood(name,"_wood")&&p.get("axis").equals("y"))p.remove("axis");
        if(!p.empty())s.raw("Properties",p.toString());else s.remove("Properties");return s.toString();
    }
    public static boolean matches(String name,Map<String,String> requested,Map<String,String> expected) {
        for(Map.Entry<String,String> e:expected.entrySet()) {
            String value=requested.get(e.getKey());
            if(e.getValue().equals(value))continue;
            if(name.startsWith("minecraft:")&&name.endsWith("_door")&&!name.endsWith("_trapdoor")) {
                boolean upper="upper".equals(requested.get("half"));String k=e.getKey();
                if(upper&&k.equals("facing")&&Arrays.asList("north","south","east","west").contains(value))continue;
                if((upper&&k.equals("open"))||(!upper&&(k.equals("hinge")||k.equals("powered"))))
                        if(k.equals("hinge")?Arrays.asList("left","right").contains(value):Arrays.asList("true","false").contains(value))continue;
            }
            if(value!=null&&derived(name,e.getKey(),value))continue;
            return false;
        }
        for(Map.Entry<String,String> e:requested.entrySet())
            if(!expected.containsKey(e.getKey())&&!defaultProperty(name,e.getKey(),e.getValue()))return false;
        return true;
    }
}
