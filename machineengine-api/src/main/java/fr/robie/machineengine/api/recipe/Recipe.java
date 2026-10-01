package fr.robie.machineengine.api.recipe;

import net.kyori.adventure.key.Keyed;
import org.jetbrains.annotations.NotNull;

/**
 * A transformation (ARCHITECTURE.md §13). Recipes describe what happens, not how it executes, and
 * live in the reloadable {@code machineengine:recipe} registry.
 */
public interface Recipe extends Keyed {

    @NotNull RecipeType<?> type();
}
