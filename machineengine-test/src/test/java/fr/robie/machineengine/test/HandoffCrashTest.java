package fr.robie.machineengine.test;

import fr.robie.machineengine.api.data.DataMap;
import fr.robie.machineengine.api.machine.MachineInspection;
import fr.robie.machineengine.api.resource.ResourceStack;
import fr.robie.machineengine.api.transfer.StorageView;
import fr.robie.machineengine.api.world.Direction;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.core.lifecycle.CoreMachineEngine;
import fr.robie.machineengine.core.machine.LoadedMachine;
import fr.robie.machineengine.core.machine.MachineInstanceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static fr.robie.machineengine.test.MachineFixtures.B;
import static fr.robie.machineengine.test.MachineFixtures.CHEST;
import static fr.robie.machineengine.test.MachineFixtures.PUSHER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The handoff protocol under injected crashes (§16.3). A pusher in region "west" sends 30 alpha:b to
 * a chest in region "east", 10 at a time. Saves are taken between the protocol steps, and the world is
 * restored from mixed saves, the way a crash leaves chunks saved at different moments.
 */
class HandoffCrashTest {
    private static final WorldPosition PUSHER_AT = FakeWorld.at(0, 64, 0);
    private static final WorldPosition CHEST_AT = FakeWorld.at(1, 64, 0);
    private static final long TOTAL = 30;

    private final CoreMachineEngine engine = MachineFixtures.bothAddons();
    private FakeWorld world;
    /** Escrow committed at the source; delivery not processed yet. */
    private Map<WorldPosition, DataMap> afterSend;
    /** Target inserted and recorded the transfer id; acknowledgement not processed yet. */
    private Map<WorldPosition, DataMap> afterReceive;
    /** Source settled the acknowledgement (and started the next transfer). */
    private Map<WorldPosition, DataMap> afterAcknowledge;

    @BeforeEach
    void runTheProtocolStepByStep() {
        this.world = newWorld();
        MachineInstanceImpl pusher = this.world.place(PUSHER, PUSHER_AT, Direction.EAST);
        this.world.place(CHEST, CHEST_AT, Direction.EAST);
        MachineFixtures.fill(pusher, "output", B.stack(TOTAL));

        this.world.advance();
        this.world.step("west");
        assertEquals(10, escrowed(this.world), "the first 10 are in escrow");
        this.afterSend = this.world.save();

        this.world.step("east");
        assertEquals(10, chestAmount(this.world), "the chest took them");
        this.afterReceive = this.world.save();

        this.world.advance();
        this.world.step("west");
        this.afterAcknowledge = this.world.save();
        assertEquals(10, chestAmount(this.world));
        assertEquals(10, escrowed(this.world), "the escrow now holds the second transfer");
    }

    @Test
    void withoutACrashEverythingArrivesOnce() {
        settle(this.world);
        assertEquals(TOTAL, chestAmount(this.world));
        assertEquals(TOTAL, total(this.world));
    }

    @Test
    void crashAfterSendingResendsTheSameTransfer() {
        FakeWorld restored = restore(this.afterSend.get(PUSHER_AT), this.afterSend.get(CHEST_AT));
        settle(restored);
        assertEquals(TOTAL, chestAmount(restored));
    }

    @Test
    void crashAfterReceivingDoesNotDuplicate() {
        // The chest saved the insert and the transfer id; the pusher still has the escrow and re-sends it.
        FakeWorld restored = restore(this.afterReceive.get(PUSHER_AT), this.afterReceive.get(CHEST_AT));
        settle(restored);
        assertEquals(TOTAL, chestAmount(restored), "the re-delivery is answered from the dedupe record");
    }

    @Test
    void crashBeforeTheTargetSavedItsInsertDeliversAgain() {
        FakeWorld restored = restore(this.afterReceive.get(PUSHER_AT), this.afterSend.get(CHEST_AT));
        settle(restored);
        assertEquals(TOTAL, chestAmount(restored));
    }

    @Test
    void theDocumentedLossWindow() {
        // The pusher saved after settling the first transfer, but the chest's last save predates the
        // insert: the first 10 are gone. This is the one window §16.3 documents; nothing else is lost.
        FakeWorld restored = restore(this.afterAcknowledge.get(PUSHER_AT), this.afterSend.get(CHEST_AT));
        settle(restored);
        assertEquals(TOTAL - 10, chestAmount(restored));
        assertEquals(TOTAL - 10, total(restored));
    }

    @Test
    void unloadingTheTargetReturnsTheResourcesAndBacksOff() {
        this.world.unload(CHEST_AT);
        this.world.tick(3);
        MachineInspection pusher = this.world.inspect(PUSHER_AT);
        assertTrue(pusher.escrow().isEmpty(), "answered with nothing accepted, so the escrow is returned");
        assertEquals(TOTAL - 10, amount(pusher, "output"), "the 10 delivered before the unload stay in the chest's save");

        this.world.tick(10);
        assertEquals(0, this.world.pendingTasks(), "the route rests instead of re-sending every tick");
        assertEquals(0, escrowed(this.world));
    }

    @Test
    void removingTheSourceWithAHandoffInFlightDropsTheRemainder() {
        FakeWorld fresh = newWorld();
        MachineInstanceImpl pusher = fresh.place(PUSHER, PUSHER_AT, Direction.EAST);
        MachineFixtures.fill(pusher, "output", B.stack(TOTAL));
        fresh.advance();
        fresh.step("west");                       // 10 in escrow, delivery queued; no chest at the target
        fresh.remove(PUSHER_AT).forEach(stack -> assertEquals(B, stack.type()));
        fresh.tick(5);
        assertEquals(0, fresh.pendingTasks());
        assertEquals(10, fresh.dropped().stream().mapToLong(ResourceStack::amount).sum(),
                "the unaccepted handoff is dropped into the world instead of vanishing");
    }

    // ------------------------------------------------------------------------------------------

    private FakeWorld newWorld() {
        return FakeWorld.regions(this.engine, position -> position.x() <= 0 ? "west" : "east");
    }

    private FakeWorld restore(DataMap pusher, DataMap chest) {
        FakeWorld restored = newWorld();
        restored.load(PUSHER_AT, pusher);
        restored.load(CHEST_AT, chest);
        return restored;
    }

    private static void settle(FakeWorld world) {
        world.tick(100);
        assertEquals(0, world.pendingTasks(), "no message left in flight");
        assertEquals(0, escrowed(world), "no escrow left");
    }

    private static long chestAmount(FakeWorld world) {
        LoadedMachine chest = world.loaded(CHEST_AT);
        return chest == null ? 0 : amount(chest.inspect(), "items");
    }

    private static long escrowed(FakeWorld world) {
        return world.inspect(PUSHER_AT).escrow().stream().mapToLong(ResourceStack::amount).sum();
    }

    private static long total(FakeWorld world) {
        return chestAmount(world) + amount(world.inspect(PUSHER_AT), "output") + escrowed(world);
    }

    private static long amount(MachineInspection inspection, String storage) {
        return inspection.storages().get(storage).stream().mapToLong(StorageView::amount).sum();
    }
}
