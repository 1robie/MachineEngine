package fr.robie.machineengine.core.machine;

import fr.robie.machineengine.api.data.DataMap;
import fr.robie.machineengine.api.error.MachineError;
import fr.robie.machineengine.api.machine.MachineInspection;
import fr.robie.machineengine.api.machine.SchedulerState;
import fr.robie.machineengine.api.world.Direction;
import fr.robie.machineengine.api.world.WorldPosition;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Stands in for a machine whose type is not registered (D3, §21): keeps the saved data verbatim,
 * never ticks, exposes nothing, and writes the data back unchanged so it rehydrates once the addon
 * returns.
 */
public final class PlaceholderMachine implements LoadedMachine {
    private final WorldPosition position;
    private final Key type;
    private final DataMap data;
    private final MachineError reason;

    PlaceholderMachine(@NotNull WorldPosition position, @NotNull Key type, @NotNull DataMap data, @NotNull MachineError reason) {
        this.position = position;
        this.type = type;
        this.data = data;
        this.reason = reason;
    }

    @Override
    public @NotNull WorldPosition position() {
        return this.position;
    }

    @Override
    public @Nullable MachineInstanceImpl instance() {
        return null;
    }

    /** The key of the missing machine type. */
    public @NotNull Key missingType() {
        return this.type;
    }

    public @NotNull MachineError reason() {
        return this.reason;
    }

    @Override
    public @NotNull DataMap save(long gameTime) {
        return this.data;
    }

    @Override
    public @NotNull MachineInspection inspect() {
        Direction facing = this.data.getString(MachineCodec.FACING).flatMap(MachineCodec::parseDirection).orElse(Direction.NORTH);
        return new MachineInspection(this.type, this.position, facing, true, SchedulerState.DISABLED, null, this.reason,
                Map.of(), null, List.of(), List.of(), Map.of(), List.of());
    }

    @Override
    public String toString() {
        return "PlaceholderMachine[" + this.type.asString() + " at " + this.position + "]";
    }
}
