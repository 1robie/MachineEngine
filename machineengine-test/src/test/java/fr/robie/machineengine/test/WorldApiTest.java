package fr.robie.machineengine.test;

import fr.robie.machineengine.api.addon.AddonRegistration;
import fr.robie.machineengine.api.machine.MachineBehavior;
import fr.robie.machineengine.api.machine.MachineContext;
import fr.robie.machineengine.api.machine.MachineType;
import fr.robie.machineengine.api.machine.PortDefinition;
import fr.robie.machineengine.api.machine.PortDirection;
import fr.robie.machineengine.api.machine.SchedulerState;
import fr.robie.machineengine.api.machine.StorageDefinition;
import fr.robie.machineengine.api.world.BlockPosition;
import fr.robie.machineengine.api.world.Direction;
import fr.robie.machineengine.api.world.RelativeSide;
import fr.robie.machineengine.api.world.WorldFluid;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.core.lifecycle.CoreMachineEngine;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The world calls a machine can make through its host (blocks, fluids, redstone). */
class WorldApiTest {
    private static final WorldPosition HERE = FakeWorld.at(0, 64, 0);
    private static final WorldPosition NEXT = FakeWorld.at(1, 64, 0);
    private static final Key STONE = Key.key("minecraft", "stone");

    private static final BlockPosition ABOVE = new BlockPosition(0, 65, 0);
    private static final Key WATER = Key.key("minecraft", "water");

    /** Runs the current action once on its tick, then sleeps: tests drive a machine's context through it. */
    private static final AtomicReference<Consumer<MachineContext>> ACTION = new AtomicReference<>(context -> { });
    private static final MachineType PROBE = MachineType.builder(Key.key("beta", "probe"))
            .storage(StorageDefinition.tank("tank", 100))
            .port(PortDefinition.builder("io", "tank").direction(PortDirection.INPUT).sides(RelativeSide.BACK).build())
            .behavior(type -> new MachineBehavior() {
                @Override
                public void tick(@NotNull MachineContext context) {
                    ACTION.get().accept(context);
                    context.sleep();
                }
            })
            .build();

    private final CoreMachineEngine engine = TestEngine.builder()
            .addon(MachineFixtures.alpha())
            .addon(AddonRegistration.builder("beta", "1.0.0").requires("alpha", "1.0")
                    .register(ctx -> {
                        ctx.machineTypes().register(MachineFixtures.CHEST);
                        ctx.machineTypes().register(PROBE);
                    }).build())
            .start();

    @Test
    void aMachineIsNeverBrokenByAnotherMachine() {
        FakeWorld world = FakeWorld.singleRegion(this.engine);
        world.place(MachineFixtures.CHEST, HERE, Direction.NORTH);

        assertEquals(-1, world.worldBlockHardness(HERE));
        assertFalse(world.breakWorldBlock(HERE));
        assertTrue(world.machines().containsKey(HERE), "the machine is still there");
    }

    @Test
    void aPlainBlockIsBrokenAndDropsAsAnItem() {
        FakeWorld world = FakeWorld.singleRegion(this.engine);
        assertTrue(world.placeWorldBlock(NEXT, STONE));
        assertEquals(STONE, world.blockAt(NEXT));

        assertTrue(world.breakWorldBlock(NEXT));
        assertEquals(Key.key("minecraft", "air"), world.blockAt(NEXT));
        assertEquals(1, world.itemsAt(NEXT).size());
    }

    @Test
    void aBlockCannotBePlacedOnAMachineOrAnotherBlock() {
        FakeWorld world = FakeWorld.singleRegion(this.engine);
        world.place(MachineFixtures.CHEST, HERE, Direction.NORTH);

        assertFalse(world.placeWorldBlock(HERE, STONE));
        assertTrue(world.placeWorldBlock(NEXT, STONE));
        assertFalse(world.placeWorldBlock(NEXT, STONE));
    }

    /** Runs {@code action} on one tick of a probe machine at {@link #HERE}. */
    private FakeWorld probe(Consumer<MachineContext> action) {
        FakeWorld world = FakeWorld.singleRegion(this.engine);
        world.place(PROBE, HERE, Direction.NORTH);
        run(world, action);
        return world;
    }

    private static void run(FakeWorld world, Consumer<MachineContext> action) {
        ACTION.set(action);
        world.machine(HERE).wake(fr.robie.machineengine.api.machine.WakeReason.CONFIG_CHANGED);
        world.tick(1);
    }

    @Test
    void fluidsAreReadPlacedAndTaken() {
        FakeWorld world = FakeWorld.singleRegion(this.engine);
        world.place(PROBE, HERE, Direction.NORTH);
        WorldFluid[] seen = new WorldFluid[2];
        boolean[] done = new boolean[3];
        run(world, context -> {
            done[0] = context.world().placeFluid(ABOVE, WATER);
            seen[0] = context.world().fluidAt(ABOVE);
            done[1] = context.world().takeFluid(ABOVE);
            seen[1] = context.world().fluidAt(ABOVE);
            done[2] = context.world().takeFluid(ABOVE);
        });

        assertTrue(done[0]);
        assertEquals(new WorldFluid(WATER, true), seen[0]);
        assertTrue(done[1]);
        assertNull(seen[1]);
        assertFalse(done[2], "nothing left to take");
    }

    @Test
    void blocksAreReadPlacedAndBroken() {
        FakeWorld world = FakeWorld.singleRegion(this.engine);
        world.place(PROBE, HERE, Direction.NORTH);
        Object[] seen = new Object[4];
        run(world, context -> {
            seen[0] = context.world().placeBlock(ABOVE, STONE);
            seen[1] = context.world().blockAt(ABOVE);
            seen[2] = context.world().blockHardness(ABOVE);
            seen[3] = context.world().breakBlock(ABOVE);
        });

        assertEquals(true, seen[0]);
        assertEquals(STONE, seen[1]);
        assertEquals(1.5f, seen[2]);
        assertEquals(true, seen[3]);
        assertEquals(1, world.itemsAt(world.machine(HERE).position().relative(Direction.UP)).size());
    }

