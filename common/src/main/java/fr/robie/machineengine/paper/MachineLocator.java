package fr.robie.machineengine.paper;

import fr.robie.machineengine.api.machine.WakeReason;
import fr.robie.machineengine.api.network.TransportSideMode;
import fr.robie.machineengine.api.world.Direction;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.core.machine.LoadedMachine;
import net.kyori.adventure.key.Key;
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

    /**
     * Replaces the machine block at {@code position} by the block {@code blockId} (another machine,
     * e.g. the next tier), keeping the facing and what the machine holds (E6). Owner thread only.
     * Nothing drops: the new machine takes the contents over.
     *
     * @return false if there is no machine there, it is busy (a process that cannot be cancelled, a
     *         transfer in flight), or the block could not be placed; nothing changed then
     */
    default boolean replaceBlock(@NotNull WorldPosition position, @NotNull Key blockId) {
        return replaceBlock(position, blockId, null);
    }

    /** {@link #replaceBlock(WorldPosition, Key)} facing {@code facing} ({@code null}: the old facing). */
    default boolean replaceBlock(@NotNull WorldPosition position, @NotNull Key blockId,
                                 @org.jetbrains.annotations.Nullable Direction facing) {
        return false;
    }

    /**
     * Cycles how {@code side} of the transport at {@code position} connects (default, pull, push,
     * none), e.g. with a configurator. Owner thread only.
     *
     * @return the new mode, or {@code null} if there is no transport there
     */
    default @org.jetbrains.annotations.Nullable TransportSideMode cycleTransportSide(
            @NotNull WorldPosition position, @NotNull Direction side) {
        return null;
    }

    /** The locator of the running server's host, or {@code null} (headless, or not started yet). */
    static @org.jetbrains.annotations.Nullable MachineLocator current() {
        return Holder.current;
    }

    /** Set by the host when it starts and stops. */
    static void setCurrent(@org.jetbrains.annotations.Nullable MachineLocator locator) {
        Holder.current = locator;
    }

    final class Holder {
        private static volatile @org.jetbrains.annotations.Nullable MachineLocator current;

        private Holder() {
        }
    }
}
