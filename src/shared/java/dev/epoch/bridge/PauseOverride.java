package dev.epoch.bridge;

/** Restores the user's focus-pause choice when the short command/freeze interval ends. */
public final class PauseOverride {
    private Boolean previous;
    public boolean update(boolean current, boolean required) {
        if (required) { if (previous == null) previous = current; return false; }
        if (previous == null) return current;
        boolean restored = previous; previous = null; return restored;
    }
}
