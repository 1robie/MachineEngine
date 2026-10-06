package fr.robie.machineengine.core.machine;

import fr.robie.machineengine.api.machine.StorageDefinition;
import fr.robie.machineengine.api.transfer.storage.CombinedResourceHandler;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** One storage of a machine: its slots, built from a {@link StorageDefinition}. */
public final class MachineStorage extends CombinedResourceHandler {
    private final StorageDefinition definition;
    private final List<MachineSlot> slots;

    MachineStorage(@NotNull StorageDefinition definition, @NotNull List<MachineSlot> slots) {
        super(slots);
        this.definition = definition;
        this.slots = List.copyOf(slots);
    }

    public @NotNull StorageDefinition definition() {
        return this.definition;
    }

    public @NotNull List<MachineSlot> slots() {
        return this.slots;
    }

    public int lockedSlots() {
        return (int) this.slots.stream().filter(slot -> slot.lockedData() != null).count();
    }
}
