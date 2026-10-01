import dev.epoch.bridge.BlockStateCompatibility;
import java.util.*;

public final class BlockStateCompatibilityTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static Map<String, Set<String>> leaves(boolean waterlogging) {
        Map<String, Set<String>> schema = new TreeMap<String, Set<String>>();
        schema.put("distance", new HashSet<String>(Arrays.asList("1", "2", "3", "4", "5", "6", "7")));
        schema.put("persistent", new HashSet<String>(Arrays.asList("false", "true")));
        if (waterlogging) schema.put("waterlogged", new HashSet<String>(Arrays.asList("false", "true")));
        return schema;
    }

    private static Map<String, String> state(String distance, String persistent, String waterlogged) {
        Map<String, String> state = new TreeMap<String, String>();
        state.put("distance", distance);
        state.put("persistent", persistent);
        state.put("waterlogged", waterlogged);
        return state;
    }

    private static BlockStateCompatibility.Result resolve(String block, String version,
            Map<String, String> state, Map<String, Set<String>> schema) {
        return BlockStateCompatibility.resolve(block, state, false, version, schema);
    }

    public static void main(String[] args) {
        Map<String, Set<String>> oldSchema = leaves(false), newSchema = leaves(true);
        for (String wood : Arrays.asList("oak", "spruce", "birch", "jungle", "acacia", "dark_oak")) {
            String block = "minecraft:" + wood + "_leaves";
            for (String target : Arrays.asList("1.16.5", "1.18.2")) {
                for (int distance = 1; distance <= 7; distance++) for (String persistent : Arrays.asList("true", "false")) {
                    Map<String, String> original = state("" + distance, persistent, "false");
                    BlockStateCompatibility.Result result = resolve(block, target, original, oldSchema);
                    check(result.compatible() && result.rules.size() == 1, "Dry leaves must convert: " + block);
                    check(!result.properties.containsKey("waterlogged"), "Unsupported dry flag retained");
                    check(result.properties.get("distance").equals("" + distance), "Distance changed");
                    check(result.properties.get("persistent").equals(persistent), "Persistence changed");
                    check(original.get("waterlogged").equals("false"), "Original snapshot was mutated");
                    // Returning to modern leaves uses the registry's default dry state.
                    Map<String, String> restored = state("7", "false", "false");
                    BlockStateCompatibility.Result back = resolve(block, "1.20.1", result.properties, newSchema);
                    check(back.compatible() && back.rules.isEmpty(), "Return conversion failed");
                    restored.putAll(back.properties);
                    check(restored.equals(original), "Dry leaves failed round trip");
                }
                BlockStateCompatibility.Result wet = resolve(block, target, state("1", "true", "true"), oldSchema);
                check("unsupported_property".equals(wet.failure) && "waterlogged".equals(wet.property), "Wet leaves lost water");
            }
        }
        Map<String, String> dry = state("3", "true", "false");
        for (String water : Arrays.asList("true", "false")) {
            Map<String, String> original = state("3", "true", water);
            BlockStateCompatibility.Result exact = resolve("minecraft:jungle_leaves", "1.20.1", original, newSchema);
            check(exact.compatible() && exact.rules.isEmpty() && exact.properties.equals(original), "Modern state changed");
        }
        check(!resolve("example:jungle_leaves", "1.16.5", dry, oldSchema).compatible(), "Rule leaked into mod blocks");
        check(!resolve("minecraft:stone", "1.16.5", dry, oldSchema).compatible(), "Rule leaked into other blocks");
        check(!resolve("minecraft:jungle_leaves", "1.12.2", dry, oldSchema).compatible(), "Rule leaked into legacy versions");
        check(!resolve("minecraft:jungle_leaves", "future", dry, oldSchema).compatible(), "Rule leaked into unknown versions");
        check("missing_block".equals(resolve("minecraft:cherry_leaves", "1.16.5", dry, null).failure), "Missing block accepted");
        check("legacy_payload".equals(BlockStateCompatibility.resolve("minecraft:jungle_leaves", dry, true, "1.16.5", oldSchema).failure), "Legacy marker ignored");
        Map<String, String> bad = state("8", "false", "false");
        BlockStateCompatibility.Result invalid = resolve("minecraft:jungle_leaves", "1.16.5", bad, oldSchema);
        check("unsupported_value".equals(invalid.failure) && "distance".equals(invalid.property) && "8".equals(invalid.value), "Invalid distance accepted");
        bad = state("1", "false", "false");
        bad.put("future_property", "false");
        check("unsupported_property".equals(resolve("minecraft:jungle_leaves", "1.16.5", bad, oldSchema).failure), "Unknown false attribute ignored");
        Map<String, Set<String>> restricted = leaves(true);
        restricted.put("waterlogged", Collections.singleton("true"));
        check("unsupported_value".equals(resolve("minecraft:jungle_leaves", "1.16.5", dry, restricted).failure), "Existing property bypassed validation");
        check(resolve("minecraft:stone", "1.16.5", Collections.<String, String>emptyMap(), Collections.<String, Set<String>>emptyMap()).compatible(), "Property-free block rejected");

        BlockStateCompatibility.Report report = new BlockStateCompatibility.Report("1.20.1", "1.16.5");
        for (int i = 0; i < 40; i++) report.record(i, "minecraft:jungle_leaves", invalid);
        report.record(40, "minecraft:jungle_leaves", resolve("minecraft:jungle_leaves", "1.16.5", dry, oldSchema));
        report.record(41, "minecraft:jungle_leaves", resolve("minecraft:jungle_leaves", "1.20.1", dry, newSchema));
        Map<String, Object> diagnostics = report.snapshot();
        check(diagnostics.get("rejectedStates").equals(40) && diagnostics.get("convertedStates").equals(1)
            && diagnostics.get("exactStates").equals(1), "Report counts incorrect");
        check(((List<?>) diagnostics.get("examples")).size() == 32 && diagnostics.get("omittedExamples").equals(9), "Report unbounded");
        check(((Map<?, ?>) diagnostics.get("failureCounts")).get("unsupported_value").equals(40), "Failure totals incorrect");
        check(new BlockStateCompatibility.Report("1.16.5", "1.20.1").snapshot().get("rejectedStates").equals(0), "Stale report");
        System.out.println("Block state compatibility tests passed");
    }
}
