package fr.robie.machineengine.api.machine;

import fr.robie.machineengine.api.error.MachineError;
import fr.robie.machineengine.api.process.ProcessView;
import fr.robie.machineengine.api.resource.ResourceStack;
import fr.robie.machineengine.api.transfer.StorageView;
import fr.robie.machineengine.api.upgrade.InstalledUpgrade;
import fr.robie.machineengine.api.world.Direction;
import fr.robie.machineengine.api.world.WorldPosition;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of a machine for debugging and admin commands (ARCHITECTURE.md §27).
 *
 * @param type          machine type key (for placeholders: the missing type's key)
 * @param storages      contents per storage id
 * @param escrow        resources in flight to other regions, still owned by this machine
 * @param lockedSlots   slots holding data of unregistered resource types, per storage id
 * @param unknownData   ids of persisted data this machine type no longer declares (kept verbatim)
 */
public record MachineInspection(@NotNull Key type, @NotNull WorldPosition position, @NotNull Direction facing,
                                boolean placeholder, @NotNull SchedulerState state, @Nullable WakeReason lastWakeReason,
                                @Nullable MachineError lastError, @NotNull Map<String, List<StorageView>> storages,
                                @Nullable ProcessView process, @NotNull List<InstalledUpgrade> upgrades,
                                @NotNull List<ResourceStack> escrow, @NotNull Map<String, Integer> lockedSlots,
                                @NotNull List<String> unknownData) {

    public MachineInspection {
        storages = Map.copyOf(storages);
        upgrades = List.copyOf(upgrades);
        escrow = List.copyOf(escrow);
        lockedSlots = Map.copyOf(lockedSlots);
        unknownData = List.copyOf(unknownData);
    }
}
