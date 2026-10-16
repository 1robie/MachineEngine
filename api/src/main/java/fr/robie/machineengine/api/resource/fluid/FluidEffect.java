package fr.robie.machineengine.api.resource.fluid;

import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;

/**
 * What a placed fluid does to an entity inside it (potion, damage, fire …). Made by a
 * {@link FluidEffectType} from a definition, or written in code. Runs on the entity's thread.
 */
@FunctionalInterface
public interface FluidEffect {
    /**
     * Called every 10 ticks while {@code entity} is inside {@code fluid}.
     *
     * @param ticksInside how long the entity has been inside, in ticks
     */
    void tick(@NotNull Entity entity, @NotNull FluidType fluid, int ticksInside);

    /** Called once when the entity leaves the fluid (or the fluid goes away). */
    default void leave(@NotNull Entity entity, @NotNull FluidType fluid) {
    }
}
