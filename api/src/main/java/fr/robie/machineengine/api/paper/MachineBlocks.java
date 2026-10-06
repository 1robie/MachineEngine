package fr.robie.machineengine.api.paper;

import fr.robie.machineengine.api.network.TransportSideMode;
import fr.robie.machineengine.api.world.Direction;
import fr.robie.machineengine.api.world.WorldPosition;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Changes machine and transport blocks in the world. Implemented by the block integration
 * (CraftEngine); {@link #current()} is empty headless and before MachineEngine has enabled.
 */
public interface MachineBlocks {
    /**
     * Replaces the machine block at {@code position} by the block {@code blockId} (another machine,
     * e.g. the next tier), keeping what the machine holds. Owner thread only. Nothing drops: the new
     * machine takes the contents over.
     *
     * @param facing the new block's facing, or {@code null} for the old one
     * @return false if there is no machine there, it is busy (a process that cannot be cancelled, a
     *         transfer in flight), or the block could not be placed; nothing changed then
     */
    boolean replaceBlock(@NotNull WorldPosition position, @NotNull Key blockId, @Nullable Direction facing);

    /** {@link #replaceBlock(WorldPosition, Key, Direction)} keeping the facing. */
    default boolean replaceBlock(@NotNull WorldPosition position, @NotNull Key blockId) {
        return replaceBlock(position, blockId, null);
    }

    /**
     * Cycles how {@code side} of the transport at {@code position} connects (default, pull, push,
     * none), e.g. with a configurator. Owner thread only.
     *
     * @return the new mode, or {@code null} if there is no transport there
     */
    @Nullable TransportSideMode cycleTransportSide(@NotNull WorldPosition position, @NotNull Direction side);

    /** The running server's block integration, or {@code null}. */
    static @Nullable MachineBlocks current() {
        return Holder.current;
    }

    /** Set by the block integration when it starts and stops. */
    @ApiStatus.Internal
    static void setCurrent(@Nullable MachineBlocks blocks) {
        Holder.current = blocks;
    }

    @ApiStatus.Internal
    final class Holder {
        private static volatile @Nullable MachineBlocks current;

        private Holder() {
        }
    }
}