    @Test
    void droppedItemsAreCollectedOnce() {
        FakeWorld world = FakeWorld.singleRegion(this.engine);
        world.place(PROBE, HERE, Direction.NORTH);
        world.dropItems(FakeWorld.at(0, 65, 0), MachineFixtures.A.stack(3));
        int[] taken = new int[2];
        run(world, context -> {
            taken[0] = context.world().collectItems(ABOVE).size();
            taken[1] = context.world().collectItems(ABOVE).size();
        });

        assertEquals(1, taken[0]);
        assertEquals(0, taken[1]);
    }

    @Test
    void redstoneSoundsBeamsAndPropertiesReachTheHost() {
        FakeWorld world = FakeWorld.singleRegion(this.engine);
        world.place(PROBE, HERE, Direction.NORTH);
        world.setRedstone(HERE, 9);
        int[] power = new int[1];
        run(world, context -> {
            power[0] = context.world().redstonePower();
            context.world().playSound(Key.key("minecraft", "block.note_block.bell"), 2f, 0.5f);
            context.world().particleLine(ABOVE, 0xFF0000);
            context.world().setBlockProperty("mode", "pull");
        });

        assertEquals(9, power[0]);
        assertEquals(new FakeWorld.Sound(HERE, Key.key("minecraft", "block.note_block.bell"), 2f, 0.5f), world.sounds().getFirst());
        assertEquals(new FakeWorld.Beam(HERE, FakeWorld.at(0, 65, 0), 0xFF0000), world.beams().getFirst());
        assertEquals("pull", world.blockProperties(HERE).get("mode"));
    }

    @Test
    void otherMachinesAreReachedByPositionAndWoken() {
        FakeWorld world = FakeWorld.singleRegion(this.engine);
        world.place(PROBE, HERE, Direction.NORTH);
        world.place(MachineFixtures.CHEST, NEXT, Direction.NORTH);
        world.tick(1);
        assertEquals(SchedulerState.SLEEPING, world.state(NEXT));
        boolean[] found = new boolean[3];
        run(world, context -> {
            found[0] = context.machines().storage(NEXT, "items").isPresent();
            found[1] = context.machines().storage(NEXT, "nothing").isPresent();
            found[2] = context.machines().storage(FakeWorld.at(9, 9, 9), "items").isPresent();
            context.machines().wake(NEXT);
        });

        assertTrue(found[0]);
        assertFalse(found[1], "no such storage");
        assertFalse(found[2], "no machine there");
        assertEquals(SchedulerState.ACTIVE, world.state(NEXT), "woken");
    }

    @Test
    void aStorageCapacityIsSetAndSavedWithTheMachine() {
        FakeWorld world = probe(context -> context.setStorageCapacity("tank", 40));
        long[] capacity = new long[1];
        run(world, context -> capacity[0] = context.storage("tank").views().getFirst().capacity());
        assertEquals(40, capacity[0]);

        var saved = world.save(HERE);
        world.unload(HERE);
        world.load(HERE, saved);
        run(world, context -> capacity[0] = context.storage("tank").views().getFirst().capacity());
        assertEquals(40, capacity[0], "kept through a save");

        run(world, context -> {
            context.setStorageCapacity("tank", -1);
            capacity[0] = context.storage("tank").views().getFirst().capacity();
        });
        assertEquals(100, capacity[0], "-1 goes back to the type's capacity");
    }

    @Test
    void portDirectionAndSidesAreChangedAndRead() {
        FakeWorld world = FakeWorld.singleRegion(this.engine);
        world.place(PROBE, HERE, Direction.NORTH);
        Object[] seen = new Object[4];
        run(world, context -> {
            seen[0] = context.portDirection("io");
            context.setPortDirection("io", PortDirection.OUTPUT);
            seen[1] = context.portDirection("io");
            seen[2] = context.portSides("io");
            context.setPortSides("io", Set.of(RelativeSide.LEFT, RelativeSide.RIGHT));
            seen[3] = context.portSides("io");
        });

        assertEquals(PortDirection.INPUT, seen[0]);
        assertEquals(PortDirection.OUTPUT, seen[1]);
        assertEquals(Set.of(RelativeSide.BACK), seen[2]);
        assertEquals(Set.of(RelativeSide.LEFT, RelativeSide.RIGHT), seen[3]);
    }

    @Test
    void anExposedHandlerReplacesThePortsUntilItIsGivenBack() {
        FakeWorld world = FakeWorld.singleRegion(this.engine);
        world.place(PROBE, HERE, Direction.NORTH);
        world.place(MachineFixtures.CHEST, NEXT, Direction.NORTH);
        var handler = new fr.robie.machineengine.api.transfer.ResourceHandler[1];
        run(world, context -> {
            handler[0] = context.machines().storage(NEXT, "items").orElseThrow();
            context.exposeHandler(handler[0]);
        });
        // The probe's own port is on its back only (south): the exposed handler shows on every side.
        var capability = fr.robie.machineengine.api.capability.Capabilities.RESOURCE_HANDLER;
        assertSame(handler[0], world.machine(HERE).capability(capability, Direction.EAST).orElseThrow());

        run(world, context -> context.exposeHandler(null));
        assertTrue(world.machine(HERE).capability(capability, Direction.EAST).isEmpty(), "no port on that side again");
    }
}
