package fr.robie.machineengine.api.machine;

import java.util.Set;

/**
 * Scheduling state of a machine (ARCHITECTURE.md §17.1). Only {@link #ACTIVE} machines tick; the
 * others wait for one of their {@linkplain #wakesOn(WakeReason) wake reasons}.
 */
public enum SchedulerState {
    /** Ticks every game tick. */
    ACTIVE,
    /** Sleeps until a known game tick. */
    SCHEDULED,
    /** Needs input; wakes when its storages receive something. */
    WAITING,
    /** Output full or a dependency missing; wakes when something leaves its storages. */
    BLOCKED,
    /** Nothing to do until any signal. */
    SLEEPING,
    /** Never ticks (placeholder, or disabled by configuration or errors). */
    DISABLED;

    private static final Set<WakeReason> ALWAYS = Set.of(WakeReason.TIMER, WakeReason.CONFIG_CHANGED,
            WakeReason.UPGRADE_CHANGED, WakeReason.RECIPES_RELOADED, WakeReason.CHUNK_LOADED);
    private static final Set<WakeReason> INPUT = Set.of(WakeReason.RESOURCE_INSERTED, WakeReason.INPUT_CHANGED,
            WakeReason.NEIGHBOR_CHANGED);
    private static final Set<WakeReason> OUTPUT = Set.of(WakeReason.RESOURCE_EXTRACTED, WakeReason.OUTPUT_AVAILABLE,
            WakeReason.NEIGHBOR_CHANGED, WakeReason.NETWORK_CHANGED);

    public boolean isSleeping() {
        return this != ACTIVE;
    }

    /** @return whether {@code reason} makes a machine in this state active again */
    public boolean wakesOn(WakeReason reason) {
        return switch (this) {
            case ACTIVE, DISABLED -> false;
            case SLEEPING -> true;
            case SCHEDULED -> ALWAYS.contains(reason) || reason.isExternal();
            case WAITING -> ALWAYS.contains(reason) || reason.isExternal() || INPUT.contains(reason);
            case BLOCKED -> ALWAYS.contains(reason) || reason.isExternal() || OUTPUT.contains(reason);
        };
    }
}
