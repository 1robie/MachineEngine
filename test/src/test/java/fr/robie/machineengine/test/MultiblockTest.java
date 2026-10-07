package fr.robie.machineengine.test;

import fr.robie.machineengine.api.data.DataMap;
import fr.robie.machineengine.api.definition.DefinitionFile;
import fr.robie.machineengine.api.machine.MachineType;
import fr.robie.machineengine.api.multiblock.MultiblockDefinitions;
import fr.robie.machineengine.api.multiblock.MultiblockShape;
import fr.robie.machineengine.api.multiblock.MultiblockStructure;
import fr.robie.machineengine.api.multiblock.MultiblockType;
import fr.robie.machineengine.api.multiblock.Multiblocks;
import fr.robie.machineengine.api.registry.Registries;
import fr.robie.machineengine.api.world.BlockPosition;
import fr.robie.machineengine.api.world.Direction;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.core.lifecycle.CoreMachineEngine;
import fr.robie.machineengine.core.machine.MachineInstanceImpl;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static fr.robie.machineengine.test.DefinitionTest.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Multiblocks (D33): box and pattern shapes, rotations, and data-driven multiblocks. */
class MultiblockTest {
    private static final Key WORLD = Key.key("minecraft", "overworld");
    private static final Key CASING = Key.key("pack", "casing");
    private static final Key CORE = Key.key("pack", "core");
    private static final Key GLASS = Key.key("minecraft", "glass");
    private static final Key STONE = Key.key("minecraft", "stone");

    /**
     * An L-shaped altar, not symmetric, so every rotation is a different placement:
     * <pre>
     * layer 0:  C#C      layer 1:  .G.
     *           C..                ...
     * </pre>
     */
    private static final MultiblockShape.Pattern ALTAR = new MultiblockShape.Pattern(
            List.of(List.of("C#C", "C  "), List.of(" G ", "   ")),
            Map.of('C', Set.of(CASING), '#', Set.of(CORE), 'G', Set.of(GLASS)), '#', true);
    private static final MultiblockType ALTAR_TYPE = new MultiblockType(Key.key("pack", "altar"), ALTAR);

    @AfterEach
    void forget() {
        Multiblocks.clear();
    }

    /** Builds the altar at {@code origin}, turned {@code quarters} times clockwise. */
    private static Map<BlockPosition, Key> build(BlockPosition origin, int quarters) {
        Map<BlockPosition, Key> world = new HashMap<>();
        for (MultiblockShape.Pattern.Cell cell : ALTAR.cells()) {
            int x = cell.x(), z = cell.z();
            for (int i = 0; i < quarters; i++) {
                int east = -z;
                z = x;
                x = east;
            }
            world.put(new BlockPosition(origin.x() + x, origin.y() + cell.y(), origin.z() + z),
                    ALTAR.key().get(cell.symbol()).iterator().next());
        }
        return world;
    }

    private static MultiblockStructure detect(Map<BlockPosition, Key> world, BlockPosition start, MultiblockType type) {
        return Multiblocks.detect(WORLD, at -> world.getOrDefault(at, Key.key("minecraft", "air")), start, type);
    }

    @Test
    void aPatternIsFoundInEveryRotationFromAnyOfItsBlocks() {
        BlockPosition origin = new BlockPosition(10, 64, 10);
        for (int quarters = 0; quarters < 4; quarters++) {
            Map<BlockPosition, Key> world = build(origin, quarters);
            for (BlockPosition start : world.keySet()) {
                MultiblockStructure found = detect(world, start, ALTAR_TYPE);
                assertNotNull(found, "rotation " + quarters + ", from " + start);
                assertEquals(quarters, found.rotation());
                assertEquals(CORE, world.get(found.anchorPosition()), "the anchor is the '#' block");
                assertEquals(world.keySet(), found.members());
                assertEquals(1, found.count(CORE));
                assertEquals(3, found.count(CASING));
            }
        }
    }

    @Test
    void aMissingOrWrongBlockIsNoStructure() {
        BlockPosition origin = new BlockPosition(0, 64, 0);
        Map<BlockPosition, Key> world = build(origin, 1);
        BlockPosition glass = world.entrySet().stream().filter(e -> e.getValue().equals(GLASS)).findFirst().orElseThrow().getKey();
        BlockPosition core = world.entrySet().stream().filter(e -> e.getValue().equals(CORE)).findFirst().orElseThrow().getKey();
        world.remove(glass);
        assertNull(detect(world, core, ALTAR_TYPE), "the glass is missing");
        world.put(glass, STONE);
        assertNull(detect(world, core, ALTAR_TYPE), "stone instead of glass");
    }

    @Test
    void aPatternThatDoesNotRotateMustBeBuiltAsWritten() {
        MultiblockType fixed = new MultiblockType(Key.key("pack", "fixed"),
                new MultiblockShape.Pattern(ALTAR.layers(), ALTAR.key(), '#', false));
        BlockPosition origin = new BlockPosition(0, 64, 0);
        Map<BlockPosition, Key> asWritten = build(origin, 0);
        assertNotNull(detect(asWritten, origin, fixed));
        Map<BlockPosition, Key> turned = build(origin, 1);
        assertNull(detect(turned, turned.keySet().iterator().next(), fixed));
    }

