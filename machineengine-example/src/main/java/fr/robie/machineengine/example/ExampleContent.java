package fr.robie.machineengine.example;

import fr.robie.machineengine.api.addon.RegistrationContext;
import fr.robie.machineengine.api.machine.MachineType;
import fr.robie.machineengine.api.machine.PortDefinition;
import fr.robie.machineengine.api.machine.PortDirection;
import fr.robie.machineengine.api.machine.ProcessingBehavior;
import fr.robie.machineengine.api.machine.StorageDefinition;
import fr.robie.machineengine.api.process.ProcessBinding;
import fr.robie.machineengine.api.recipe.ProcessingRecipe;
import fr.robie.machineengine.api.recipe.RecipeType;
import fr.robie.machineengine.api.resource.ResourceCategory;
import fr.robie.machineengine.api.resource.ResourceType;
import fr.robie.machineengine.api.resource.match.ResourceMatcher;
import fr.robie.machineengine.api.resource.match.ResourcePredicate;
import fr.robie.machineengine.api.world.RelativeSide;
import net.kyori.adventure.key.Key;

import java.util.List;

/**
 * Everything the example addon registers. The generator makes 10 sparks every second and pushes them
 * out of its front; the consumer takes sparks through its back and burns 5 every half second.
 */
final class ExampleContent {
    static final ResourceCategory CHARGE = ResourceCategory.builder(Key.key("example", "charge")).unit("sparks").build();
    static final ResourceType SPARK = ResourceType.of(Key.key("example", "spark"), CHARGE);

    static final RecipeType<ProcessingRecipe> GENERATING = RecipeType.of(Key.key("example", "generating"), ProcessingRecipe.class);
    static final RecipeType<ProcessingRecipe> BURNING = RecipeType.of(Key.key("example", "burning"), ProcessingRecipe.class);

    static final MachineType GENERATOR = MachineType.builder(Key.key("example", "generator"))
            .storage(StorageDefinition.tank("output", 100))
            .port(PortDefinition.builder("out", "output").direction(PortDirection.OUTPUT).sides(RelativeSide.FRONT).build())
            .behavior(ProcessingBehavior.pushing(GENERATING, new ProcessBinding(List.of(), "output", null), RelativeSide.FRONT, 20))
            .build();

    static final MachineType CONSUMER = MachineType.builder(Key.key("example", "consumer"))
            .storage(StorageDefinition.tank("input", 50).filter(ResourcePredicate.type(SPARK)))
            .port(PortDefinition.builder("in", "input").direction(PortDirection.INPUT).sides(RelativeSide.BACK)
                    .filter(ResourcePredicate.type(SPARK)).build())
            .behavior(ProcessingBehavior.of(BURNING, ProcessBinding.of("input", "input")))
            .build();

    private ExampleContent() {
    }

    static void register(RegistrationContext context) {
        context.resourceCategories().register(CHARGE);
        context.resourceTypes().register(SPARK);
        context.recipeTypes().register(GENERATING);
        context.recipeTypes().register(BURNING);
        context.machineTypes().register(GENERATOR);
        context.machineTypes().register(CONSUMER);
        context.recipes(reload -> {
            reload.register(ProcessingRecipe.builder(Key.key("example", "make_sparks"), GENERATING)
                    .output(SPARK.stack(10)).duration(20).build());
            reload.register(ProcessingRecipe.builder(Key.key("example", "burn_sparks"), BURNING)
                    .input(ResourceMatcher.of(ResourcePredicate.type(SPARK), 5)).duration(10).build());
        });
    }
}
