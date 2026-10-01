package fr.robie.machineengine.test;

import fr.robie.machineengine.api.addon.AddonId;
import fr.robie.machineengine.api.capability.Capabilities;
import fr.robie.machineengine.api.data.DataMap;
import fr.robie.machineengine.api.event.EventPhase;
import fr.robie.machineengine.api.machine.MachineEvents;
import fr.robie.machineengine.api.machine.MachineInspection;
import fr.robie.machineengine.api.machine.SchedulerState;
import fr.robie.machineengine.api.machine.WakeReason;
import fr.robie.machineengine.api.process.ProcessStatus;
import fr.robie.machineengine.api.resource.ResourceStack;
import fr.robie.machineengine.api.resource.ResourceType;
import fr.robie.machineengine.api.transfer.ResourceHandler;
import fr.robie.machineengine.api.transfer.StorageView;
import fr.robie.machineengine.api.transfer.Transaction;
import fr.robie.machineengine.api.world.Direction;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.core.lifecycle.CoreMachineEngine;
import fr.robie.machineengine.core.machine.LoadedMachine;
import fr.robie.machineengine.core.machine.MachineInstanceImpl;
import fr.robie.machineengine.core.machine.PlaceholderMachine;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static fr.robie.machineengine.test.MachineFixtures.B;
import static fr.robie.machineengine.test.MachineFixtures.CONSUMER;
import static fr.robie.machineengine.test.MachineFixtures.GENERATOR;
import static fr.robie.machineengine.test.MachineFixtures.MATTER;
import static fr.robie.machineengine.test.MachineFixtures.SLOW_CONSUMER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The first integration test (ARCHITECTURE.md §36): two addons interact through MachineEngine only.
 * Addon alpha's generator makes {@code alpha:b} and pushes it into addon beta's consumer, which eats it.
 */
class MachineIntegrationTest {
    private static final WorldPosition GENERATOR_AT = FakeWorld.at(0, 64, 0);
    private static final WorldPosition CONSUMER_AT = FakeWorld.at(1, 64, 0);

    @Test
    void aGeneratorFeedsAConsumerFromAnotherAddon() {
        CoreMachineEngine engine = MachineFixtures.bothAddons();
        FakeWorld world = FakeWorld.singleRegion(engine);
        Ledger ledger = new Ledger(engine);
        world.place(GENERATOR, GENERATOR_AT, Direction.EAST);
        world.place(CONSUMER, CONSUMER_AT, Direction.EAST);

        for (int i = 0; i < 300; i++) {
            world.tick();
            ledger.assertConserved(world);
        }
        assertTrue(ledger.produced >= 100, "the generator produced: " + ledger.produced);
        assertTrue(ledger.destroyed >= 100, "the consumer consumed: " + ledger.destroyed);
        assertNull(world.machine(CONSUMER_AT).lastError());
    }

    @Test
    void theConsumerSleepsWhenEmptyAndWakesOnInsert() {
        FakeWorld world = FakeWorld.singleRegion(MachineFixtures.bothAddons());
        MachineInstanceImpl consumer = world.place(CONSUMER, CONSUMER_AT, Direction.EAST);
        world.tick(2);
        assertEquals(SchedulerState.WAITING, consumer.schedulerState());

        ResourceHandler port = consumer.capability(Capabilities.RESOURCE_HANDLER, Direction.WEST).orElseThrow();
        try (Transaction transaction = world.engine().transactions().openOuter()) {
            assertEquals(5, port.insert(B.variant(), 5, transaction));
            transaction.commit();
        }
        assertEquals(SchedulerState.ACTIVE, consumer.schedulerState());
        assertEquals(WakeReason.RESOURCE_INSERTED, consumer.lastWakeReason());

        world.tick();
        assertEquals(SchedulerState.SCHEDULED, consumer.schedulerState(), "a running process without costs sleeps until done");
        assertEquals(ProcessStatus.RUNNING, consumer.inspect().process().status());
        world.tick(10);
        assertEquals(SchedulerState.WAITING, consumer.schedulerState(), "done, and nothing left to eat");
    }

