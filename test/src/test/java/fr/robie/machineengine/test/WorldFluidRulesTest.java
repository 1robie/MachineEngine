package fr.robie.machineengine.test;

import fr.robie.machineengine.api.resource.fluid.FluidLook;
import fr.robie.machineengine.api.resource.fluid.FluidType;
import fr.robie.machineengine.nms.BiomeSections;
import fr.robie.machineengine.paper.fluid.FluidChunkCodec;
import fr.robie.machineengine.paper.fluid.FluidRules;
import fr.robie.machineengine.paper.fluid.client.BiomeZoom;
import fr.robie.machineengine.paper.fluid.client.Cells;
import fr.robie.machineengine.paper.fluid.client.FluidCells;
import fr.robie.machineengine.paper.fluid.client.FluidKeys;
import fr.robie.machineengine.paper.fluid.client.KeyPalette;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** World fluids (D46, docs/FLUID_TYPES_PLAN.md stage 4): keys, saved data and where fluids may go. */
class WorldFluidRulesTest {
    private static final int NEUTRAL = 0x3F76E4;
    private static final UUID WORLD = UUID.randomUUID();
    private static final FluidRules.Settings STOP = new FluidRules.Settings(true, 2, 1, 2);
    private static final FluidRules.Settings ALLOW = new FluidRules.Settings(false, 2, 1, 2);

    private static FluidType fluid(String name, int color) {
        return FluidType.builder(Key.key("mymod", "fluid/" + name)).color(color).placeable().build();
    }

    @Test
    void thePaletteIsTheOneTheSimulationMeasured() {
        // tmp uniform_sim.py, 22 keys (docs/FLUID_TYPES_PLAN.md §4.4).
        int[] measured = {0xFF0000, 0x0080FF, 0x66FF00, 0x2631FF, 0xFFF426, 0x26E9FF, 0x0040FF, 0x7300FF, 0x26FF31, 0xFF8826, 0xB2FF00,
                0x00B2FF, 0x3C26FF, 0x0019FF, 0xC926FF, 0x0059FF, 0xFFBE26, 0x00E5FF, 0x8BFF4D, 0xFF5226, 0x4DFF4D, 0x00FF40};
        assertArrayEquals(measured, KeyPalette.choose(22, NEUTRAL).stream().mapToInt(Integer::intValue).toArray());
        assertEquals(KeyPalette.choose(5, NEUTRAL), KeyPalette.choose(22, NEUTRAL).subList(0, 5), "more keys only add to the end");
        assertEquals(0.05, KeyPalette.shareCut(12));
        assertEquals(0.20, KeyPalette.shareCut(22));
        assertEquals(0.40, KeyPalette.shareCut(48));
    }

    @Test
    void fluidsThatLookTheSameShareAKey() {
        FluidLook textured = FluidLook.TINTED_WATER;
        FluidType oil = fluid("oil", 0x2A2238);
        FluidType tar = fluid("tar", 0x2A2238);
        FluidType brine = fluid("brine", 0xC8B88A);
        FluidKeys keys = FluidKeys.of(List.of(oil, brine, tar), FluidType::look, NEUTRAL, true);

        assertEquals(3, keys.fluidCount());
        assertEquals(2, keys.keyCount(), "oil and tar: water tinted with the same color");
        assertEquals(keys.keyIndex(keys.number(oil)), keys.keyIndex(keys.number(tar)));
        assertNotEquals(keys.keyIndex(keys.number(oil)), keys.keyIndex(keys.number(brine)));
        assertEquals(List.of(brine, oil, tar), keys.fluids(), "numbered by key, so the same fluids always get the same keys");
        assertEquals(0, keys.neutralEntry(), "uniform water: every biome already is the neutral");
        assertEquals(2 * FluidKeys.LEVELS, keys.keyBiomes().size());
        assertEquals(KeyPalette.mix(keys.keyColor(0), NEUTRAL, 0.5), keys.keyBiomes().get(1).waterColor(), "half key, half neutral");
        assertEquals(textured, FluidLook.TINTED_WATER);
    }

