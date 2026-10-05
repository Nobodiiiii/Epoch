package dev.epoch.bridge;

import java.util.*;

/** Explicit vanilla gameplay fields; identity, inventory and world coordinates are separate. */
public final class PlayerCompatibility {
    private PlayerCompatibility() {}
    public static final String[] LEGACY_EFFECTS={"","speed","slowness","haste","mining_fatigue","strength","instant_health","instant_damage","jump_boost","nausea","regeneration","resistance","fire_resistance","water_breathing","invisibility","blindness","night_vision","hunger","weakness","poison","wither","health_boost","absorption","saturation"};
    public static final List<String> FIELDS = Collections.unmodifiableList(Arrays.asList(
        "Health","AbsorptionAmount","Air","Fire","FallDistance","OnGround","Motion",
        "foodLevel","foodTickTimer","foodExhaustionLevel","foodSaturationLevel",
        "XpLevel","XpP","XpTotal","XpSeed","Score","abilities","EnderItems",
        "ActiveEffects","Attributes","SpawnX","SpawnY","SpawnZ","SpawnForced",
        "SpawnDimension","SpawnAngle","HurtTime","DeathTime","PortalCooldown","playerGameType"));
    private static final String[][] ATTRIBUTES = {
        {"max_health","maxHealth"},{"follow_range","followRange"},{"knockback_resistance","knockbackResistance"},
        {"movement_speed","movementSpeed"},{"attack_damage","attackDamage"},{"attack_speed","attackSpeed"},
        {"armor_toughness","armorToughness"},{"flying_speed","flyingSpeed"},{"armor","armor"},{"luck","luck"}
    };
    public static String canonicalAttribute(String name) {
        if(name.equals("forge:block_reach"))return "forge:reach_distance";
        if(name.equals("forge.swimSpeed"))return "forge:swim_speed";
        if(name.equals("generic.reachDistance"))return "forge:reach_distance";
        for(String[] pair:ATTRIBUTES) if(name.equals("generic."+pair[1])) return "minecraft:generic."+pair[0];
        return name;
    }
    public static String legacyAttribute(String name) {
        name=canonicalAttribute(name);
        if(name.equals("forge:swim_speed"))return "forge.swimSpeed";
        if(name.equals("forge:reach_distance"))return "generic.reachDistance";
        for(String[] pair:ATTRIBUTES) if(name.equals("minecraft:generic."+pair[0])) return "generic."+pair[1];
        return name;
    }
    /** Restore only fields the target could not store, never effects that expired in play. */
    public static <T> void restoreMissing(Map<String,T> current, Map<String,T> original, Map<String,T> applied) {
        boolean sameSpawn=true;
        for(String key:Arrays.asList("SpawnX","SpawnY","SpawnZ","SpawnForced","SpawnDimension"))
            if(!Objects.equals(current.get(key),applied.get(key)))sameSpawn=false;
        for(String key:FIELDS) if(original.containsKey(key)&&!applied.containsKey(key)&&!current.containsKey(key)
            &&(!key.startsWith("Spawn")||sameSpawn))current.put(key,original.get(key));
    }
    public static boolean absentDefault(String name, double base, boolean modifiers) {
        name=canonicalAttribute(name);
        if(modifiers)return false;
        return (name.equals("minecraft:generic.luck")||name.equals("minecraft:generic.armor_toughness")
            ||name.equals("minecraft:generic.armor")||name.equals("forge:step_height_addition"))&&base==0
            ||name.equals("minecraft:generic.attack_speed")&&base==4
            ||name.equals("forge:swim_speed")&&base==1
            ||name.equals("forge:entity_gravity")&&base==0.08
            ||name.equals("forge:reach_distance")&&(base==5||base==4.5)
            ||name.equals("forge:block_reach")&&base==4.5
            ||name.equals("forge:entity_reach")&&base==3;
    }
}