    @Test
    void theGeneratorBlocksWhenTheConsumerIsFull() {
        FakeWorld world = FakeWorld.singleRegion(MachineFixtures.bothAddons());
        MachineInstanceImpl generator = world.place(GENERATOR, GENERATOR_AT, Direction.EAST);
        world.place(SLOW_CONSUMER, CONSUMER_AT, Direction.EAST);

        world.tick(400);
        assertEquals(SchedulerState.BLOCKED, generator.schedulerState());
        assertEquals(ProcessStatus.OUTPUT_BLOCKED, generator.inspect().process().status());
        assertEquals(35, amount(generator.inspect(), "output"), "its own output cannot take another 10 (capacity 40)");
        assertEquals(20, amount(world.inspect(CONSUMER_AT), "input"), "the consumer's input is full");
        assertEquals(5, world.inspect(CONSUMER_AT).process().inFlight().getFirst().amount(), "and 5 are in its slow process");
    }

    @Test
    void portsCheckRuntimeIdentityAndSides() {
        FakeWorld world = FakeWorld.singleRegion(MachineFixtures.bothAddons());
        MachineInstanceImpl consumer = world.place(CONSUMER, CONSUMER_AT, Direction.EAST);
        ResourceType lookAlike = ResourceType.of(Key.key("alpha", "b"), MATTER);

        ResourceHandler port = consumer.capability(Capabilities.RESOURCE_HANDLER, Direction.WEST).orElseThrow();
        try (Transaction transaction = world.engine().transactions().openOuter()) {
            assertEquals(0, port.insert(lookAlike.variant(), 5, transaction), "same key, different (unregistered) type");
            assertEquals(5, port.insert(B.variant(), 5, transaction));
            assertEquals(0, port.extract(B.variant(), 5, transaction), "input-only port");
        }
        assertTrue(consumer.capability(Capabilities.RESOURCE_HANDLER, Direction.EAST).isEmpty(), "nothing on its front");
        assertTrue(consumer.capability(Capabilities.RESOURCE_HANDLER, null).isEmpty());
    }

    @Test
    void stateSurvivesSaveAndLoadAndRunsOnIdentically() {
        CoreMachineEngine engine = MachineFixtures.bothAddons();
        FakeWorld original = FakeWorld.singleRegion(engine);
        original.place(GENERATOR, GENERATOR_AT, Direction.EAST);
        original.place(CONSUMER, CONSUMER_AT, Direction.EAST);
        original.tick(57);
        Map<WorldPosition, DataMap> saved = original.save();
        assertNotNull(original.inspect(CONSUMER_AT).process(), "saved mid-process, with inputs in flight");

        FakeWorld restored = FakeWorld.singleRegion(engine);
        restored.load(saved);
        assertEquals(persistent(original), persistent(restored));

        original.tick(200);
        restored.tick(200);
        assertEquals(persistent(original), persistent(restored), "simulation is deterministic across a reload (§19)");
    }

    @Test
    void crossRegionTransfersUseHandoffAndConserveResources() {
        CoreMachineEngine engine = MachineFixtures.bothAddons();
        FakeWorld world = FakeWorld.regions(engine, position -> position.x() <= 0 ? "west" : "east");
        Ledger ledger = new Ledger(engine);
        MachineInstanceImpl generator = world.place(GENERATOR, GENERATOR_AT, Direction.EAST);
        world.place(CONSUMER, CONSUMER_AT, Direction.EAST);

        boolean sawEscrow = false;
        for (int i = 0; i < 300; i++) {
            world.tick();
            ledger.assertConserved(world);
            sawEscrow |= !generator.inspect().escrow().isEmpty();
        }
        assertTrue(sawEscrow, "transfers went through escrow");
        assertTrue(ledger.destroyed >= 100, "the consumer consumed: " + ledger.destroyed);
    }