    @Test
    void withoutUniformWaterOnlySixLooksGetAKey() {
        List<FluidType> fluids = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) fluids.add(fluid("f" + i, 0x101010 * (i + 1)));
        FluidKeys keys = FluidKeys.of(fluids, FluidType::look, NEUTRAL, false);

        assertEquals(6, keys.keyCount());
        assertEquals(2, keys.withoutKey().size());
        assertFalse(keys.hasKey(keys.number(keys.withoutKey().getFirst())));
        assertEquals(6 * FluidKeys.LEVELS + 1, keys.neutralEntry(), "the neutral water comes after every key");
        assertEquals(6 * FluidKeys.LEVELS + 1, keys.keyBiomes().size());
    }

    @Test
    void savedFluidsKeepTheirKeysAndNewerDataIsLeftAlone() throws Exception {
        Map<Long, Key> blocks = new LinkedHashMap<>();
        blocks.put(Cells.pack(1, 64, 2), Key.key("mymod", "fluid/oil"));
        blocks.put(Cells.pack(-30_000_000, -64, 29_999_999), Key.key("gone", "fluid/acid"));
        blocks.put(Cells.pack(1, 65, 2), Key.key("mymod", "fluid/oil"));

        assertEquals(blocks, FluidChunkCodec.decode(FluidChunkCodec.encode(blocks)));
        byte[] newer = FluidChunkCodec.encode(blocks);
        newer[0] = (byte) (FluidChunkCodec.VERSION + 1);
        assertThrows(FluidChunkCodec.NewerFormatException.class, () -> FluidChunkCodec.decode(newer));
        byte[] damaged = FluidChunkCodec.encode(blocks);
        assertThrows(IOException.class, () -> FluidChunkCodec.decode(java.util.Arrays.copyOf(damaged, damaged.length - 3)));
    }

    @Test
    void packedPositionsRoundTrip() {
        long packed = Cells.pack(-29_999_984, -64, 29_999_984);
        assertEquals(-29_999_984, Cells.x(packed));
        assertEquals(-64, Cells.y(packed));
        assertEquals(29_999_984, Cells.z(packed));
        assertEquals(-5, Cells.chunkX(Cells.chunk(-5, 7)));
        assertEquals(7, Cells.chunkZ(Cells.chunk(-5, 7)));
    }

    @Test
    void aBlockReadsOneOfTheEightCellsAroundIt() {
        long seed = BiomeZoom.zoomSeed(12345L);
        for (int i = 0; i < 1000; i++) {
            int x = i * 37 - 5000, y = (i * 13) % 300 - 64, z = 7000 - i * 53;
            long cell = BiomeZoom.cell(seed, x, y, z);
            int qx = (x - 2) >> 2, qy = (y - 2) >> 2, qz = (z - 2) >> 2;
            assertTrue(Cells.x(cell) - qx >= 0 && Cells.x(cell) - qx <= 1);
            assertTrue(Cells.y(cell) - qy >= 0 && Cells.y(cell) - qy <= 1);
            assertTrue(Cells.z(cell) - qz >= 0 && Cells.z(cell) - qz <= 1);
        }
    }

    /** A world of water blocks for the rules. */
    private static final class Water implements FluidRules.Blocks {
        final Set<Long> water = new HashSet<>();

        void fill(int x0, int y0, int z0, int x1, int y1, int z1) {
            for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) this.water.add(Cells.pack(x, y, z));
        }

        @Override
        public boolean isWater(int x, int y, int z) {
            return this.water.contains(Cells.pack(x, y, z));
        }

        @Override
        public boolean readable(int x, int z) {
            return true;
        }
    }

    private record World(FluidKeys keys, FluidCells cells, Water blocks, int oil, int brine) {
        void put(int number, int x0, int y0, int z0, int x1, int y1, int z1) {
            this.blocks.fill(x0, y0, z0, x1, y1, z1);
            for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) this.cells.set(WORLD, x, y, z, number);
        }

        FluidRules.Refusal check(int x, int y, int z, int number, FluidRules.Settings settings) {
            return FluidRules.check(this.cells, WORLD, this.blocks, x, y, z, number, settings);
        }
    }

    private static World world() {
        FluidType oil = fluid("oil", 0x2A2238);
        FluidType brine = fluid("brine", 0xC8B88A);
        FluidKeys keys = FluidKeys.of(List.of(oil, brine), FluidType::look, NEUTRAL, true);
        FluidCells cells = new FluidCells(world -> BiomeZoom.zoomSeed(42L), keys, 0);
        return new World(keys, cells, new Water(), keys.number(oil), keys.number(brine));
    }

    @Test
    void differentFluidsKeepTheGapWhateverTheConflictRule() {
        World world = world();
        world.put(world.oil(), 0, 60, 0, 7, 63, 7);

        for (FluidRules.Settings settings : List.of(STOP, ALLOW)) {
            FluidRules.Refusal near = world.check(14, 62, 3, world.brine(), settings);
            assertEquals(FluidRules.Reason.OTHER_FLUID_NEAR, near.reason());
            assertEquals(world.oil(), near.other(), "names the fluid in the way");
            assertNull(world.check(40, 62, 3, world.brine(), settings), "far enough");
        }
        assertNull(world.check(14, 62, 3, world.oil(), STOP), "the same fluid may come closer");
        assertNull(world.check(14, 62, 3, world.brine(), new FluidRules.Settings(true, 0, 1, 2)), "gap 0: only the area rule");
    }

    @Test
    void underTheStopRuleAnAreaHoldsOneKindOfWater() {
        World world = world();
        world.put(world.oil(), 0, 60, 0, 7, 63, 7);
        world.blocks().fill(30, 60, 30, 37, 63, 37); // plain water

        FluidRules.Refusal plain = world.check(3, 62, 3, 0, STOP);
        assertEquals(FluidRules.Reason.AREA_OF_FLUID, plain.reason(), "plain water can't go where oil owns the area");
        assertEquals(world.oil(), plain.other());
        assertEquals(FluidRules.Reason.AREA_READ_BY_WATER, world.check(33, 62, 33, world.oil(), STOP).reason(),
                "oil can't go where plain water reads the area");
        assertNull(world.check(3, 62, 3, 0, ALLOW));
        assertNull(world.check(33, 62, 33, world.oil(), ALLOW));
    }

    @Test
    void aFluidWithoutAKeyCantBePlaced() {
        List<FluidType> fluids = new java.util.ArrayList<>();
        for (int i = 0; i < 7; i++) fluids.add(fluid("f" + i, 0x101010 * (i + 1)));
        FluidKeys keys = FluidKeys.of(fluids, FluidType::look, NEUTRAL, false);
        FluidCells cells = new FluidCells(world -> 1L, keys, 6);
        int last = keys.number(keys.withoutKey().getFirst());

        assertEquals(FluidRules.Reason.NO_KEY, FluidRules.check(cells, WORLD, new Water(), 0, 64, 0, last, STOP).reason());
    }

    @Test
    void cellsAroundAFluidGetItsKeyOrADilutedOne() {
        World world = world();
        world.put(world.oil(), 0, 60, 0, 7, 63, 7);
        long dry = find(world, cell -> world.cells().owner(WORLD, cell) == 0
                && !FluidRules.readersAreWater(world.cells(), WORLD, world.blocks(), cell, Integer.MIN_VALUE, 0, 0, false)
                && world.cells().nearestOwner(WORLD, cell, 1, false)[0] == world.oil());
        long owned = world.cells().readCell(WORLD, 3, 62, 3);

        assertEquals(world.keys().entry(world.oil(), 0), world.cells().key(WORLD, owned));
        assertEquals(world.keys().entry(world.oil(), 0), FluidRules.surroundingEntry(world.cells(), WORLD, world.blocks(), dry, STOP),
                "padding: the full key where no water reads the cell");

        world.blocks().fill(-12, 56, -12, -1, 67, 20); // plain water beside the pool
        long ring = find(world, cell -> world.cells().owner(WORLD, cell) == 0
                && FluidRules.readersAreWater(world.cells(), WORLD, world.blocks(), cell, Integer.MIN_VALUE, 0, 0, false)
                && world.cells().nearestOwner(WORLD, cell, 2, true)[0] == world.oil());
        int[] near = world.cells().nearestOwner(WORLD, ring, 2, true);
        assertEquals(world.oil(), near[0]);
        assertEquals(0, FluidRules.surroundingEntry(world.cells(), WORLD, world.blocks(), ring, STOP), "stop: no fading into water");
        assertEquals(world.keys().entry(world.oil(), near[1]), FluidRules.surroundingEntry(world.cells(), WORLD, world.blocks(), ring, ALLOW),
                "allow: diluted by the distance");
    }

    @Test
    void noPaddingWithinBlendReachOfAnotherFluid() {
        World world = world();
        world.put(world.oil(), 0, 60, 0, 7, 63, 7);
        world.put(world.brine(), 22, 60, 0, 29, 63, 7); // closer than the gap allows: the rule must hold anyway
        java.util.function.LongPredicate dryBesideOil = cell -> world.cells().owner(WORLD, cell) == 0
                && !FluidRules.readersAreWater(world.cells(), WORLD, world.blocks(), cell, Integer.MIN_VALUE, 0, 0, false)
                && world.cells().nearestOwner(WORLD, cell, 1, false)[0] == world.oil();
        long towardBrine = find(world, cell -> dryBesideOil.test(cell)
                && world.cells().otherOwnerNear(WORLD, cell, FluidRules.OTHER_FLUID_REACH, world.oil()) == world.brine());
        long awayFromBrine = find(world, cell -> dryBesideOil.test(cell)
                && world.cells().otherOwnerNear(WORLD, cell, FluidRules.OTHER_FLUID_REACH, world.oil()) == 0);

        assertEquals(0, FluidRules.surroundingEntry(world.cells(), WORLD, world.blocks(), towardBrine, STOP),
                "two keys blended are no fluid: no oil padding where brine's blocks blend it in");
        assertEquals(world.keys().entry(world.oil(), 0), FluidRules.surroundingEntry(world.cells(), WORLD, world.blocks(), awayFromBrine, STOP));
    }

    /** A cell around the pool (blocks 0..7, 60..63, 0..7) that fits. */
    private static long find(World world, java.util.function.LongPredicate fits) {
        for (int x = -4; x <= 5; x++) for (int y = 13; y <= 18; y++) for (int z = -4; z <= 5; z++) {
            long cell = Cells.pack(x, y, z);
            if (fits.test(cell)) return cell;
        }
        throw new AssertionError("no such cell around the pool");
    }

    @Test
    void biomeSectionsMatchTheClientFormat() {
        ByteArrayOutputStream single = new ByteArrayOutputStream();
        int[] ids = new int[64];
        java.util.Arrays.fill(ids, 5);
        BiomeSections.write(single, ids, 90, false);
        assertArrayEquals(new byte[]{0, 5}, single.toByteArray(), "one biome: bits 0 and its id");
        ByteArrayOutputStream old = new ByteArrayOutputStream();
        BiomeSections.write(old, ids, 90, true);
        assertArrayEquals(new byte[]{0, 5, 0}, old.toByteArray(), "before 1.21.5: an empty long array follows");

        ids[0] = 70;
        ByteArrayOutputStream two = new ByteArrayOutputStream();
        BiomeSections.write(two, ids, 90, false);
        byte[] bytes = two.toByteArray();
        assertEquals(1, bytes[0], "two biomes: 1 bit per cell, linear palette");
        assertEquals(2, bytes[1]);
        assertEquals(3 + 1 + 8, bytes.length, "bits, palette size, 2 ids (70 is one byte), one long");
    }
}
