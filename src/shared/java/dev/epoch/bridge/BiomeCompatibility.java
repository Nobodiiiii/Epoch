package dev.epoch.bridge;

import java.util.*;

/** Explicit vanilla names; unavailable biomes use a documented visual fallback plus residuals. */
public final class BiomeCompatibility {
    private BiomeCompatibility() {}
    private static final String[][] ALIASES = {
        {"beach","beaches"}, {"savanna_plateau","savanna_rock"}, {"badlands_plateau","mesa_clear_rock"},
        {"windswept_hills","mountains","extreme_hills"}, {"snowy_plains","snowy_tundra","ice_flats"},
        {"sparse_jungle","jungle_edge"}, {"stony_shore","stone_shore","stone_beach"},
        {"old_growth_birch_forest","tall_birch_forest","mutated_birch_forest"},
        {"old_growth_pine_taiga","giant_tree_taiga","redwood_taiga"},
        {"old_growth_spruce_taiga","giant_spruce_taiga","mutated_redwood_taiga"},
        {"windswept_forest","wooded_mountains","extreme_hills_with_trees"},
        {"windswept_gravelly_hills","gravelly_mountains","mutated_extreme_hills"},
        {"windswept_savanna","shattered_savanna","mutated_savanna"},
        {"wooded_badlands","wooded_badlands_plateau","mesa_rock"},
        {"badlands","mesa"}, {"eroded_badlands","mutated_mesa"},
        {"snowy_taiga","taiga_cold"}, {"snowy_beach","cold_beach"},
        {"snowy_mountains","ice_mountains"}, {"mushroom_fields","mushroom_island"},
        {"mushroom_field_shore","mushroom_island_shore"}, {"nether_wastes","nether","hell"},
        {"the_end","sky"}, {"dark_forest","roofed_forest"}, {"swamp","swampland"},
        {"wooded_hills","forest_hills"}, {"flower_forest","mutated_forest"},
        {"sunflower_plains","mutated_plains"}, {"ice_spikes","mutated_ice_flats"}
    };
    public static String resolve(String id, Set<String> available) {
        if(available.contains(id)) return id;
        if(id.startsWith("minecraft:")) {
            String shortName=id.substring(10);
            for(String[] aliases:ALIASES) if(Arrays.asList(aliases).contains(shortName))
                for(String alias:aliases) if(available.contains("minecraft:"+alias)) return "minecraft:"+alias;
        }
        if(!available.contains("minecraft:plains")) throw new IllegalStateException("No plains biome fallback in target registry");
        return "minecraft:plains";
    }
    public static String[] project(String[] source, int sx, int sy, int sz, boolean volumetric, Set<String> available) {
        if(source.length!=sx*sy*sz || sx%4!=0 || sz%4!=0)throw new IllegalArgumentException("Invalid biome grid");
        String[] out=new String[source.length]; Map<String,String> resolved=new HashMap<String,String>();
        int step=volumetric?4:1, yStep=volumetric?1:sy;
        for(int y=0;y<sy;y+=yStep)for(int z=0;z<sz;z+=step)for(int x=0;x<sx;x+=step){
            String original=source[((volumetric?y:Math.min(16,sy-1))*sz+z)*sx+x];
            String mapped=resolved.get(original);if(mapped==null){mapped=resolve(original,available);resolved.put(original,mapped);}
            for(int dy=0;dy<yStep;dy++)for(int dz=0;dz<step;dz++)for(int dx=0;dx<step;dx++)out[((y+dy)*sz+z+dz)*sx+x+dx]=mapped;
        }
        return out;
    }
    public static final String[] LEGACY = {"ocean","plains","desert","extreme_hills","forest","taiga","swampland","river","hell","sky","frozen_ocean","frozen_river","ice_flats","ice_mountains","mushroom_island","mushroom_island_shore","beaches","desert_hills","forest_hills","taiga_hills","smaller_extreme_hills","jungle","jungle_hills","jungle_edge","deep_ocean","stone_beach","cold_beach","birch_forest","birch_forest_hills","roofed_forest","taiga_cold","taiga_cold_hills","redwood_taiga","redwood_taiga_hills","extreme_hills_with_trees","savanna","savanna_rock","mesa","mesa_rock","mesa_clear_rock"};
    public static String legacyName(int id, String identity) {
        if(id>=0 && id<LEGACY.length) return "minecraft:"+(id==16?"beach":LEGACY[id]);
        Map<Integer,String> mutated=new HashMap<Integer,String>();
        int[] ids={129,130,131,132,133,134,140,149,151,155,156,157,158,160,161,162,163,164,165,166,167};
        String[] names={"sunflower_plains","desert_lakes","gravelly_mountains","flower_forest","taiga_mountains","swamp_hills","ice_spikes","modified_jungle","modified_jungle_edge","tall_birch_forest","tall_birch_hills","dark_forest_hills","snowy_taiga_mountains","giant_spruce_taiga","giant_spruce_taiga_hills","modified_gravelly_mountains","shattered_savanna","shattered_savanna_plateau","eroded_badlands","modified_wooded_badlands_plateau","modified_badlands_plateau"};
        for(int n=0;n<ids.length;n++)mutated.put(ids[n],names[n]);
        if(mutated.containsKey(id))return "minecraft:"+mutated.get(id);
        return "epoch:legacy_biome/"+id+"/"+Integer.toHexString(identity.hashCode());
    }
}