    @Test
    void aPatternContainsOnlyItsBlocks() {
        BlockPosition origin = new BlockPosition(0, 64, 0);
        Map<BlockPosition, Key> world = build(origin, 2);
        MultiblockStructure found = detect(world, world.keySet().iterator().next(), ALTAR_TYPE);
        assertNotNull(found);
        for (BlockPosition member : world.keySet()) assertTrue(found.contains(new WorldPosition(WORLD, member)));
        // Inside its bounding box but not one of its blocks (the empty corner of the L).
        for (int x = found.min().x(); x <= found.max().x(); x++) {
            for (int y = found.min().y(); y <= found.max().y(); y++) {
                for (int z = found.min().z(); z <= found.max().z(); z++) {
                    BlockPosition at = new BlockPosition(x, y, z);
                    assertEquals(world.containsKey(at), found.contains(new WorldPosition(WORLD, at)), at.toString());
                }
            }
        }
        Multiblocks.form(found);
        assertEquals(found, Multiblocks.containing(found.anchor()));
    }

    @Test
    void aHollowBoxIsStillABox() {
        MultiblockType tank = new MultiblockType(Key.key("pack", "tank"),
                new MultiblockShape.Box(CASING, Set.of(CASING, GLASS), block -> block.value().equals("air")));
        Map<BlockPosition, Key> world = new HashMap<>();
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 3; y++) {
                for (int z = 0; z < 3; z++) {
                    int bounds = (x != 1 ? 1 : 0) + (y != 1 ? 1 : 0) + (z != 1 ? 1 : 0);
                    if (bounds == 0) continue;
                    world.put(new BlockPosition(x, y, z), bounds == 1 ? GLASS : CASING);
                }
            }
        }
        MultiblockStructure found = detect(world, new BlockPosition(1, 0, 1), tank);
        assertNotNull(found);
        assertEquals(new BlockPosition(0, 0, 0), found.anchorPosition());
        assertEquals(1, found.volume());
        world.put(new BlockPosition(1, 1, 1), STONE);
        assertNull(detect(world, new BlockPosition(1, 0, 1), tank), "something inside");
    }

    @Test
    void dataDrivenPatternMultiblocksFormAndSizeTheirAnchor() {
        DataMap pack = map(
                "multiblocks", map("pack:altar", map(
                        "pattern", map("layers", List.of(List.of("C#C", "C  "), List.of(" C ", "   ")),
                                "key", map("C", "pack:casing", "#", "pack:core"), "anchor", "#"),
                        "capacities", map("mana", 500))),
                "machines", map(
                        "pack:casing", map("behavior", map("type", "machineengine:multiblock_member", "multiblocks", List.of("pack:altar"))),
                        "pack:core", map(
                                "storages", map("mana", map("capacity", 1)),
                                "behavior", map("type", "machineengine:multiblock_member", "multiblocks", List.of("pack:altar")))));
        CoreMachineEngine engine = TestEngine.builder().definitions(List.of(new DefinitionFile("definitions/altar.yml", pack))).start();
        assertFalse(engine.startupReport().hasErrors(), engine.startupReport().format());
        assertTrue(engine.startupReport().warnings().isEmpty(), engine.startupReport().format());
        assertTrue(engine.registries().registry(Registries.MULTIBLOCK_TYPE).contains(Key.key("pack", "altar")));

        MachineType casing = engine.registries().registry(Registries.MACHINE_TYPE).getOrThrow(CASING);
        MachineType core = engine.registries().registry(Registries.MACHINE_TYPE).getOrThrow(CORE);
        FakeWorld world = FakeWorld.singleRegion(engine);
        // Built turned once (facing east): C#C runs north to south.
        world.place(casing, FakeWorld.at(0, 64, 0), Direction.NORTH);
        MachineInstanceImpl anchor = world.place(core, FakeWorld.at(0, 64, 1), Direction.NORTH);
        world.place(casing, FakeWorld.at(0, 64, 2), Direction.NORTH);
        world.place(casing, FakeWorld.at(-1, 64, 0), Direction.NORTH);
        world.tick(3);
        assertNull(Multiblocks.containing(anchor.position()), "not complete: the top casing is missing");
        world.place(casing, FakeWorld.at(0, 65, 1), Direction.NORTH);
        world.tick(3);
        MultiblockStructure formed = Multiblocks.containing(anchor.position());
        assertNotNull(formed, "formed");
        assertEquals(anchor.position(), formed.anchor());
        assertEquals(500, anchor.inspect().storages().get("mana").getFirst().capacity(), "the anchor's storage is sized");
    }

    @Test
    void badDefinitionsAreRejected() {
        assertTrue(MultiblockDefinitions.type(Key.key("pack", "x"), map("pattern", map("layers", List.of(List.of("AB")),
                "key", map("A", "pack:a"), "anchor", "A"))).isErr(), "B is not in the key");
        assertTrue(MultiblockDefinitions.type(Key.key("pack", "x"), map("pattern", map("layers", List.of(List.of("AA")),
                "key", map("A", "pack:a"), "anchor", "A"))).isErr(), "the anchor appears twice");
        assertTrue(MultiblockDefinitions.type(Key.key("pack", "x"), map("box", map("casing", "pack:a", "faces", List.of("pack:b")))).isErr(),
                "the casing must be a face");
        assertTrue(MultiblockDefinitions.type(Key.key("pack", "x"), map()).isErr(), "no shape");
    }
}
