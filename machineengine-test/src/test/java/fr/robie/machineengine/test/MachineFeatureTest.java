package fr.robie.machineengine.test;

import fr.robie.machineengine.api.addon.AddonRegistration;
import fr.robie.machineengine.api.capability.Capabilities;
import fr.robie.machineengine.api.data.Codecs;
import fr.robie.machineengine.api.data.DataMap;
import fr.robie.machineengine.api.data.VersionedCodec;
import fr.robie.machineengine.api.diagnostic.DiagnosticCodes;
import fr.robie.machineengine.api.machine.MachineBehavior;
import fr.robie.machineengine.api.machine.MachineContext;
import fr.robie.machineengine.api.machine.MachineDataKey;
import fr.robie.machineengine.api.machine.MachineInspection;
import fr.robie.machineengine.api.machine.MachineType;
import fr.robie.machineengine.api.machine.PortDefinition;
import fr.robie.machineengine.api.machine.PortDirection;
import fr.robie.machineengine.api.machine.ProcessingBehavior;
import fr.robie.machineengine.api.machine.SchedulerState;
import fr.robie.machineengine.api.machine.StorageDefinition;
import fr.robie.machineengine.api.process.ProcessBinding;
import fr.robie.machineengine.api.process.ProcessStatus;
import fr.robie.machineengine.api.recipe.ProcessingRecipe;
import fr.robie.machineengine.api.recipe.RecipeType;
import fr.robie.machineengine.api.resource.ResourceCategory;
import fr.robie.machineengine.api.resource.ResourceStack;
import fr.robie.machineengine.api.resource.ResourceType;
import fr.robie.machineengine.api.resource.match.ResourceMatcher;
import fr.robie.machineengine.api.resource.match.ResourcePredicate;
import fr.robie.machineengine.api.transfer.ResourceHandler;
import fr.robie.machineengine.api.transfer.StorageView;
import fr.robie.machineengine.api.transfer.Transaction;
import fr.robie.machineengine.api.upgrade.Modifier;
import fr.robie.machineengine.api.upgrade.StandardModifiers;
import fr.robie.machineengine.api.upgrade.UpgradeType;
import fr.robie.machineengine.api.world.Direction;
import fr.robie.machineengine.api.world.RelativeSide;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.core.lifecycle.CoreMachineEngine;
import fr.robie.machineengine.core.machine.MachineInstanceImpl;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static fr.robie.machineengine.test.MachineFixtures.A;
import static fr.robie.machineengine.test.MachineFixtures.B;
import static fr.robie.machineengine.test.MachineFixtures.CHEST;
import static fr.robie.machineengine.test.MachineFixtures.CONSUMER;
import static fr.robie.machineengine.test.MachineFixtures.GENERATOR;
import static fr.robie.machineengine.test.MachineFixtures.SPEED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MachineFeatureTest {
    private static final WorldPosition HERE = FakeWorld.at(0, 64, 0);

    // -- missing resource types (D3) -------------------------------------------------------------

    private static final ResourceCategory PLASMA_CATEGORY = ResourceCategory.builder(Key.key("gamma", "plasma")).build();
    private static final ResourceType PLASMA = ResourceType.of(Key.key("gamma", "hot_plasma"), PLASMA_CATEGORY);

    private static AddonRegistration gamma() {
        return AddonRegistration.builder("gamma", "1.0.0").register(ctx -> {
            ctx.resourceCategories().register(PLASMA_CATEGORY);
            ctx.resourceTypes().register(PLASMA);
        }).build();
    }

    @Test
    void aMissingResourceTypeLocksItsSlotAndRehydrates() {
        CoreMachineEngine full = TestEngine.builder().addon(MachineFixtures.alpha()).addon(MachineFixtures.beta()).addon(gamma()).start();
        FakeWorld original = FakeWorld.singleRegion(full);
        MachineInstanceImpl chest = original.place(CHEST, HERE, Direction.NORTH);
        MachineFixtures.fill(chest, "items", PLASMA.stack(300));
        DataMap saved = original.save(HERE);

        FakeWorld withoutGamma = FakeWorld.singleRegion(MachineFixtures.bothAddons());
        withoutGamma.load(HERE, saved);
        MachineInstanceImpl reloaded = withoutGamma.machine(HERE);
        assertEquals(Map.of("items", 1), reloaded.inspect().lockedSlots());
        MachineFixtures.fill(reloaded, "items", B.stack(2000)); // the three other slots still work
        assertEquals(2000, amount(reloaded.inspect(), "items"));

        FakeWorld withGamma = FakeWorld.singleRegion(full);
        withGamma.load(HERE, withoutGamma.save(HERE));
        MachineInspection rehydrated = withGamma.inspect(HERE);
        assertTrue(rehydrated.lockedSlots().isEmpty());
        assertEquals(new StorageView(PLASMA.variant(), 300, 1000), rehydrated.storages().get("items").getFirst());
    }

    // -- undeclared data -------------------------------------------------------------------------

    private static final MachineDataKey<Long> COUNTER = MachineDataKey.of("counter",
            VersionedCodec.builder(Key.key("delta", "counter"), Codecs.LONG).build(), 0L);

    private static CoreMachineEngine deltaEngine(boolean withCounter) {
        MachineType.Builder type = MachineType.builder(Key.key("delta", "counter_box"))
                .storage(StorageDefinition.tank("tank", 10))
                .behavior(t -> new MachineBehavior() {
                    @Override
                    public void tick(@NotNull MachineContext context) {
                        if (withCounter) context.set(COUNTER, context.get(COUNTER) + 1);
                        context.sleep();
                    }
                });
        if (withCounter) type.data(COUNTER);
        MachineType built = type.build();
        return TestEngine.builder().addon("delta", ctx -> ctx.machineTypes().register(built)).start();
    }

    @Test
    void dataTheTypeNoLongerDeclaresIsKeptVerbatim() {
        FakeWorld v1 = FakeWorld.singleRegion(deltaEngine(true));
        MachineType type = v1.engine().registries().registry(fr.robie.machineengine.api.registry.Registries.MACHINE_TYPE)
                .getOrThrow(Key.key("delta", "counter_box"));
        v1.place(type, HERE, Direction.NORTH);
        v1.tick();
        DataMap saved = v1.save(HERE);

        FakeWorld v2 = FakeWorld.singleRegion(deltaEngine(false));
        v2.load(HERE, saved);
        assertEquals(List.of("counter"), v2.inspect(HERE).unknownData());

        FakeWorld back = FakeWorld.singleRegion(deltaEngine(true));
        back.load(HERE, v2.save(HERE));
        assertEquals(1L, back.machine(HERE).context().get(COUNTER));
    }

    // -- upgrades and modifiers (D16) ------------------------------------------------------------

    @Test
    void upgradesModifyProcessDurationAndAreLimited() {
        CoreMachineEngine engine = MachineFixtures.bothAddons();
        FakeWorld world = FakeWorld.singleRegion(engine);
        MachineInstanceImpl generator = world.place(GENERATOR, HERE, Direction.NORTH);
        assertTrue(generator.installUpgrade(SPEED, 2).isOk());
        world.tick();
        assertEquals(10, generator.inspect().process().duration(), "20 ticks × (1 − 2 × 25 %)");

        assertTrue(generator.installUpgrade(SPEED, 3).isErr(), "max stack is 4");
        UpgradeType unregistered = UpgradeType.of(Key.key("alpha", "ghost"), 1);
        assertTrue(generator.installUpgrade(unregistered, 1).isErr());
        assertEquals(2, generator.removeUpgrade(SPEED, 5));
        assertTrue(generator.upgrades().isEmpty());
    }

    // -- ports -----------------------------------------------------------------------------------

    private static final UpgradeType FAST_PORTS = UpgradeType.of(Key.key("epsilon", "fast_ports"), 1,
            Modifier.addMultipliedBase(StandardModifiers.TRANSFER_RATE, 1000));
    private static final MachineType BUFFER = MachineType.builder(Key.key("epsilon", "buffer"))
            .storage(StorageDefinition.tank("tank", 1000))
            .port(PortDefinition.builder("in", "tank").direction(PortDirection.INPUT).sides(RelativeSide.TOP).ratePerTick(5).build())
            .upgradeSlots(1, upgrade -> true)
            .behavior(t -> MachineContext::sleep)
            .build();

    @Test
    void portRatesResetEachTickIgnoreAbortsAndFollowUpgrades() {
        CoreMachineEngine engine = TestEngine.builder()
                .addon(MachineFixtures.alpha())
                .addon("epsilon", ctx -> {
                    ctx.upgradeTypes().register(FAST_PORTS);
                    ctx.machineTypes().register(BUFFER);
                })
                .start();
        FakeWorld world = FakeWorld.singleRegion(engine);
        MachineInstanceImpl buffer = world.place(BUFFER, HERE, Direction.NORTH);
        ResourceHandler port = buffer.capability(Capabilities.RESOURCE_HANDLER, Direction.UP).orElseThrow();

        try (Transaction aborted = engine.transactions().openOuter()) {
            assertEquals(5, port.insert(B.variant(), 10, aborted));
        }
        assertEquals(5, insert(engine, port, 10), "an aborted transfer does not use up the rate");
        assertEquals(0, insert(engine, port, 10));
        world.tick();
        assertEquals(5, insert(engine, port, 10), "the rate resets every tick");
        assertTrue(buffer.installUpgrade(FAST_PORTS, 1).isOk());
        world.tick();
        assertEquals(10, insert(engine, port, 20), "+100 % transfer rate");
    }

    // -- processes -------------------------------------------------------------------------------

    private static final RecipeType<ProcessingRecipe> SMELTING = RecipeType.of(Key.key("zeta", "smelting"), ProcessingRecipe.class);
    private static final ProcessingRecipe SMELT = ProcessingRecipe.builder(Key.key("zeta", "smelt_a"), SMELTING)
            .input(ResourceMatcher.of(ResourcePredicate.type(A), 2))
            .output(B.stack(1))
            .costPerTick(A.stack(1))
            .duration(4)
            .build();
    private static final MachineType FURNACE = MachineType.builder(Key.key("zeta", "furnace"))
            .storage(StorageDefinition.tank("input", 100))
            .storage(StorageDefinition.tank("fuel", 100))
            .storage(StorageDefinition.tank("output", 100))
            .behavior(ProcessingBehavior.of(SMELTING, ProcessBinding.of("input", "output").withCosts("fuel")))
            .build();

    private static CoreMachineEngine zeta() {
        return TestEngine.builder()
                .addon(MachineFixtures.alpha())
                .addon("zeta", "1.0.0", builder -> builder.requires("alpha", "1.0").register(ctx -> {
                    ctx.recipeTypes().register(SMELTING);
                    ctx.machineTypes().register(FURNACE);
                    ctx.recipes(reload -> reload.register(SMELT));
                }))
                .start();
    }

    @Test
    void aProcessPausesWhileItsCostsCannotBePaid() {
        FakeWorld world = FakeWorld.singleRegion(zeta());
        MachineInstanceImpl furnace = world.place(FURNACE, HERE, Direction.NORTH);
        MachineFixtures.fill(furnace, "input", A.stack(2));
        MachineFixtures.fill(furnace, "fuel", A.stack(2));

        world.tick(3);
        assertEquals(ProcessStatus.WAITING_FOR_COST, furnace.inspect().process().status());
        assertEquals(2, furnace.inspect().process().progress());
        assertEquals(SchedulerState.WAITING, furnace.schedulerState());

        MachineFixtures.fill(furnace, "fuel", A.stack(10));
        world.tick(3);
        assertNull(furnace.inspect().process());
        assertEquals(1, amount(furnace.inspect(), "output"));
        assertEquals(8, amount(furnace.inspect(), "fuel"), "4 ticks × 1 fuel");
    }

    @Test
    void cancellingReturnsTheInputs() {
        FakeWorld world = FakeWorld.singleRegion(MachineFixtures.bothAddons());
        MachineInstanceImpl consumer = world.place(CONSUMER, HERE, Direction.NORTH);
        MachineFixtures.fill(consumer, "input", B.stack(20));
        world.tick();
        assertEquals(15, amount(consumer.inspect(), "input"));
        assertEquals(ProcessStatus.IDLE, consumer.context().process().cancel());
        assertNull(consumer.inspect().process());
        assertEquals(20, amount(consumer.inspect(), "input"));
    }

    @Test
    void removingAMachineDropsItsContentsAndInFlightInputs() {
        FakeWorld world = FakeWorld.singleRegion(MachineFixtures.bothAddons());
        MachineInstanceImpl consumer = world.place(CONSUMER, HERE, Direction.NORTH);
        MachineFixtures.fill(consumer, "input", B.stack(12));
        world.tick();
        List<ResourceStack> drops = world.remove(HERE);
        assertEquals(12, drops.stream().mapToLong(ResourceStack::amount).sum());
        assertNull(world.loaded(HERE));
    }

    @Test
    void neighborsSeeEachOthersPortsFacingBack() {
        FakeWorld world = FakeWorld.singleRegion(MachineFixtures.bothAddons());
        MachineInstanceImpl generator = world.place(GENERATOR, HERE, Direction.EAST);
        world.place(CONSUMER, FakeWorld.at(1, 64, 0), Direction.EAST);
        assertTrue(generator.context().neighbor(RelativeSide.FRONT, Capabilities.RESOURCE_HANDLER).isPresent());
        assertTrue(generator.context().neighbor(RelativeSide.BACK, Capabilities.RESOURCE_HANDLER).isEmpty());
    }

    @Test
    void aTimerThatFiresEarlyIsRescheduledNotLost() {
        // Regression: Paper's region scheduler ran timers one tick before the world clock reached them.
        FakeWorld world = FakeWorld.singleRegion(MachineFixtures.bothAddons());
        MachineInstanceImpl consumer = world.place(CONSUMER, HERE, Direction.NORTH);
        MachineFixtures.fill(consumer, "input", B.stack(5));
        world.tick();
        assertEquals(SchedulerState.SCHEDULED, consumer.schedulerState());
        consumer.timerFired(world.time() + 1); // early
        assertEquals(SchedulerState.SCHEDULED, consumer.schedulerState());
        world.tick(12);
        assertNull(consumer.inspect().process(), "the process still completed");
    }

    // -- validation ------------------------------------------------------------------------------

    @Test
    void invalidMachineTypesDisableTheirAddon() {
        MachineType broken = MachineType.builder(Key.key("broken", "machine"))
                .storage(StorageDefinition.tank("tank", 10))
                .storage(StorageDefinition.tank("tank", 0))
                .port(PortDefinition.builder("p", "nowhere").build())
                .behavior(t -> MachineContext::sleep)
                .build();
        CoreMachineEngine engine = TestEngine.builder().addon("broken", ctx -> ctx.machineTypes().register(broken)).start();
        long errors = engine.startupReport().errors().stream()
                .filter(d -> d.code().equals(DiagnosticCodes.INVALID_DEFINITION)).count();
        assertEquals(3, errors, engine.startupReport().format());
        assertTrue(engine.enabledAddons().isEmpty());
    }

    @Test
    void invalidRecipesAreLeftOut() {
        ResourceType unregistered = ResourceType.of(Key.key("zeta", "ghost"), MachineFixtures.MATTER);
        CoreMachineEngine engine = TestEngine.builder()
                .addon(MachineFixtures.alpha())
                .addon("zeta", "1.0.0", builder -> builder.requires("alpha", "1.0").register(ctx -> {
                    ctx.recipeTypes().register(SMELTING);
                    ctx.recipes(reload -> reload.register(ProcessingRecipe.builder(Key.key("zeta", "bad"), SMELTING)
                            .output(unregistered.stack(1)).build()));
                }))
                .start();
        LifecycleTest.assertHasError(engine, DiagnosticCodes.UNREGISTERED_REFERENCE, "zeta");
        assertTrue(engine.machines().recipes().all(SMELTING).isEmpty());
        assertNotNull(engine.machines().recipes().all(MachineFixtures.GENERATING).getFirst());
    }

    // ------------------------------------------------------------------------------------------

    private static long insert(CoreMachineEngine engine, ResourceHandler handler, long amount) {
        try (Transaction transaction = engine.transactions().openOuter()) {
            long inserted = handler.insert(B.variant(), amount, transaction);
            transaction.commit();
            return inserted;
        }
    }

    private static long amount(MachineInspection inspection, String storage) {
        return inspection.storages().get(storage).stream().mapToLong(StorageView::amount).sum();
    }
}
