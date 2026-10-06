package fr.robie.machineengine.api.machine;

import org.jetbrains.annotations.NotNull;

/**
 * Addon-provided logic of a machine (ARCHITECTURE.md §12). One instance per machine, created by the
 * type's {@link Factory}. All methods run on the machine's owner thread.
 *
 * <p>Behaviors only touch the world through the {@link MachineContext}. State that must survive a
 * restart goes into {@link MachineDataKey}s, storages and the process slot, never into fields.</p>
 */
public interface MachineBehavior {

    /** Called each tick while the machine is {@link SchedulerState#ACTIVE}. */
    void tick(@NotNull MachineContext context);

    /** Called before the first tick after waking up. */
    default void onWake(@NotNull MachineContext context, @NotNull WakeReason reason) {
    }

    /** Called after the machine was placed or loaded, before it first ticks. */
    default void onLoad(@NotNull MachineContext context) {
    }

    /** Called before the machine unloads (its chunk unloads or the server stops). */
    default void onUnload(@NotNull MachineContext context) {
    }

    /**
     * Called once after loading when the type's {@link CatchUpPolicy} catches up: {@code ticks} is the
     * time spent unloaded, capped. Must be closed-form and touch only the machine's own storages
     * (no neighbors, no networks, no world). The machine is woken afterwards.
     */
    default void catchUp(@NotNull MachineContext context, long ticks) {
    }

    /** Called when the machine is removed from the world. */
    default void onRemove(@NotNull MachineContext context) {
    }

    @FunctionalInterface
    interface Factory {
        @NotNull MachineBehavior create(@NotNull MachineType type);
    }
}
