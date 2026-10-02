package fr.robie.machineengine.api.definition;

import fr.robie.machineengine.api.data.DataValue;
import fr.robie.machineengine.api.error.Result;
import fr.robie.machineengine.api.recipe.ProcessingRecipe;
import fr.robie.machineengine.api.recipe.RecipeType;
import fr.robie.machineengine.api.registry.RegistryAccess;
import fr.robie.machineengine.api.resource.ResourceStack;
import fr.robie.machineengine.api.resource.match.ResourceMatcher;
import fr.robie.machineengine.api.resource.match.ResourcePredicate;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

/**
 * Helpers a {@link BehaviorType.Parser} uses to read the shared definition vocabulary, so every
 * behavior writes filters, stacks and recipe types the same way.
 */
@ApiStatus.NonExtendable
public interface DefinitionContext {

    @NotNull RegistryAccess registries();

    /**
     * Filters: {@code {type: k}}, {@code {types: [k…]}}, {@code {category: k}}, {@code {tag: k}},
     * {@code {item: minecraft:x}}, {@code {any: true}}, {@code {all: […]}}, {@code {any-of: […]}},
     * {@code {not: {…}}}.
     */
    @NotNull Result<ResourcePredicate> predicate(@NotNull DataValue spec);

    /** A filter plus {@code amount}. */
    @NotNull Result<ResourceMatcher> matcher(@NotNull DataValue spec);

    /** {@code {type: k, amount: n}} or {@code {item: minecraft:x, amount: n}}. */
    @NotNull Result<ResourceStack> stack(@NotNull DataValue spec);

    @NotNull Result<RecipeType<ProcessingRecipe>> processingRecipeType(@NotNull DataValue spec);
}
