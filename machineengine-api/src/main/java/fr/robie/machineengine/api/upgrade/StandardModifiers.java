package fr.robie.machineengine.api.upgrade;

import fr.robie.machineengine.api.MachineEngineKeys;
import fr.robie.machineengine.api.resource.ResourceCategory;
import org.jetbrains.annotations.NotNull;

/**
 * Modifier types for mechanisms MachineEngine owns (ARCHITECTURE.md §14). Addons add their own for
 * anything else.
 */
public final class StandardModifiers {
    /** Ticks a process takes. Lower is faster; at least 1. */
    public static final ModifierType PROCESS_DURATION = ModifierType.of(MachineEngineKeys.key("process_duration"), 1, Long.MAX_VALUE);
    /** Amount of each per-tick process cost. */
    public static final ModifierType PROCESS_COST = ModifierType.nonNegative(MachineEngineKeys.key("process_cost"));
    /**
     * Processes a {@code ProcessingBehavior} may complete per tick (base 1). Only one-tick processes
     * benefit: a process cannot take less than a tick, so these machines run several instead.
     */
    public static final ModifierType PROCESSES_PER_TICK = ModifierType.of(MachineEngineKeys.key("processes_per_tick"), 1, 1024);
    /** Per-tick limit of rate-limited ports. */
    public static final ModifierType TRANSFER_RATE = ModifierType.nonNegative(MachineEngineKeys.key("transfer_rate"));

    private static final java.util.Map<net.kyori.adventure.key.Key, ModifierType> COSTS = new java.util.concurrent.ConcurrentHashMap<>();

    private StandardModifiers() {
    }

    /**
     * Amount of each per-tick process cost of one resource category, applied after
     * {@link #PROCESS_COST} (an energy upgrade lowers energy costs only). One instance per category;
     * the addon owning the category registers it.
     */
    public static @NotNull ModifierType processCost(@NotNull ResourceCategory category) {
        // In the category's namespace: the addon owning the category registers it.
        return COSTS.computeIfAbsent(category.key(), key -> ModifierType.nonNegative(
                net.kyori.adventure.key.Key.key(key.namespace(), "process_cost/" + key.value())));
    }
}
