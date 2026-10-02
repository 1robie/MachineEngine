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
