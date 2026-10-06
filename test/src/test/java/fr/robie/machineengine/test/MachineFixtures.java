package fr.robie.machineengine.test;

import fr.robie.machineengine.api.addon.AddonRegistration;
import fr.robie.machineengine.api.data.Codecs;
import fr.robie.machineengine.api.data.VersionedCodec;
import fr.robie.machineengine.api.machine.MachineBehavior;
import fr.robie.machineengine.api.machine.MachineContext;
import fr.robie.machineengine.api.machine.MachineDataKey;
import fr.robie.machineengine.api.machine.MachineType;
import fr.robie.machineengine.api.machine.PortDefinition;
import fr.robie.machineengine.api.machine.PortDirection;
import fr.robie.machineengine.api.machine.ProcessingBehavior;
import fr.robie.machineengine.api.machine.StorageDefinition;
import fr.robie.machineengine.api.process.ProcessBinding;
import fr.robie.machineengine.api.recipe.ProcessingRecipe;
import fr.robie.machineengine.api.recipe.RecipeType;
import fr.robie.machineengine.api.resource.ResourceCategory;
import fr.robie.machineengine.api.resource.ResourceStack;
import fr.robie.machineengine.api.resource.ResourceType;
import fr.robie.machineengine.api.resource.match.ResourceMatcher;
import fr.robie.machineengine.api.resource.match.ResourcePredicate;
import fr.robie.machineengine.api.transfer.Transaction;
import fr.robie.machineengine.api.upgrade.Modifier;
import fr.robie.machineengine.api.upgrade.StandardModifiers;
import fr.robie.machineengine.api.upgrade.UpgradeType;
import fr.robie.machineengine.api.world.RelativeSide;
import fr.robie.machineengine.core.lifecycle.CoreMachineEngine;
import fr.robie.machineengine.core.machine.MachineInstanceImpl;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Two independent addons, written the way third parties would (ARCHITECTURE.md §36):
 * <ul>
 *   <li><b>alpha</b> defines a resource category, resources {@code alpha:a} and {@code alpha:b}, a
 *       generator that makes {@code alpha:b} and pushes it out of its front, and a pusher that just
 *       pushes what it holds.</li>
 *   <li><b>beta</b> (requires alpha) defines a consumer that eats {@code alpha:b} through its back
 *       port, a slow variant, and a chest.</li>
 * </ul>
 */
final class MachineFixtures {
    static final ResourceCategory MATTER = ResourceCategory.builder(Key.key("alpha", "matter")).unit("u").build();
    static final ResourceType A = ResourceType.of(Key.key("alpha", "a"), MATTER);
    static final ResourceType B = ResourceType.of(Key.key("alpha", "b"), MATTER);

    static final RecipeType<ProcessingRecipe> GENERATING = RecipeType.of(Key.key("alpha", "generating"), ProcessingRecipe.class);
    static final ProcessingRecipe MAKE_B = ProcessingRecipe.builder(Key.key("alpha", "make_b"), GENERATING)
            .output(B.stack(10)).duration(20).build();

    static final MachineDataKey<Long> PUSHED = MachineDataKey.of("pushed",
            VersionedCodec.builder(Key.key("alpha", "pushed"), Codecs.LONG).build(), 0L);

    static final MachineType GENERATOR = MachineType.builder(Key.key("alpha", "generator"))
            .storage(StorageDefinition.tank("output", 40))
            .port(PortDefinition.builder("out", "output").direction(PortDirection.OUTPUT).sides(RelativeSide.FRONT).build())
            .upgradeSlots(2, upgrade -> true)
            .behavior(ProcessingBehavior.pushing(GENERATING, new ProcessBinding(List.of(), "output", null), RelativeSide.FRONT, 20))
            .build();

    /** Pushes up to 10 per tick from its output to its front; counts what left. */
    static final MachineType PUSHER = MachineType.builder(Key.key("alpha", "pusher"))
            .storage(StorageDefinition.tank("output", 1000))
            .data(PUSHED)
            .behavior(type -> new MachineBehavior() {
                @Override
                public void tick(@NotNull MachineContext context) {
                    long moved = context.push(RelativeSide.FRONT, "output", variant -> true, 10).moved();
                    context.set(PUSHED, context.get(PUSHED) + moved);
                    if (context.storage("output").views().stream().allMatch(view -> view.isEmpty())) context.sleep();
                }
            })
            .build();

