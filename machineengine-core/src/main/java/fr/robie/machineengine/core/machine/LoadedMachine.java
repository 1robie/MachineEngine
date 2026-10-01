package fr.robie.machineengine.core.machine;

import fr.robie.machineengine.api.data.DataMap;
import fr.robie.machineengine.api.machine.MachineInspection;
import fr.robie.machineengine.api.world.WorldPosition;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** What a host holds per machine block: a live machine or a placeholder for missing content (D3). */
public sealed interface LoadedMachine permits MachineInstanceImpl, PlaceholderMachine {

    @NotNull WorldPosition position();

    /** @return the live machine, or {@code null} for a placeholder */
    @Nullable MachineInstanceImpl instance();

    /** Encodes the persistent state, as of {@code gameTime}. */
    @NotNull DataMap save(long gameTime);

    @NotNull MachineInspection inspect();
}
