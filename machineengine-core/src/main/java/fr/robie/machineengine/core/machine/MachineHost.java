package fr.robie.machineengine.core.machine;

import fr.robie.machineengine.api.resource.ResourceStack;
import fr.robie.machineengine.api.world.WorldPosition;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * What a platform provides to the machines it hosts (ARCHITECTURE.md §22, §23): time, ownership,
 * neighbors, timers and message passing. The CraftEngine adapter implements it in M3; the test
 * harness implements it with fake worlds and regions.
 */
public interface MachineHost {

    long gameTime(@NotNull Key world);

    /** Whether the calling thread owns {@code position} (Paper: main thread; Folia: its region). */
    boolean isOwnedByCurrentThread(@NotNull WorldPosition position);

    /** The loaded, non-placeholder machine at {@code position}, or {@code null}. Owner thread only. */
    @Nullable MachineInstanceImpl machineAt(@NotNull WorldPosition position);

    /** Wakes {@code machine} by calling {@link MachineInstanceImpl#timerFired(long)} at {@code gameTime}. */
    void scheduleWake(@NotNull MachineInstanceImpl machine, long gameTime);

    /** The machine's persistent state changed and must be saved (§20.2). */
    void markDirty(@NotNull MachineInstanceImpl machine);

    /** The machine switched between ticking and sleeping. */
    void activityChanged(@NotNull MachineInstanceImpl machine);

    /** What the block at {@code position} sees of the world (owner thread). */
    default @NotNull fr.robie.machineengine.api.machine.MachineEnvironment environment(@NotNull WorldPosition position) {
        return fr.robie.machineengine.api.machine.MachineEnvironment.NONE;
    }

    /**
     * A block that is not a machine but holds resources, e.g. a vanilla chest: its handler as seen from
     * {@code side} (the face touched), or null. Owner thread only. Machines push into it and read it as
     * a neighbor; networks fill it, and pull from it only on a transport side set to pull.
     */
    default @Nullable fr.robie.machineengine.api.transfer.ResourceHandler worldHandlerAt(@NotNull WorldPosition position,
                                                                                       @NotNull fr.robie.machineengine.api.world.Direction side) {
        return null;
    }

    /** The fluid in the block at {@code position}, or null (none, not loaded, not owned): E5. */
    default @Nullable fr.robie.machineengine.api.world.WorldFluid worldFluidAt(@NotNull WorldPosition position) {
        return null;
    }

    /** Removes the fluid source block at {@code position}; false if there is none or it is not owned. */
    default boolean takeWorldFluid(@NotNull WorldPosition position) {
        return false;
    }

    /** The block at {@code position} (a CraftEngine or vanilla id), or null if not loaded or not owned: E12. */
    default @Nullable Key blockAt(@NotNull WorldPosition position) {
        return null;
    }

    // -- the world around a machine (owner thread) ---------------------------------------------

    default boolean placeWorldFluid(@NotNull WorldPosition position, @NotNull Key fluid) {
        return false;
    }

    default float worldBlockHardness(@NotNull WorldPosition position) {
        return -1;
    }

    default boolean breakWorldBlock(@NotNull WorldPosition position) {
        return false;
    }

    default @NotNull List<ResourceStack> collectWorldItems(@NotNull WorldPosition position) {
        return List.of();
    }

    default void setBlockProperty(@NotNull WorldPosition position, @NotNull String property, @NotNull String value) {
    }

    default int redstonePower(@NotNull WorldPosition position) {
        return 0;
    }

    default void playSound(@NotNull WorldPosition position, @NotNull Key sound, float volume, float pitch) {
    }

    default void particleLine(@NotNull WorldPosition from, @NotNull WorldPosition to, int color) {
    }

    /** Where the machine's ports are changed (side configuration, a mode, a turn): e.g. to redraw its sides. */
    default void portsChanged(@NotNull MachineInstanceImpl machine) {
    }

    /** The machine started or stopped working visibly ({@link fr.robie.machineengine.api.machine.MachineContext#setActive}). */
    default void activeChanged(@NotNull MachineInstanceImpl machine) {
    }

    /** A committed transaction changed what the machine holds (e.g. to update comparators). */
    default void contentsChanged(@NotNull MachineInstanceImpl machine) {
    }

    /** Runs {@code task} on the thread owning {@code target} (Folia: its region scheduler). */
    void post(@NotNull WorldPosition target, @NotNull Runnable task);

    /**
     * Puts resources into the world at {@code position} (as items, or however the platform handles
     * them). Used for resources that have no machine left to return to, e.g. a handoff answered after
     * its source machine was removed.
     */
    void dropResources(@NotNull WorldPosition position, @NotNull List<ResourceStack> resources);
}
