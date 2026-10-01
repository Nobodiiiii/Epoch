import dev.epoch.bridge.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class CompatibilityRulesTest {
    private static int checks;
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static Map<String, String> properties(String key, String value) {
        Map<String, String> result = new TreeMap<String, String>(); result.put(key, value); return result;
    }
    private static CompatibilityRules.Rule rule(String id) {
        CompatibilityRules.Rule rule = new CompatibilityRules.Rule();
        rule.id = id; rule.safety = "equivalent"; rule.blocks.add("example:lamp"); rule.targetVersions.add("1.16.5");
        rule.when.put("powered", "off"); rule.rename.put("powered", "lit");
        rule.values.put("lit", properties("off", "false")); return rule;
    }
    private static CompatibilityRules.SchemaLookup lookup() {
        return block -> {
            if (!Arrays.asList("example:lamp", "example:old_lamp", "minecraft:grass_path").contains(block)) return null;
            Map<String, Set<String>> schema = new TreeMap<String, Set<String>>();
            if (!block.equals("minecraft:grass_path")) schema.put("lit", new HashSet<String>(Arrays.asList("true", "false")));
            return schema;
        };
    }
    private static BlockStateCompatibility.Result resolve(CompatibilityRules rules, Map<String, String> props, Map<String, String> mods) {
        return rules.resolve(new CanonicalBlockState("example:lamp", props, false, "original SNBT"), "1.16.5", mods, lookup());
    }
    private static void rejects(String json) throws Exception {
        boolean rejected = false;
        try { CompatibilityRules.parse(new StringReader(json)); } catch (IOException expected) { rejected = true; }
        check(rejected, "Malformed pack accepted: " + json);
    }
    public static final class Provider implements BlockCompatibilityProvider {
        public List<CompatibilityRules.Rule> rules() { return Collections.singletonList(rule("example:java_provider")); }
    }
    public static void main(String[] args) throws Exception {
        CompatibilityRules.Rule source = rule("example:lamp_rename");
        source.targetMods.put("example", Arrays.asList("2.0.0"));
        CompatibilityRules rules = CompatibilityRules.of(Collections.singletonList(source));
        BlockStateCompatibility.Result result = resolve(rules, properties("powered", "off"), properties("example", "2.0.0"));
        check(result.compatible() && result.properties.equals(properties("lit", "false")), "Rename/value conversion failed");
        check(!resolve(rules, properties("powered", "off"), Collections.<String, String>emptyMap()).compatible(), "Missing mod version matched");
        check(!resolve(rules, properties("powered", "off"), properties("example", "2.1.0")).compatible(), "Wrong mod version matched");
        source.rename.clear(); source.targetMods.clear();
        check(resolve(rules, properties("powered", "off"), properties("example", "2.0.0")).compatible(), "Rules retained provider mutable references");
        check(!resolve(rules, properties("powered", "on"), properties("example", "2.0.0")).compatible(), "Unmatched predicate converted");
        check(resolve(rules, properties("lit", "true"), properties("example", "2.0.0")).rules.isEmpty(), "Already-compatible state was migrated");
        Map<String, String> collision = properties("powered", "off"); collision.put("lit", "true");
        check("invalid_rule_output".equals(resolve(rules, collision, properties("example", "2.0.0")).failure), "Property overwritten");

        CompatibilityRules.Rule a = rule("example:a"), b = rule("example:b");
        rules = CompatibilityRules.of(Arrays.asList(a, b));
        check("rule_conflict".equals(resolve(rules, properties("powered", "off"), Collections.<String, String>emptyMap()).failure), "Equal-priority conflict resolved implicitly");
        b.priority = 1; b.toBlock = "example:old_lamp";
        rules = CompatibilityRules.of(Arrays.asList(a, b));
        check(resolve(rules, properties("powered", "off"), Collections.<String, String>emptyMap()).block.equals("example:old_lamp"), "Priority or ID mapping failed");
        check(rules.fingerprint.equals(CompatibilityRules.of(Arrays.asList(b, a)).fingerprint), "File order changed fingerprint");
        b.toBlock = "missing:lamp";
        check("missing_block".equals(resolve(CompatibilityRules.of(Arrays.asList(b)), properties("powered", "off"), Collections.<String, String>emptyMap()).failure), "Missing output block accepted");
        a.values.put("lit", properties("off", "invalid"));
        check("unsupported_value".equals(resolve(CompatibilityRules.of(Arrays.asList(a)), properties("powered", "off"), Collections.<String, String>emptyMap()).failure), "Invalid output value accepted");
        a = rule("example:a"); a.when.clear();
        check("unmapped_rule_value".equals(resolve(CompatibilityRules.of(Arrays.asList(a)), properties("powered", "unknown"), Collections.<String, String>emptyMap()).failure), "Unmapped value bypassed");
        boolean duplicate = false;
        try { CompatibilityRules.of(Arrays.asList(a, a)); } catch (IOException expected) { duplicate = true; }
        check(duplicate, "Duplicate IDs accepted");

        String valid = "{\"schema\":1,\"rules\":[{\"id\":\"example:dry\",\"safety\":\"equivalent\",\"blocks\":[\"example:lamp\"],\"targetVersions\":[\"1.16.5\"],\"drop\":{\"extra\":\"false\"}}]}";
        check(CompatibilityRules.parse(new StringReader(valid)).size() == 1, "Valid JSON rejected");
        rejects(valid.replace("equivalent", "lossy"));
        rejects(valid.replace("\"false\"", "false"));
        rejects(valid.replace("\"drop\"", "\"drpo\""));
        rejects(valid.replace("example:lamp", "*:lamp"));
        rejects(valid.replace("1.16.5", "*"));
        rejects(valid.replace("\"schema\":1", "\"schema\":2"));
        rejects(valid.replace("\"schema\":1", "\"schema\":1.5"));
        rejects(valid.replace("\"safety\":", "\"priority\":1.5,\"safety\":"));
        rejects("null");
        result = CompatibilityRules.builtins().resolve(new CanonicalBlockState("%%FILTER_ME%%", Collections.<String, String>emptyMap(), false, "raw"), "1.16.5", Collections.<String, String>emptyMap(), lookup());
        check("invalid_block_id".equals(result.failure), "Invalid legacy sentinel reached registry lookup");

        Path directory = Files.createTempDirectory("epoch-rule-test-");
        try {
            Files.write(directory.resolve("mod.json"), valid.getBytes(StandardCharsets.UTF_8));
            CompatibilityRules loaded = CompatibilityRules.load(directory);
            Map<String, String> extra = properties("extra", "false"); extra.put("lit", "true");
            check(resolve(loaded, extra, Collections.<String, String>emptyMap()).compatible(), "External pack not loaded");
            Files.write(directory.resolve("mod.json"), "bad JSON".getBytes(StandardCharsets.UTF_8));
            check(resolve(loaded, extra, Collections.<String, String>emptyMap()).compatible(), "Active import changed with pack file");
            boolean failed = false;
            try { CompatibilityRules.load(directory); } catch (IOException expected) { failed = expected.getMessage().contains("mod.json"); }
            check(failed, "Invalid pack silently skipped or omitted filename");
            Files.delete(directory.resolve("mod.json"));
            Path service = directory.resolve("META-INF/services/dev.epoch.bridge.BlockCompatibilityProvider");
            Files.createDirectories(service.getParent());
            Files.write(service, "CompatibilityRulesTest$Provider\n".getBytes(StandardCharsets.UTF_8));
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            try (URLClassLoader loader = new URLClassLoader(new URL[]{directory.toUri().toURL()}, previous)) {
                Thread.currentThread().setContextClassLoader(loader);
                check(resolve(CompatibilityRules.load(directory), properties("powered", "off"), Collections.<String, String>emptyMap()).compatible(), "Java provider not loaded");
            } finally { Thread.currentThread().setContextClassLoader(previous); }
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
            }
        }
        result = CompatibilityRules.builtins().resolve(new CanonicalBlockState("minecraft:dirt_path", Collections.<String, String>emptyMap(), false, "raw"), "1.16.5", Collections.<String, String>emptyMap(), lookup());
        check(result.compatible() && result.block.equals("minecraft:grass_path"), "Vanilla path rename failed");
        System.out.println("Compatibility rule pack tests passed: " + checks + " checks");
    }
}
