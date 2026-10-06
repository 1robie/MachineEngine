package fr.robie.machineengine.api.recipe;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** The current recipe snapshot, by type, in registration order. */
@ApiStatus.NonExtendable
public interface RecipeLookup {

    <R extends Recipe> @NotNull List<R> all(@NotNull RecipeType<R> type);

    /** Increments on every recipe reload. */
    long generation();
}
