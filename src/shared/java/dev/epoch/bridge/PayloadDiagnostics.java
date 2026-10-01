package dev.epoch.bridge;

import java.util.*;

/** Separate from state coverage: a supported state may still reject its saved NBT. */
public final class PayloadDiagnostics {
    private int rejected;
    private final List<Map<String, Object>> examples = new ArrayList<Map<String, Object>>();
    public void reject(int index, String state, Exception error) {
        rejected++;
        if (examples.size() >= 32) return;
        Map<String, Object> entry = new LinkedHashMap<String, Object>();
        entry.put("positionIndex", index);
        entry.put("state", state);
        // Avoid serializing arbitrary exception objects or complete container contents.
        entry.put("reason", error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage()));
        examples.add(entry);
    }
    public Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("rejectedPositions", rejected);
        result.put("examples", new ArrayList<Map<String, Object>>(examples));
        result.put("omittedExamples", rejected - examples.size());
        return result;
    }
}