    static final UpgradeType SPEED = UpgradeType.of(Key.key("alpha", "speed"), 4,
            Modifier.addMultipliedBase(StandardModifiers.PROCESS_DURATION, -250));

    static final RecipeType<ProcessingRecipe> CONSUMING = RecipeType.of(Key.key("beta", "consuming"), ProcessingRecipe.class);
    static final RecipeType<ProcessingRecipe> SLOW_CONSUMING = RecipeType.of(Key.key("beta", "slow_consuming"), ProcessingRecipe.class);
    static final ProcessingRecipe EAT_B = ProcessingRecipe.builder(Key.key("beta", "eat_b"), CONSUMING)
            .input(ResourceMatcher.of(ResourcePredicate.type(B), 5)).duration(10).build();
    static final ProcessingRecipe EAT_B_SLOWLY = ProcessingRecipe.builder(Key.key("beta", "eat_b_slowly"), SLOW_CONSUMING)
            .input(ResourceMatcher.of(ResourcePredicate.type(B), 5)).duration(10_000).build();

    static final MachineType CONSUMER = consumer("consumer", CONSUMING);
    static final MachineType SLOW_CONSUMER = consumer("slow_consumer", SLOW_CONSUMING);

    static final MachineType CHEST = MachineType.builder(Key.key("beta", "chest"))
            .storage(StorageDefinition.slots("items", 4, 1000))
            .port(PortDefinition.builder("io", "items").build())
            .behavior(type -> MachineContext::sleep)
            .build();

    private MachineFixtures() {
    }

    private static MachineType consumer(String name, RecipeType<ProcessingRecipe> recipes) {
        return MachineType.builder(Key.key("beta", name))
                .storage(StorageDefinition.tank("input", 20).filter(ResourcePredicate.type(B)))
                .port(PortDefinition.builder("in", "input").direction(PortDirection.INPUT).sides(RelativeSide.BACK)
                        .filter(ResourcePredicate.type(B)).build())
                .behavior(ProcessingBehavior.of(recipes, ProcessBinding.of("input", "input")))
                .build();
    }

    static AddonRegistration alpha() {
        return AddonRegistration.builder("alpha", "1.0.0").register(ctx -> {
            ctx.resourceCategories().register(MATTER);
            ctx.resourceTypes().register(A);
            ctx.resourceTypes().register(B);
            ctx.recipeTypes().register(GENERATING);
            ctx.upgradeTypes().register(SPEED);
            ctx.machineTypes().register(GENERATOR);
            ctx.machineTypes().register(PUSHER);
            ctx.recipes(reload -> reload.register(MAKE_B));
        }).build();
    }

    static AddonRegistration beta() {
        return AddonRegistration.builder("beta", "1.0.0").requires("alpha", "1.0").register(ctx -> {
            ctx.recipeTypes().register(CONSUMING);
            ctx.recipeTypes().register(SLOW_CONSUMING);
            ctx.machineTypes().register(CONSUMER);
            ctx.machineTypes().register(SLOW_CONSUMER);
            ctx.machineTypes().register(CHEST);
            ctx.recipes(reload -> {
                reload.register(EAT_B);
                reload.register(EAT_B_SLOWLY);
            });
        }).build();
    }

    static CoreMachineEngine bothAddons() {
        CoreMachineEngine engine = TestEngine.builder().addon(alpha()).addon(beta()).start();
        if (engine.startupReport().hasErrors()) throw new AssertionError(engine.startupReport().format());
        return engine;
    }

    static CoreMachineEngine alphaOnly() {
        CoreMachineEngine engine = TestEngine.builder().addon(alpha()).start();
        if (engine.startupReport().hasErrors()) throw new AssertionError(engine.startupReport().format());
        return engine;
    }

    /** Puts resources straight into a machine storage (test setup). */
    static void fill(MachineInstanceImpl machine, String storage, ResourceStack stack) {
        try (Transaction transaction = machine.services().transactions().openOuter()) {
            long inserted = machine.context().storage(storage).insert(stack.variant(), stack.amount(), transaction);
            if (inserted != stack.amount()) throw new AssertionError("Only " + inserted + " of " + stack + " fit");
            transaction.commit();
        }
    }
}
