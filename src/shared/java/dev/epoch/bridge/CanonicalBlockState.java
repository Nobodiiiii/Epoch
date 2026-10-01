package dev.epoch.bridge;

import java.util.*;

/** Version-neutral view; the original SNBT remains the authority for preservation. */
public final class CanonicalBlockState {
    public final String block, originalSnbt;
    public final Map<String, String> properties;
    public final boolean legacyPayload;

    public CanonicalBlockState(String block, Map<String, String> properties,
                               boolean legacyPayload, String originalSnbt) {
        this.block = Objects.requireNonNull(block);
        this.properties = Collections.unmodifiableMap(new TreeMap<String, String>(properties));
        this.legacyPayload = legacyPayload;
        this.originalSnbt = Objects.requireNonNull(originalSnbt);
    }
}
