package fr.robie.machineengine.core.machine.handoff;

import fr.robie.machineengine.api.world.WorldPosition;
import org.jetbrains.annotations.NotNull;

/** Identifies one handoff: the source machine and a per-machine sequence number (persisted). */
public record TransferId(@NotNull WorldPosition source, long sequence) {

    @Override
    public @NotNull String toString() {
        return this.source + "#" + this.sequence;
    }
}
