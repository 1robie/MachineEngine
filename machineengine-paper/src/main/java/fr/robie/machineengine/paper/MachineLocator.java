package fr.robie.machineengine.paper;

import fr.robie.machineengine.api.machine.WakeReason;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.core.machine.LoadedMachine;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/** Finds loaded machines; implemented by the host of the block integration (CraftEngine). */
public interface MachineLocator {

    @Nullable LoadedMachine machine(@NotNull WorldPosition position);

    @NotNull Collection<LoadedMachine> loadedMachines();

    /** Whether the current thread owns {@code position} (Folia: its region; Paper: the main thread). */
    boolean isOwnedByCurrentThread(@NotNull WorldPosition position);

    /**
     * Admin purge (§21), on the thread owning {@code position}: a placeholder machine is deleted with
     * its block and data; a live machine loses the data it keeps for unregistered content.
     *
     * @return what was purged, or {@code null} if there was nothing to purge
     */
    @Nullable String purge(@NotNull WorldPosition position);

    /** Wakes every loaded machine on its owner thread, e.g. after a recipe reload. */
    void wakeAll(@NotNull WakeReason reason);
}
