package fr.robie.machineengine.api.recipe;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.Keyed;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * A family of recipes, e.g. {@code mymod:refining}. Machines look up recipes by type.
 *
 * @param <R> the recipe class of this family
 */
public final class RecipeType<R extends Recipe> implements Keyed {
    private final Key key;
    private final Class<R> recipeClass;

    private RecipeType(Key key, Class<R> recipeClass) {
        this.key = key;
        this.recipeClass = recipeClass;
    }

    public static <R extends Recipe> @NotNull RecipeType<R> of(@NotNull Key key, @NotNull Class<R> recipeClass) {
        return new RecipeType<>(Objects.requireNonNull(key), Objects.requireNonNull(recipeClass));
    }

    @Override
    public @NotNull Key key() {
        return this.key;
    }

    public @NotNull Class<R> recipeClass() {
        return this.recipeClass;
    }

    @Override
    public String toString() {
        return "RecipeType[" + this.key.asString() + "]";
    }
}
