package fr.robie.machineengine.core.machine.handoff;

import fr.robie.machineengine.api.world.Direction;
import fr.robie.machineengine.api.world.WorldPosition;
import org.jetbrains.annotations.NotNull;

/**
 * Where a handoff goes: the target machine and the face resources enter through.
 */
public record Route(@NotNull WorldPosition target, @NotNull Direction side) {
}
