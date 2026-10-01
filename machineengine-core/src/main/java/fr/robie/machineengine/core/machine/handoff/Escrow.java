package fr.robie.machineengine.core.machine.handoff;

import fr.robie.machineengine.api.resource.ResourceStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Resources that left a machine's storage for a handoff (§16.3). Still owned and persisted by the
 * source until the target acknowledges them.
 *
 * @param storage   source storage the resources came from, and are returned to
 * @param returning the target answered; {@link #stacks()} is the remainder still waiting to be put back
 */
public record Escrow(@NotNull TransferId id, @NotNull Route route, @NotNull String storage,
                     @NotNull List<ResourceStack> stacks, boolean returning) {

    public Escrow {
        stacks = List.copyOf(stacks);
    }

    public @NotNull Escrow returning(@NotNull List<ResourceStack> remainder) {
        return new Escrow(this.id, this.route, this.storage, remainder, true);
    }
}
