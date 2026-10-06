package fr.robie.machineengine.test;

import fr.robie.machineengine.api.machine.MachineType;
import fr.robie.machineengine.api.machine.ProcessingBehavior;
import fr.robie.machineengine.api.machine.StorageDefinition;
import fr.robie.machineengine.api.process.ProcessBinding;
import fr.robie.machineengine.api.recipe.ProcessingRecipe;
import fr.robie.machineengine.api.recipe.RecipeType;
import fr.robie.machineengine.api.resource.ResourceCategory;
import fr.robie.machineengine.api.resource.ResourceType;
import fr.robie.machineengine.api.resource.match.ResourceMatcher;
import fr.robie.machineengine.api.resource.match.ResourcePredicate;
import fr.robie.machineengine.api.transfer.StorageView;
import fr.robie.machineengine.api.world.Direction;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.core.lifecycle.CoreMachineEngine;
import fr.robie.machineengine.core.machine.MachineInstanceImpl;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Process features Mekanism-style machines need: costs from several storages (energy and a chemical),
 * chance outputs (E1), and the visible "active" state.
 */
class ProcessFeaturesTest {
    private static final WorldPosition HERE = FakeWorld.at(0, 64, 0);
    private static final ResourceCategory STUFF = ResourceCategory.builder(Key.key("omega", "stuff")).build();
    private static final ResourceType ORE = ResourceType.of(Key.key("omega", "ore"), STUFF);
    private static final ResourceType DUST = ResourceType.of(Key.key("omega", "dust"), STUFF);
    private static final ResourceType SAWDUST = ResourceType.of(Key.key("omega", "sawdust"), STUFF);
    private static final ResourceType ENERGY = ResourceType.of(Key.key("omega", "energy"), STUFF);
    private static final ResourceType OXYGEN = ResourceType.of(Key.key("omega", "oxygen"), STUFF);
    private static final RecipeType<ProcessingRecipe> PURIFYING = RecipeType.of(Key.key("omega", "purifying"), ProcessingRecipe.class);

    /** Like a purification chamber: 50 energy and 1 oxygen per tick, from two different tanks. */
    private static final ProcessingRecipe PURIFY = ProcessingRecipe.builder(Key.key("omega", "purify"), PURIFYING)
            .input(ResourceMatcher.of(ResourcePredicate.type(ORE), 1))
            .output(DUST.stack(3))
            .chanceOutput(SAWDUST.stack(1), 0.25)
            .costPerTick(ENERGY.stack(50))
            .costPerTick(OXYGEN.stack(1))
            .duration(10)
            .build();
    private static final MachineType CHAMBER = MachineType.builder(Key.key("omega", "chamber"))
            .storage(StorageDefinition.tank("input", 10_000))
            .storage(StorageDefinition.tank("energy", 1_000_000))
            .storage(StorageDefinition.tank("gas", 10_000))
            .storage(StorageDefinition.slots("output", 2, 100_000))
            .behavior(ProcessingBehavior.of(PURIFYING, ProcessBinding.of("input", "output").withCosts("energy", "gas")))
            .build();

    private final CoreMachineEngine engine = TestEngine.builder()
            .addon("omega", ctx -> {
                ctx.resourceCategories().register(STUFF);
                for (ResourceType type : new ResourceType[] {ORE, DUST, SAWDUST, ENERGY, OXYGEN}) ctx.resourceTypes().register(type);
                ctx.recipeTypes().register(PURIFYING);
                ctx.machineTypes().register(CHAMBER);
                ctx.recipes(reload -> reload.register(PURIFY));
            })
            .start();

    @Test
    void costsArePaidFromSeveralStoragesAndPauseWhenOneRunsOut() {
        FakeWorld world = FakeWorld.singleRegion(this.engine);
        MachineInstanceImpl chamber = world.place(CHAMBER, HERE, Direction.NORTH);
        MachineFixtures.fill(chamber, "input", ORE.stack(1));
        MachineFixtures.fill(chamber, "energy", ENERGY.stack(10_000));
        MachineFixtures.fill(chamber, "gas", OXYGEN.stack(4));

        world.tick(6);
        assertEquals(4, chamber.inspect().process().progress(), "paused after 4 ticks: out of oxygen");
        assertEquals(10_000 - 4 * 50, amount(chamber, "energy"), "energy for exactly the ticks that ran");
        assertFalse(chamber.isActive(), "not working while waiting for oxygen");

        MachineFixtures.fill(chamber, "gas", OXYGEN.stack(10));
        world.tick(8);
        assertEquals(3, amountOf(chamber, "output", DUST));
        assertEquals(10_000 - 10 * 50, amount(chamber, "energy"));
    }

    @Test
    void chanceOutputsComeAtTheirRateAndAreKeptWithTheProcess() {
        FakeWorld world = FakeWorld.singleRegion(this.engine);
        MachineInstanceImpl chamber = world.place(CHAMBER, HERE, Direction.NORTH);
        int runs = 400;
        MachineFixtures.fill(chamber, "input", ORE.stack(runs));
        MachineFixtures.fill(chamber, "energy", ENERGY.stack(runs * 10L * 50));
        MachineFixtures.fill(chamber, "gas", OXYGEN.stack(runs * 10L));
        world.tick(runs * 10 + 20);

        assertEquals(runs * 3L, amountOf(chamber, "output", DUST));
        long sawdust = amountOf(chamber, "output", SAWDUST);
        assertTrue(sawdust > runs * 0.15 && sawdust < runs * 0.35, "about 25 %: " + sawdust + " of " + runs);
    }

    @Test
    void theActiveStateFollowsTheWorkAndIsSaved() {
        FakeWorld world = FakeWorld.singleRegion(this.engine);
        MachineInstanceImpl chamber = world.place(CHAMBER, HERE, Direction.NORTH);
        assertFalse(chamber.isActive());
        MachineFixtures.fill(chamber, "input", ORE.stack(1));
        MachineFixtures.fill(chamber, "energy", ENERGY.stack(10_000));
        MachineFixtures.fill(chamber, "gas", OXYGEN.stack(100));
        world.tick(2);
        assertTrue(chamber.isActive(), "working");

        FakeWorld later = FakeWorld.singleRegion(this.engine);
        later.load(HERE, world.save(HERE));
        assertTrue(later.machine(HERE).isActive(), "saved: the block keeps its lit look after a restart");
        later.tick(20);
        assertFalse(later.machine(HERE).isActive(), "done and nothing left to process");
    }

    private static long amount(MachineInstanceImpl machine, String storage) {
        return machine.inspect().storages().get(storage).stream().mapToLong(StorageView::amount).sum();
    }

    private static long amountOf(MachineInstanceImpl machine, String storage, ResourceType type) {
        return machine.inspect().storages().get(storage).stream()
                .filter(view -> view.variant() != null && view.variant().type() == type).mapToLong(StorageView::amount).sum();
    }
}
