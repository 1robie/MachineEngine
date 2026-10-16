package fr.robie.machineengine.api.resource.fluid;

import fr.robie.machineengine.api.resource.ResourceType;
import org.bukkit.block.Block;
import org.jetbrains.annotations.NotNull;

/**
 * What happens when a placed fluid flows into another fluid (or plain water): stop, replace it, turn
 * into a block … Made by a {@link FluidReactionType} from a definition, or written in code. Runs on
 * the block's thread.
 */
@FunctionalInterface
public interface FluidReaction {
    /**
     * @param at       the block the fluid would flow into
     * @param incoming the fluid flowing in
     * @param present  the fluid already there ({@link fr.robie.machineengine.api.resource.Fluids#water()} for plain water)
     * @return whether the incoming fluid still flows into the block
     */
    @NotNull Outcome react(@NotNull Block at, @NotNull FluidType incoming, @NotNull ResourceType present);

    enum Outcome {
        /** The incoming fluid flows in (it replaces what was there). */
        FLOW,
        /** The incoming fluid stops; the reaction may have changed the block itself. */
        STOP
    }

    /** Both stop where they meet: the default. */
    FluidReaction STOP = (at, incoming, present) -> Outcome.STOP;
}
