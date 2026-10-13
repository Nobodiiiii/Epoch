package dev.epoch.bridge;

import java.io.IOException;
import java.util.*;

/** The common vanilla spawner configuration; richer entity migrations remain preserved. */
final class SpawnerPayloads {
    private static final Map<String,String> IDS=new LinkedHashMap<String,String>();
    static {
        String[][] names={{"Pig","pig"},{"Cow","cow"},{"Sheep","sheep"},{"Chicken","chicken"},{"Zombie","zombie"},{"Skeleton","skeleton"},{"Spider","spider"},{"CaveSpider","cave_spider"},{"Creeper","creeper"},{"Enderman","enderman"},{"Blaze","blaze"},{"Slime","slime"},{"LavaSlime","magma_cube"},{"Silverfish","silverfish"},{"Ghast","ghast"},{"Witch","witch"},{"Squid","squid"},{"Wolf","wolf"},{"Ozelot","ocelot"},{"Bat","bat"},{"Villager","villager"},{"SnowMan","snow_golem"},{"VillagerGolem","iron_golem"}};
        for(String[] pair:names)IDS.put(pair[0],"minecraft:"+pair[1]);
    }
    private static String id(String value){return IDS.containsKey(value)?IDS.get(value):value;}
    private static boolean modern(String target){return target.equals("1.18.2")||target.equals("1.20.1");}
    private static SnbtFields entity(SnbtFields source)throws IOException {
        SnbtFields e=source;
        if(source.has("entity")){if(source.keys().size()!=1)return null;e=source.compound("entity");}
        if(!e.has("id")||e.keys().size()!=1)return null;
        e=e.copy();e.put("id",id(e.get("id")));if(!IDS.containsValue(e.get("id")))return null;return e;
    }
    static SnbtFields project(SnbtFields source,String target)throws IOException {
        SnbtFields n=source.copy();SnbtFields spawn;
        // No structural rewrite is needed for already-native modern spawner data.
        // The native factory still validates and serializes the complete payload afterwards.
        if(modern(target)&&n.has("SpawnData")&&n.compound("SpawnData").has("entity")&&!n.has("EntityId"))return n;
        if((target.equals("1.12.2")||target.equals("1.16.5"))&&!n.has("EntityId")&&n.has("SpawnData")&&n.compound("SpawnData").has("id")&&potentialsUse(n,"Entity"))return n;
        if(target.equals("1.7.10")&&n.has("EntityId")&&(!n.has("SpawnData")||!n.compound("SpawnData").has("entity")&&!n.compound("SpawnData").has("id"))&&potentialsUse(n,"Type"))return n;
        if(n.has("SpawnData")&&!(n.has("EntityId")&&n.compound("SpawnData").empty()))spawn=entity(n.compound("SpawnData"));
        else if(n.has("EntityId")){spawn=new SnbtFields();spawn.put("id",id(n.get("EntityId")));if(!IDS.containsValue(spawn.get("id")))return null;}
        else return n;
        if(spawn==null)return null;
        List<SnbtFields> entities=new ArrayList<SnbtFields>();List<Integer> weights=new ArrayList<Integer>();
        if(n.has("SpawnPotentials"))for(String raw:SnbtFields.list(n.raw("SpawnPotentials"))) {
            SnbtFields entry=SnbtFields.parse(raw),e;int weight;
            if(entry.has("data")){if(!new HashSet<String>(Arrays.asList("data","weight")).containsAll(entry.keys()))return null;e=entity(entry.compound("data"));weight=entry.integer("weight",1);}
            else if(entry.has("Entity")){if(!new HashSet<String>(Arrays.asList("Entity","Weight")).containsAll(entry.keys()))return null;e=entity(entry.compound("Entity"));weight=entry.integer("Weight",1);}
            else if(entry.has("Type")){
                if(!new HashSet<String>(Arrays.asList("Type","Properties","Weight")).containsAll(entry.keys()))return null;
                if(entry.has("Properties")&&!entry.compound("Properties").empty())return null;e=new SnbtFields();e.put("id",id(entry.get("Type")));if(!IDS.containsValue(e.get("id")))return null;weight=entry.integer("Weight",1);
            }else return null;
            if(e==null||weight<=0)return null;entities.add(e);weights.add(weight);
        }
        boolean oldest=target.equals("1.7.10");n.remove("EntityId");
        if(oldest){n.put("EntityId",oldId(spawn.get("id")));n.remove("SpawnData");}
        else {SnbtFields data=spawn;if(modern(target)){data=new SnbtFields();data.raw("entity",spawn.toString());}n.raw("SpawnData",data.toString());}
        if(n.has("SpawnPotentials")) {
            List<String> out=new ArrayList<String>();
            for(int i=0;i<entities.size();i++) {
                SnbtFields entry=new SnbtFields(),e=entities.get(i);
                if(oldest){entry.put("Type",oldId(e.get("id")));entry.raw("Properties","{}");entry.number("Weight",weights.get(i));}
                else if(modern(target)){SnbtFields data=new SnbtFields();data.raw("entity",e.toString());entry.raw("data",data.toString());entry.number("weight",weights.get(i));}
                else {entry.raw("Entity",e.toString());entry.number("Weight",weights.get(i));}out.add(entry.toString());
            }
            n.raw("SpawnPotentials","["+String.join(",",out)+"]");
        }
        return n;
    }
    private static String oldId(String value){for(Map.Entry<String,String> e:IDS.entrySet())if(e.getValue().equals(value))return e.getKey();throw new IllegalArgumentException("Unsupported entity");}
    private static boolean potentialsUse(SnbtFields n,String key)throws IOException {
        if(!n.has("SpawnPotentials"))return true;
        for(String item:SnbtFields.list(n.raw("SpawnPotentials")))if(!SnbtFields.parse(item).has(key))return false;
        return true;
    }
}
