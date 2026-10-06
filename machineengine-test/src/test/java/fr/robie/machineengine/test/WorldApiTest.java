package fr.robie.machineengine.test;

import fr.robie.machineengine.api.addon.AddonRegistration;
import fr.robie.machineengine.api.world.Direction;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.core.lifecycle.CoreMachineEngine;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The world calls a machine can make through its host (blocks, fluids, redstone). */
class WorldApiTest {
    private static final WorldPosition HERE = FakeWorld.at(0, 64, 0);
    private static final WorldPosition NEXT = FakeWorld.at(1, 64, 0);
    private static final Key STONE = Key.key("minecraft", "stone");

    private final CoreMachineEngine engine = TestEngine.builder()
            .addon(MachineFixtures.alpha())
            .addon(AddonRegistration.builder("beta", "1.0.0").requires("alpha", "1.0")
                    .register(ctx -> ctx.machineTypes().register(MachineFixtures.CHEST)).build())
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
}
