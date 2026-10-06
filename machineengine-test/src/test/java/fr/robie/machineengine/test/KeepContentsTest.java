package fr.robie.machineengine.test;

import fr.robie.machineengine.api.addon.AddonRegistration;
import fr.robie.machineengine.api.data.DataMap;
import fr.robie.machineengine.api.machine.CatchUpPolicy;
import fr.robie.machineengine.api.machine.MachineContext;
import fr.robie.machineengine.api.machine.MachineInspection;
import fr.robie.machineengine.api.machine.MachineType;
import fr.robie.machineengine.api.machine.ProcessingBehavior;
import fr.robie.machineengine.api.machine.StorageDefinition;
import fr.robie.machineengine.api.process.ProcessBinding;
import fr.robie.machineengine.api.transfer.StorageView;
import fr.robie.machineengine.api.world.Direction;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.core.data.DataBinary;
import fr.robie.machineengine.core.lifecycle.CoreMachineEngine;
import fr.robie.machineengine.core.machine.CoreMachines;
import fr.robie.machineengine.core.machine.LoadedMachine;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keep-contents (§20.5): a broken machine's state travels in its item and resumes where it was
 * placed again. The generator makes 10 alpha:b every 20 ticks and is broken after 25 ticks: one
 * batch made, the second 5 ticks in.
 */
class KeepContentsTest {
    private static final WorldPosition HERE = FakeWorld.at(0, 64, 0);
    private static final WorldPosition THERE = FakeWorld.at(40, 70, -12);
    private static final MachineType GENERATOR = generator("generator");
    private static final MachineType OTHER = generator("other_generator");
    private static final MachineType BOX = MachineType.builder(Key.key("omega", "box"))
            .storage(StorageDefinition.tank("tank", 10))
            .behavior(type -> MachineContext::sleep)
            .build();

    private static MachineType generator(String name) {
        return MachineType.builder(Key.key("omega", name))
                .storage(StorageDefinition.tank("output", 1000))
                // Catch-up must not count the time spent as an item.
                .catchUp(CatchUpPolicy.bounded(60))
                .behavior(ProcessingBehavior.of(MachineFixtures.GENERATING, new ProcessBinding(List.of(), "output", null)))
                .build();
    }

    private final CoreMachineEngine engine = TestEngine.builder()
            .addon(MachineFixtures.alpha())
            .addon(AddonRegistration.builder("omega", "1.0.0").requires("alpha", "1.0").register(ctx -> {
                ctx.machineTypes().register(GENERATOR);
                ctx.machineTypes().register(OTHER);
                ctx.machineTypes().register(BOX);
            }).build())
            .start();

    @Test
    void aPackedMachineResumesWhereItIsPlacedAgain() throws IOException {
        FakeWorld world = FakeWorld.singleRegion(this.engine);
        world.place(GENERATOR, HERE, Direction.NORTH);
        world.tick(25);
        CoreMachines.Packed packed = world.pack(HERE).orElseThrow();
        assertEquals(GENERATOR.key(), packed.type());
        assertEquals(10, packed.contents().stream().mapToLong(stack -> stack.amount()).sum(), "what a host drops if it cannot keep the state");
        assertTrue(world.machines().isEmpty());
        assertTrue(world.dropped().isEmpty(), "nothing dropped: the contents are in the item");

        // Through the item's bytes, placed elsewhere, facing elsewhere, much later.
        DataMap fromItem = DataBinary.read(DataBinary.write(packed.state()));
        assertEquals(packed.state(), fromItem);
        world.skipTime(500);
        LoadedMachine restored = world.placeWithContents(GENERATOR, THERE, Direction.EAST, fromItem);

        assertEquals(Direction.EAST, restored.instance().facing());
        assertEquals(THERE, restored.instance().position());
        assertEquals(10, output(world.inspect(THERE)), "nothing happened while it was an item");
        world.tick(15);
        assertEquals(20, output(world.inspect(THERE)), "the second batch finishes as if never broken");
    }

    @Test
    void anEmptyMachineIsRemovedNormally() {
        FakeWorld world = FakeWorld.singleRegion(this.engine);
        world.place(BOX, HERE, Direction.NORTH);
        world.tick(1);
        assertTrue(world.pack(HERE).isEmpty(), "a plain, stackable item");
    }

    @Test
    void contentsOfAnotherMachineTypeAreIgnored() {
        FakeWorld world = FakeWorld.singleRegion(this.engine);
        world.place(GENERATOR, HERE, Direction.NORTH);
        world.tick(25);
        DataMap state = world.pack(HERE).orElseThrow().state();
        world.place(OTHER, THERE, Direction.NORTH);
        LoadedMachine fresh = world.loaded(THERE);
        LoadedMachine result = world.inRegionOf(THERE, () -> this.engine.machines().restore(fresh, state, world));
        assertSame(fresh, result);
        assertEquals(0, output(world.inspect(THERE)));
    }

    @Test
    void corruptItemDataIsRejected() {
        assertThrows(IOException.class, () -> DataBinary.read(new byte[] {1, 2, 3}));
    }

    private static long output(MachineInspection inspection) {
        return inspection.storages().get("output").stream().mapToLong(StorageView::amount).sum();
    }
}
