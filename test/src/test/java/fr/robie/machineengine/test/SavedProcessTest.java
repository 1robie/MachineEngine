package fr.robie.machineengine.test;

import fr.robie.machineengine.api.data.DataMap;
import fr.robie.machineengine.api.data.DataValue;
import fr.robie.machineengine.api.world.Direction;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.core.lifecycle.CoreMachineEngine;
import org.junit.jupiter.api.Test;

import static fr.robie.machineengine.test.MachineFixtures.GENERATOR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A saved process whose storages the type no longer has (D3): kept raw, and the machine works on. */
class SavedProcessTest {
    private static final WorldPosition HERE = FakeWorld.at(0, 64, 0);

    @Test
    void aProcessBoundToARenamedStorageIsKeptAsUnknownData() {
        CoreMachineEngine engine = MachineFixtures.bothAddons();
        FakeWorld world = FakeWorld.singleRegion(engine);
        world.place(GENERATOR, HERE, Direction.NORTH);
        world.tick(5);
        assertNotNull(world.inspect(HERE).process(), "running");

        // As if the type's "output" storage had been renamed in its YAML since the save.
        DataMap saved = world.unload(HERE);
        DataMap process = saved.getMap("process").value();
        DataMap binding = process.getMap("binding").value().with("output", DataValue.of("renamed"));
        world.load(HERE, saved.with("process", process.with("binding", binding)));

        var inspection = world.inspect(HERE);
        assertNull(inspection.process(), "not loaded as a process that fails on every tick");
        assertTrue(inspection.unknownData().contains("process"), "kept raw");
        assertEquals(binding, world.save(HERE).getMap("process").value().getMap("binding").value(), "saved again unchanged");

        // No new process over one that holds inputs, until an admin purges it (D3).
        world.tick(5);
        assertNull(world.inspect(HERE).process());
        world.runInRegionOf(HERE, () -> {
            assertEquals(1, world.machine(HERE).purgeUnknown());
            world.machine(HERE).wake(fr.robie.machineengine.api.machine.WakeReason.CONFIG_CHANGED);
        });
        world.tick(2);
        assertNotNull(world.inspect(HERE).process(), "a new process starts after the purge");
    }
}
