package fr.robie.machineengine.api.upgrade;

import fr.robie.machineengine.api.MachineEngineKeys;

/**
 * Modifier types for mechanisms MachineEngine owns (ARCHITECTURE.md §14). Addons add their own for
 * anything else.
 */
public final class StandardModifiers {
    /** Ticks a process takes. Lower is faster; at least 1. */
    public static final ModifierType PROCESS_DURATION = ModifierType.of(MachineEngineKeys.key("process_duration"), 1, Long.MAX_VALUE);
    /** Amount of each per-tick process cost. */
    public static final ModifierType PROCESS_COST = ModifierType.nonNegative(MachineEngineKeys.key("process_cost"));
    /** Per-tick limit of rate-limited ports. */
    public static final ModifierType TRANSFER_RATE = ModifierType.nonNegative(MachineEngineKeys.key("transfer_rate"));

    private StandardModifiers() {
    }
}
