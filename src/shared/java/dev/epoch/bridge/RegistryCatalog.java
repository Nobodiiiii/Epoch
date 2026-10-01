package dev.epoch.bridge;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Real registry inventory and complete (not heartbeat-truncated) state audit. */
public final class RegistryCatalog {
    public int schema = 1;
    public String version;
    public Map<String, String> mods = new TreeMap<String, String>();
    public List<State> states = new ArrayList<State>();
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    public static final class State {
        public String block, snbt;
        public boolean blockEntity;
        public State(String block, String snbt, boolean blockEntity) {
            this.block = block; this.snbt = snbt; this.blockEntity = blockEntity;
        }
    }

    public RegistryCatalog(String version, Map<String, String> mods) {
        this.version = version; this.mods.putAll(mods);
    }

    public void write(Path path) throws IOException { writeJson(path, this); }

    public static RegistryCatalog read(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            RegistryCatalog catalog = GSON.fromJson(reader, RegistryCatalog.class);
            if (catalog == null || catalog.schema != 1 || catalog.version == null || catalog.mods == null || catalog.states == null
                    || catalog.states.size() > 1000000) throw new IOException("Invalid registry catalog");
            for (State state : catalog.states) if (state == null || state.block == null || state.snbt == null) throw new IOException("Invalid registry state");
            return catalog;
        } catch (RuntimeException error) { throw new IOException("Malformed registry catalog", error); }
    }

    public interface Decoder { Outcome decode(String snbt) throws Exception; }

    public static final class Outcome {
        public final String level, reason, targetBlock, property, value;
        public final List<String> rules;
        public Outcome(String level, String reason, String targetBlock, String property, String value, List<String> rules) {
            this.level = level; this.reason = reason; this.targetBlock = targetBlock;
            this.property = property; this.value = value; this.rules = rules;
        }
        public static Outcome modern(BlockStateCompatibility.Result result) {
            return new Outcome(!result.compatible() ? "PRESERVED" : result.rules.isEmpty() ? "EXACT" : "CONVERTED",
                result.failure, result.block, result.property, result.value, result.rules);
        }
        public static Outcome legacy(boolean accepted, String targetBlock) {
            return new Outcome(accepted ? "CONVERTED" : "PRESERVED", accepted ? null : "legacy_unrepresentable",
                targetBlock, null, null, Collections.<String>emptyList());
        }
    }

    public static Map<String, Object> audit(RegistryCatalog source, String targetVersion,
            Map<String, String> targetMods, String fingerprint, Decoder decoder) throws Exception {
        List<Map<String, Object>> details = new ArrayList<Map<String, Object>>();
        Map<String, Integer> totals = new TreeMap<String, Integer>(), reasons = new TreeMap<String, Integer>();
        int entityStates = 0;
        for (State state : source.states) {
            // Decode failures are tool/adapter errors, never counted as supported preservation.
            Outcome outcome = decoder.decode(state.snbt);
            increment(totals, outcome.level);
            if (outcome.reason != null) increment(reasons, outcome.reason);
            if (state.blockEntity) entityStates++;
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("block", state.block); row.put("snbt", state.snbt);
            row.put("requiresPayloadCheck", state.blockEntity);
            row.put("result", outcome); details.add(row);
        }
        Map<String, Object> report = new LinkedHashMap<String, Object>();
        report.put("schema", 1); report.put("sourceVersion", source.version); report.put("targetVersion", targetVersion);
        report.put("sourceMods", source.mods); report.put("targetMods", new TreeMap<String, String>(targetMods));
        report.put("rulesSha256", fingerprint); report.put("totalStates", source.states.size());
        report.put("stateTotals", totals); report.put("failureCounts", reasons);
        report.put("blockEntityStatesNotPayloadTested", entityStates);
        report.put("scope", "state decoding only; no world, block entity payload or neighbor behavior validation");
        report.put("states", details);
        return report;
    }

    private static void increment(Map<String, Integer> counts, String key) {
        Integer count = counts.get(key); counts.put(key, count == null ? 1 : count + 1);
    }

    public static void writeJson(Path path, Object value) throws IOException {
        Path absolute = path.toAbsolutePath(); Files.createDirectories(absolute.getParent());
        Path temp = Files.createTempFile(absolute.getParent(), "compatibility-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) { GSON.toJson(value, writer); }
            try { Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}
