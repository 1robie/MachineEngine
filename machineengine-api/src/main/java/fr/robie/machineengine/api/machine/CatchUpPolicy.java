package fr.robie.machineengine.api.machine;

import org.jetbrains.annotations.NotNull;

/**
 * What a machine does about the time it spent unloaded (D4, ARCHITECTURE.md §18).
 *
 * @param maxTicks most unloaded ticks that are caught up; 0 means none (frozen in time)
 */
public record CatchUpPolicy(long maxTicks) {
    /** Frozen in time while unloaded: resumes exactly where it stopped. The default. */
    public static final CatchUpPolicy NONE = new CatchUpPolicy(0);

    public CatchUpPolicy {
        if (maxTicks < 0) throw new IllegalArgumentException("maxTicks must not be negative");
    }

    /**
     * On load, {@link MachineBehavior#catchUp} receives the ticks spent unloaded, capped at
     * {@code maxTicks}. The behavior must work in one step, with its own storages only.
     */
    public static @NotNull CatchUpPolicy bounded(long maxTicks) {
        return new CatchUpPolicy(maxTicks);
    }

    public boolean catchesUp() {
        return this.maxTicks > 0;
    }
}
