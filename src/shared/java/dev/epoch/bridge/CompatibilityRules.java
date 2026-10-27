package dev.epoch.bridge;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Immutable, fail-closed rule set. Only explicitly equivalent state migrations are accepted. */
public final class CompatibilityRules {
    public interface SchemaLookup { Map<String, Set<String>> schema(String block); }

    /** Public DTO for Java providers; the engine validates and copies every field. */
    public static final class Rule {
        public String id, safety, toBlock;
        public int priority;
        public List<String> targetVersions = new ArrayList<String>();
        public List<String> blocks = new ArrayList<String>();
        public List<String> missingTargetProperties = new ArrayList<String>();
        public Map<String, String> when = new TreeMap<String, String>();
        public Map<String, List<String>> targetMods = new TreeMap<String, List<String>>();
        public Map<String, String> drop = new TreeMap<String, String>();
        public Map<String, String> rename = new TreeMap<String, String>();
        public Map<String, Map<String, String>> values = new TreeMap<String, Map<String, String>>();
        public Map<String, String> add = new TreeMap<String, String>();
    }

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Set<String> FIELDS = new HashSet<String>(Arrays.asList(
        "id", "safety", "priority", "targetVersions", "blocks", "missingTargetProperties",
        "when", "targetMods", "drop", "rename", "values", "add", "toBlock"));
    private final List<Rule> rules;
    public final String fingerprint;
    private static final ConcurrentMap<String,CompatibilityRules> LOADED = new ConcurrentHashMap<String,CompatibilityRules>();
    private static final ConcurrentMap<String,IOException> FAILED = new ConcurrentHashMap<String,IOException>();
    private static final ConcurrentMap<String,CompletableFuture<CompatibilityRules>> PENDING = new ConcurrentHashMap<String,CompletableFuture<CompatibilityRules>>();
    private static final ExecutorService RELOADS = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(4), task -> { Thread thread = new Thread(task, "Epoch compatibility reload"); thread.setDaemon(true); return thread; },
            new ThreadPoolExecutor.AbortPolicy());

    private CompatibilityRules(List<Rule> input) throws IOException {
        List<Rule> copy = new ArrayList<Rule>();
        Set<String> ids = new HashSet<String>();
        for (Rule original : input) {
            Rule rule = GSON.fromJson(GSON.toJson(original), Rule.class);
            validate(rule);
            if (!ids.add(rule.id)) throw new IOException("Duplicate compatibility rule ID: " + rule.id);
            // Fingerprints describe semantics, not JSON object insertion order.
            rule.blocks = new ArrayList<String>(new TreeSet<String>(rule.blocks));
            rule.targetVersions = new ArrayList<String>(new TreeSet<String>(rule.targetVersions));
            rule.missingTargetProperties = new ArrayList<String>(new TreeSet<String>(rule.missingTargetProperties));
            rule.when = new TreeMap<String, String>(rule.when);
            rule.drop = new TreeMap<String, String>(rule.drop);
            rule.rename = new TreeMap<String, String>(rule.rename);
            rule.add = new TreeMap<String, String>(rule.add);
            rule.values = new TreeMap<String, Map<String, String>>(rule.values);
            for (Map.Entry<String, Map<String, String>> entry : rule.values.entrySet())
                entry.setValue(new TreeMap<String, String>(entry.getValue()));
            rule.targetMods = new TreeMap<String, List<String>>(rule.targetMods);
            for (Map.Entry<String, List<String>> entry : rule.targetMods.entrySet())
                entry.setValue(new ArrayList<String>(new TreeSet<String>(entry.getValue())));
            copy.add(rule);
        }
        Collections.sort(copy, new Comparator<Rule>() {
            public int compare(Rule a, Rule b) { return a.id.compareTo(b.id); }
        });
        rules = Collections.unmodifiableList(copy);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(GSON.toJson(rules).getBytes(StandardCharsets.UTF_8));
            StringBuilder hash = new StringBuilder();
            for (byte b : digest) hash.append(String.format(Locale.ROOT, "%02x", b & 255));
            fingerprint = hash.toString();
        } catch (Exception error) { throw new IOException(error); }
    }

    public static CompatibilityRules of(List<Rule> rules) throws IOException { return new CompatibilityRules(rules); }

    public static CompatibilityRules builtins() throws IOException {
        List<Rule> rules = new ArrayList<Rule>();
        InputStream stream = CompatibilityRules.class.getResourceAsStream("/assets/epoch/compatibility/vanilla.json");
        if (stream == null) throw new IOException("Missing built-in compatibility pack");
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) { rules.addAll(parse(reader)); }
        return new CompatibilityRules(rules);
    }

    public static Path directory() {
        String configured = System.getProperty("epoch.compatibilityDir");
        if (configured != null) return Paths.get(configured).toAbsolutePath().normalize();
        return Paths.get(System.getProperty("epoch.projectRoot", "."))
            .resolve(".minecraft/epoch/compatibility/packs").toAbsolutePath().normalize();
    }

    public static CompatibilityRules load(Path directory) throws IOException {
        String key = directory.toAbsolutePath().normalize().toString();
        CompatibilityRules published = LOADED.get(key);
        if (published != null) return published;
        IOException failure = FAILED.get(key); if (failure != null) throw failure;
        return reload(directory, false);
    }

    /** Explicit update boundary: queries only consume the last successfully published rules. */
    public static CompatibilityRules reload(Path directory) throws IOException {
        return reload(directory, true);
    }
    private static CompatibilityRules reload(Path directory, boolean force) throws IOException {
        final String key = directory.toAbsolutePath().normalize().toString();
        CompletableFuture<CompatibilityRules> created = new CompletableFuture<CompatibilityRules>();
        CompletableFuture<CompatibilityRules> pending = PENDING.putIfAbsent(key, created);
        if (pending == null) {
            pending = created;
            try { RELOADS.execute(() -> {
                try {
                    CompatibilityRules loaded = force ? null : LOADED.get(key);
                    if (loaded == null) loaded = loadUncached(directory);
                    LOADED.put(key, loaded); FAILED.remove(key); created.complete(loaded);
                } catch (Throwable failure) {
                    FAILED.put(key, new IOException("Compatibility rules require an explicit reload after failure", failure));
                    created.completeExceptionally(failure);
                }
                finally { PENDING.remove(key, created); }
            }); } catch (RejectedExecutionException full) { PENDING.remove(key, created); created.completeExceptionally(full); }
        }
        try { return pending.get(30, TimeUnit.SECONDS); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("Rule reload interrupted", error); }
        catch (ExecutionException error) { throw new IOException("Rule reload failed: " + error.getCause().getMessage(), error.getCause()); }
        catch (TimeoutException error) { throw new IOException("Rule reload has not finished; the published rules remain active", error); }
    }

    private static CompatibilityRules loadUncached(Path directory) throws IOException {
        List<Rule> rules = new ArrayList<Rule>(builtins().rules);
        long bytes = 0, deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        if (Files.exists(directory)) {
            if (!Files.isDirectory(directory)) throw new IOException("Compatibility pack path is not a directory: " + directory);
            List<Path> files = new ArrayList<Path>();
            try (DirectoryStream<Path> paths = Files.newDirectoryStream(directory, "*.json")) {
                for (Path path : paths) files.add(path);
            }
            Collections.sort(files);
            if (files.size() > 256) throw new IOException("Too many compatibility packs");
            for (Path path : files) {
                bytes += Files.size(path);
                if (bytes > 8 * 1024 * 1024 || System.nanoTime() >= deadline) throw new IOException("Compatibility pack budget exhausted");
                if (Files.size(path) > 1024 * 1024) throw new IOException("Compatibility pack exceeds 1 MiB: " + path);
                try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { rules.addAll(parse(reader)); }
                catch (Exception error) { throw new IOException("Invalid compatibility pack " + path + ": " + error.getMessage(), error); }
            }
        }
        try {
            for (BlockCompatibilityProvider provider : ServiceLoader.load(BlockCompatibilityProvider.class))
                rules.addAll(provider.rules());
        } catch (ServiceConfigurationError | RuntimeException error) {
            throw new IOException("Cannot load block compatibility provider", error);
        }
        if (rules.size() > 10000) throw new IOException("Too many compatibility rules");
        return new CompatibilityRules(rules);
    }

    public static List<Rule> parse(Reader reader) throws IOException {
        try {
            JsonObject root = new JsonParser().parse(reader).getAsJsonObject();
            fields(root, new HashSet<String>(Arrays.asList("schema", "rules")));
            if (!root.has("schema") || !root.get("schema").isJsonPrimitive()
                    || !root.get("schema").getAsJsonPrimitive().isNumber()
                    || !"1".equals(root.get("schema").getAsString())) throw new IOException("Unsupported compatibility pack schema");
            List<Rule> result = new ArrayList<Rule>();
            for (JsonElement item : root.getAsJsonArray("rules")) {
                JsonObject object = item.getAsJsonObject();
                fields(object, FIELDS);
                for (String key : Arrays.asList("id", "safety", "toBlock"))
                    if (object.has(key)) stringValue(object.get(key));
                for (String key : Arrays.asList("blocks", "targetVersions", "missingTargetProperties"))
                    if (object.has(key)) for (JsonElement value : object.getAsJsonArray(key)) stringValue(value);
                if (object.has("targetMods")) for (Map.Entry<String, JsonElement> entry : object.getAsJsonObject("targetMods").entrySet())
                    for (JsonElement value : entry.getValue().getAsJsonArray()) stringValue(value);
                if (object.has("priority")) {
                    JsonElement value = object.get("priority");
                    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                            || !value.getAsString().matches("-?[0-9]+")) throw new IOException("Priority must be an integer");
                    Integer.parseInt(value.getAsString());
                }
                // String-only maps prevent Gson from coercing booleans/numbers into state strings.
                for (String key : Arrays.asList("when", "drop", "rename", "add"))
                    if (object.has(key)) stringMap(object.getAsJsonObject(key));
                if (object.has("values")) for (Map.Entry<String, JsonElement> entry : object.getAsJsonObject("values").entrySet())
                    stringMap(entry.getValue().getAsJsonObject());
                Rule rule = GSON.fromJson(object, Rule.class);
                validate(rule);
                result.add(rule);
            }
            return result;
        } catch (RuntimeException error) { throw new IOException("Malformed compatibility rule: " + error.getMessage(), error); }
    }

    private static void fields(JsonObject object, Set<String> allowed) throws IOException {
        for (Map.Entry<String, JsonElement> entry : object.entrySet())
            if (!allowed.contains(entry.getKey())) throw new IOException("Unknown field: " + entry.getKey());
    }

    private static void stringMap(JsonObject object) throws IOException {
        for (Map.Entry<String, JsonElement> entry : object.entrySet())
            stringValue(entry.getValue());
    }

    private static void stringValue(JsonElement value) throws IOException {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IOException("State IDs, versions and property values must be strings");
    }

    private static void validate(Rule r) throws IOException {
        if (r == null || r.id == null || !r.id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IOException("Invalid rule ID");
        if (!"equivalent".equals(r.safety)) throw new IOException("Only safety=equivalent rules are enabled: " + r.id);
        if (r.targetVersions == null || r.targetVersions.isEmpty() || r.blocks == null || r.blocks.isEmpty())
            throw new IOException("Rules require explicit targetVersions and blocks: " + r.id);
        for (String block : r.blocks) if (block == null || !block.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IOException("Invalid block ID: " + block);
        if (r.toBlock != null && !r.toBlock.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IOException("Invalid destination block");
        for (String version : r.targetVersions) if (version == null || !version.matches("[0-9]+\\.[0-9]+(?:\\.[0-9]+)?")) throw new IOException("Target versions must be explicit");
        if (r.when == null || r.drop == null || r.rename == null || r.values == null || r.add == null || r.targetMods == null || r.missingTargetProperties == null)
            throw new IOException("Null rule fields: " + r.id);
        for (String key : r.missingTargetProperties) if (key == null || key.isEmpty()) throw new IOException("Invalid missing property condition");
        for (Map<String, String> map : Arrays.asList(r.when, r.drop, r.rename, r.add))
            for (Map.Entry<String, String> entry : map.entrySet())
                if (entry.getKey().isEmpty() || entry.getValue() == null) throw new IOException("Invalid rule property: " + r.id);
        for (Map.Entry<String, Map<String, String>> entry : r.values.entrySet())
            if (entry.getValue() == null || entry.getValue().containsValue(null)) throw new IOException("Invalid value mapping");
        for (String destination : r.rename.values())
            if (r.rename.containsKey(destination)) throw new IOException("Property rename chains/cycles are not supported: " + r.id);
        for (Map.Entry<String, List<String>> entry : r.targetMods.entrySet())
            if (entry.getValue() == null || entry.getValue().isEmpty() || entry.getValue().contains(null) || entry.getValue().contains("*"))
                throw new IOException("Target mod versions must be explicit: " + entry.getKey());
        if (r.toBlock == null && r.drop.isEmpty() && r.rename.isEmpty() && r.values.isEmpty() && r.add.isEmpty()) throw new IOException("Rule has no transformation: " + r.id);
    }

    public Map<String, Object> description() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("sha256", fingerprint);
        List<String> ids = new ArrayList<String>();
        for (Rule rule : rules) ids.add(rule.id);
        result.put("rules", ids);
        return result;
    }

    public BlockStateCompatibility.Result resolve(CanonicalBlockState input, String version,
            Map<String, String> mods, SchemaLookup lookup) {
        if (input.legacyPayload) return BlockStateCompatibility.failure(input.block, input.properties, "legacy_payload", null, null, Collections.<String>emptyList());
        if (!input.block.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
            return BlockStateCompatibility.failure(input.block, input.properties, "invalid_block_id", null, null, Collections.<String>emptyList());
        Map<String, Set<String>> schema = lookup.schema(input.block);
        // Already representable states never need a migration. This also makes a
        // bidirectional pack safe when a snapshot already uses the target format.
        BlockStateCompatibility.Result exact = BlockStateCompatibility.validate(
            input.block, input.properties, schema, Collections.<String>emptyList());
        if (exact.compatible()) return exact;
        List<Rule> candidates = new ArrayList<Rule>();
        int priority = Integer.MIN_VALUE;
        for (Rule rule : rules) {
            if (!rule.targetVersions.contains(version) || !rule.blocks.contains(input.block)) continue;
            boolean matches = true;
            for (Map.Entry<String, String> e : rule.when.entrySet()) if (!e.getValue().equals(input.properties.get(e.getKey()))) matches = false;
            for (Map.Entry<String, String> e : rule.drop.entrySet()) if (!e.getValue().equals(input.properties.get(e.getKey()))) matches = false;
            for (Map.Entry<String, List<String>> e : rule.targetMods.entrySet()) if (!e.getValue().contains(mods.get(e.getKey()))) matches = false;
            for (String key : rule.missingTargetProperties) if (schema != null && schema.containsKey(key)) matches = false;
            if (!matches || rule.priority < priority) continue;
            if (rule.priority > priority) { candidates.clear(); priority = rule.priority; }
            candidates.add(rule);
        }
        if (candidates.isEmpty()) return exact;
        List<String> applied = new ArrayList<String>();
        for (Rule rule : candidates) applied.add(rule.id);
        if (candidates.size() > 1) return BlockStateCompatibility.failure(input.block, input.properties, "rule_conflict", null, null, applied);
        Rule rule = candidates.get(0);
        Map<String, String> output = new TreeMap<String, String>(input.properties);
        String block = rule.toBlock == null ? input.block : rule.toBlock;
        for (String key : rule.drop.keySet()) output.remove(key);
        for (Map.Entry<String, String> entry : rule.rename.entrySet()) {
            if (!output.containsKey(entry.getKey()) || output.containsKey(entry.getValue()))
                return BlockStateCompatibility.failure(block, input.properties, "invalid_rule_output", entry.getKey(), entry.getValue(), applied);
            output.put(entry.getValue(), output.remove(entry.getKey()));
        }
        for (Map.Entry<String, Map<String, String>> entry : rule.values.entrySet()) {
            String mapped = entry.getValue().get(output.get(entry.getKey()));
            if (mapped == null) return BlockStateCompatibility.failure(block, input.properties, "unmapped_rule_value", entry.getKey(), output.get(entry.getKey()), applied);
            output.put(entry.getKey(), mapped);
        }
        for (Map.Entry<String, String> entry : rule.add.entrySet()) {
            if (output.containsKey(entry.getKey())) return BlockStateCompatibility.failure(block, input.properties, "invalid_rule_output", entry.getKey(), entry.getValue(), applied);
            output.put(entry.getKey(), entry.getValue());
        }
        return BlockStateCompatibility.validate(block, output, lookup.schema(block), applied);
    }
}