    @Test
    void aMissingAddonLeavesPlaceholdersThatRehydrate() {
        FakeWorld original = FakeWorld.singleRegion(MachineFixtures.bothAddons());
        original.place(GENERATOR, GENERATOR_AT, Direction.EAST);
        original.place(CONSUMER, CONSUMER_AT, Direction.EAST);
        original.tick(37);
        Map<WorldPosition, DataMap> saved = original.save();
        MachineInspection consumerBefore = original.inspect(CONSUMER_AT);

        // beta is uninstalled: its consumer becomes a placeholder, and nothing is lost.
        FakeWorld withoutBeta = FakeWorld.singleRegion(MachineFixtures.alphaOnly());
        withoutBeta.load(saved);
        LoadedMachine placeholder = withoutBeta.loaded(CONSUMER_AT);
        assertInstanceOf(PlaceholderMachine.class, placeholder);
        assertTrue(placeholder.inspect().placeholder());
        assertEquals(SchedulerState.DISABLED, placeholder.inspect().state());
        withoutBeta.tick(200);
        assertEquals(SchedulerState.BLOCKED, withoutBeta.state(GENERATOR_AT), "nothing accepts its output any more");
        Map<WorldPosition, DataMap> savedWithoutBeta = withoutBeta.save();
        assertEquals(saved.get(CONSUMER_AT), savedWithoutBeta.get(CONSUMER_AT), "the placeholder writes its data back unchanged");

        // beta returns: the consumer comes back exactly as it was.
        FakeWorld withBeta = FakeWorld.singleRegion(MachineFixtures.bothAddons());
        withBeta.load(savedWithoutBeta);
        MachineInspection consumerAfter = withBeta.inspect(CONSUMER_AT);
        assertFalse(consumerAfter.placeholder());
        assertEquals(consumerBefore.storages(), consumerAfter.storages());
        assertEquals(consumerBefore.process(), consumerAfter.process());
    }

    // ------------------------------------------------------------------------------------------

    private static long amount(MachineInspection inspection, String storage) {
        return inspection.storages().get(storage).stream().mapToLong(StorageView::amount).sum();
    }

    /** The persisted part of every machine's inspection (wake reasons and errors are not persisted). */
    private static Map<WorldPosition, Object> persistent(FakeWorld world) {
        Map<WorldPosition, Object> state = new LinkedHashMap<>();
        world.machines().forEach((position, machine) -> {
            MachineInspection inspection = machine.inspect();
            state.put(position, java.util.List.of(inspection.type(), inspection.facing(), inspection.state(),
                    inspection.storages(), String.valueOf(inspection.process()), inspection.upgrades(), inspection.escrow()));
        });
        return state;
    }

    /**
     * Counts {@code alpha:b} created by completed processes and destroyed by consumed inputs, to check
     * that everything else (storages, in-flight buffers, escrow) always adds up.
     */
    static final class Ledger {
        long produced;
        long destroyed;

        Ledger(CoreMachineEngine engine) {
            engine.events().subscribe(new AddonId("test"), MachineEvents.PROCESS_COMPLETE, EventPhase.POST, event -> {
                this.produced += sumB(event.process().outputs());
                this.destroyed += sumB(event.process().inFlight());
            });
        }

        void assertConserved(FakeWorld world) {
            assertEquals(this.produced - this.destroyed, held(world), "alpha:b held in the world");
        }

        static long held(FakeWorld world) {
            long held = 0;
            for (LoadedMachine machine : world.machines().values()) {
                MachineInspection inspection = machine.inspect();
                for (var views : inspection.storages().values()) {
                    for (StorageView view : views) {
                        if (!view.isEmpty() && view.variant().type() == B) held += view.amount();
                    }
                }
                if (inspection.process() != null) held += sumB(inspection.process().inFlight());
                held += sumB(inspection.escrow());
            }
            return held;
        }

        private static long sumB(java.util.List<ResourceStack> stacks) {
            return stacks.stream().filter(stack -> stack.type() == B).mapToLong(ResourceStack::amount).sum();
        }
    }
}
