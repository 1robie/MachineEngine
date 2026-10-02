package fr.robie.machineengine.api.world;

import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;

/**
 * A fluid in a world block, as the platform names it (e.g. {@code minecraft:water}): the core gives
 * these ids no meaning (E5).
 *
 * @param source whether the block is a source block (it can be picked up), not flowing fluid
 */
public record WorldFluid(@NotNull Key fluid, boolean source) {
}
