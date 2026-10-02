package fr.robie.machineengine.test;

import fr.robie.machineengine.api.capability.Capabilities;
import fr.robie.machineengine.api.machine.MachineContext;
import fr.robie.machineengine.api.machine.MachineType;
import fr.robie.machineengine.api.machine.PortDefinition;
import fr.robie.machineengine.api.machine.StorageDefinition;
import fr.robie.machineengine.api.transfer.ResourceHandler;
import fr.robie.machineengine.api.transfer.Transaction;
import fr.robie.machineengine.api.world.Direction;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.core.lifecycle.CoreMachineEngine;
import fr.robie.machineengine.core.machine.MachineInstanceImpl;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Comparator output: vanilla's container formula over every machine slot. */
class ComparatorTest {
    private static final WorldPosition HERE = FakeWorld.at(0, 64, 0);
    /** Two slots of 10: a comparator sees their average fill. */
    private static final MachineType BIN = MachineType.builder(Key.key("omega", "bin"))
            .storage(StorageDefinition.slots("items", 2, 10))
            .port(PortDefinition.builder("io", "items").build())
            .behavior(type -> MachineContext::sleep)
            .build();

    private final CoreMachineEngine engine = TestEngine.builder()
            .addon(MachineFixtures.alpha())
            .addon("omega", ctx -> ctx.machineTypes().register(BIN))
            .start();

    @Test
    void signalFollowsTheAverageFill() {
        FakeWorld world = FakeWorld.singleRegion(this.engine);
        MachineInstanceImpl bin = world.place(BIN, HERE, Direction.NORTH);
        assertEquals(0, bin.comparatorSignal(), "empty");
        insert(world, bin, 1);
        assertEquals(1, bin.comparatorSignal(), "anything at all gives at least 1");
        insert(world, bin, 9);
        assertEquals(8, bin.comparatorSignal(), "one slot full of two: floor(0.5 * 14) + 1");
        insert(world, bin, 10);
        assertEquals(15, bin.comparatorSignal(), "full");
    }

    private void insert(FakeWorld world, MachineInstanceImpl bin, long amount) {
        ResourceHandler port = bin.capability(Capabilities.RESOURCE_HANDLER, Direction.UP).orElseThrow();
        try (Transaction transaction = world.engine().transactions().openOuter()) {
            assertEquals(amount, port.insert(MachineFixtures.B.variant(), amount, transaction));
            transaction.commit();
        }
    }
}
