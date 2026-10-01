package fr.robie.machineengine.core.machine;

import fr.robie.machineengine.api.recipe.Recipe;
import fr.robie.machineengine.api.recipe.RecipeLookup;
import fr.robie.machineengine.api.recipe.RecipeType;
import fr.robie.machineengine.api.registry.ReloadableRegistry;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Recipes indexed by type, rebuilt lazily when the recipe registry's generation changes. */
public final class CoreRecipeLookup implements RecipeLookup {
    private final ReloadableRegistry<Recipe> recipes;
    private volatile Index index = new Index(-1, Map.of());

    public CoreRecipeLookup(@NotNull ReloadableRegistry<Recipe> recipes) {
        this.recipes = recipes;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <R extends Recipe> @NotNull List<R> all(@NotNull RecipeType<R> type) {
        return (List<R>) current().byType.getOrDefault(type, List.of());
    }

    @Override
    public long generation() {
        return this.recipes.generation();
    }

    private Index current() {
        Index current = this.index;
        long generation = this.recipes.generation();
        if (current.generation == generation) return current;
        Map<RecipeType<?>, List<Recipe>> byType = new IdentityHashMap<>();
        for (Recipe recipe : this.recipes.values()) {
            if (recipe.type().recipeClass().isInstance(recipe)) {
                byType.computeIfAbsent(recipe.type(), type -> new ArrayList<>()).add(recipe);
            }
        }
        byType.replaceAll((type, list) -> List.copyOf(list));
        Index rebuilt = new Index(generation, byType);
        this.index = rebuilt;
        return rebuilt;
    }

    private record Index(long generation, Map<RecipeType<?>, List<Recipe>> byType) {
    }
}
