package fr.robie.machineengine.api.machine;

import fr.robie.machineengine.api.transfer.ResourceHandler;
import fr.robie.machineengine.api.world.WorldPosition;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Reaching other machines by position ({@link MachineContext#machines()}), e.g. the members of one
 * multiblock. Only machines that are loaded and owned by the calling thread can be reached.
 */
@ApiStatus.NonExtendable
public interface OtherMachines {

    /**
     * Full access to a storage of the machine at {@code machine} (e.g. a multiblock member reaching
     * the structure's storage, kept by another member).
     */
    @NotNull Optional<ResourceHandler> storage(@NotNull WorldPosition machine, @NotNull String storage);

    /** Wakes the machine at {@code machine} (e.g. a multiblock's anchor when its structure formed). */
    void wake(@NotNull WorldPosition machine);
}
