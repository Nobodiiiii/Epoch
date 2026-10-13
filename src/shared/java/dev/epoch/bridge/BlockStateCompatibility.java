package dev.epoch.bridge;

import java.util.*;

/** Safe, version-scoped state migrations shared by the modern adapters. */
public final class BlockStateCompatibility {
    private BlockStateCompatibility() {}

    public static final class Result {
        public final Map<String, String> properties;
        public final List<String> rules;
        public final String block, failure, property, value;

        private Result(String block, Map<String, String> properties, List<String> rules,
                       String failure, String property, String value) {
            this.block = block;
            this.properties = Collections.unmodifiableMap(new TreeMap<String, String>(properties));
            this.rules = Collections.unmodifiableList(new ArrayList<String>(rules));
            this.failure = failure;
            this.property = property;
            this.value = value;
        }

        public boolean compatible() { return failure == null; }
    }

    /** Convenience API using built-in rules only; runtime imports load one complete pack set. */
    public static Result resolve(final String block, Map<String, String> properties, boolean legacyPayload,
                                 String targetVersion, final Map<String, Set<String>> schema) {
        try {
            return CompatibilityRules.builtins().resolve(new CanonicalBlockState(block, properties, legacyPayload, ""),
                targetVersion, Collections.<String, String>emptyMap(), new CompatibilityRules.SchemaLookup() {
                    public Map<String, Set<String>> schema(String id) { return block.equals(id) ? schema : null; }
                });
        } catch (java.io.IOException error) { throw new IllegalStateException("Cannot load built-in compatibility rules", error); }
    }

    static Result failure(String block, Map<String, String> properties, String reason,
                          String property, String value, List<String> rules) {
        return new Result(block, properties, rules, reason, property, value);
    }
    public static Result unresolved(String block,String reason) {
        return failure(block,Collections.<String,String>emptyMap(),reason,null,null,Collections.<String>emptyList());
    }

    static Result validate(String block, Map<String, String> properties,
                           Map<String, Set<String>> schema, List<String> rules) {
        if (schema == null) return failure(block, properties, "missing_block", null, null, rules);
        for (Map.Entry<String, String> entry : new TreeMap<String, String>(properties).entrySet()) {
            Set<String> allowed = schema.get(entry.getKey());
            if (allowed == null) return failure(block, properties, "unsupported_property", entry.getKey(), entry.getValue(), rules);
            if (!allowed.contains(entry.getValue())) return failure(block, properties, "unsupported_value", entry.getKey(), entry.getValue(), rules);
        }
        return new Result(block, properties, rules, null, null, null);
    }

    /** Palette-level counts, with bounded examples to keep heartbeat files small. */
    public static final class Report {
        private int exact, converted, rejected;
        private final Map<String, Integer> failures = new TreeMap<String, Integer>();
        private final List<Map<String, Object>> examples = new ArrayList<Map<String, Object>>();
        private final String sourceVersion, targetVersion;

        public Report(String sourceVersion, String targetVersion) {
            this.sourceVersion = sourceVersion;
            this.targetVersion = targetVersion;
        }

        public void record(int paletteIndex, String block, Result result) {
            if (!result.compatible()) {
                rejected++;
                Integer count = failures.get(result.failure);
                failures.put(result.failure, count == null ? 1 : count + 1);
            } else if (!result.rules.isEmpty()) converted++;
            else { exact++; return; }
            if (examples.size() >= 32) return;
            Map<String, Object> example = new LinkedHashMap<String, Object>();
            example.put("paletteIndex", paletteIndex);
            example.put("block", block);
            example.put("targetBlock", result.block);
            example.put("outcome", result.compatible() ? "converted" : "rejected");
            if (!result.compatible()) {
                example.put("reason", result.failure);
                if (result.property != null) {
                    example.put("property", result.property);
                    example.put("value", result.value);
                }
            }
            example.put("rules", result.rules);
            examples.add(Collections.unmodifiableMap(example));
        }

        public Map<String, Object> snapshot() {
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("sourceVersion", sourceVersion);
            data.put("targetVersion", targetVersion);
            data.put("exactStates", exact);
            data.put("convertedStates", converted);
            data.put("rejectedStates", rejected);
            data.put("failureCounts", new TreeMap<String, Integer>(failures));
            data.put("examples", new ArrayList<Map<String, Object>>(examples));
            data.put("omittedExamples", converted + rejected - examples.size());
            return data;
        }
    }
}
