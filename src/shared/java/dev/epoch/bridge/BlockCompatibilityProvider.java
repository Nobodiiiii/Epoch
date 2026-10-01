package dev.epoch.bridge;

import java.util.List;

/** Register with META-INF/services/dev.epoch.bridge.BlockCompatibilityProvider. */
public interface BlockCompatibilityProvider {
    List<CompatibilityRules.Rule> rules();
}
