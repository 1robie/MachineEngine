package fr.robie.machineengine.test;

import fr.robie.machineengine.api.config.BlockOptions;
import fr.robie.machineengine.api.config.ConfigKey;
import fr.robie.machineengine.api.network.NetworkBehavior;
import fr.robie.machineengine.api.network.NetworkType;
import fr.robie.machineengine.api.network.TransportType;
import fr.robie.machineengine.api.resource.match.ResourcePredicate;
import fr.robie.machineengine.api.world.Direction;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.core.lifecycle.CoreMachineEngine;
import fr.robie.machineengine.core.machine.MachineInstanceImpl;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static fr.robie.machineengine.test.MachineFixtures.A;
import static fr.robie.machineengine.test.MachineFixtures.CHEST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A block config's {@code storages:} and {@code options:} reach its machine (D34). */
class BlockOptionsTest {
    private static final WorldPosition HERE = FakeWorld.at(0, 64, 0);
    private static final ConfigKey<Long> ENERGY = ConfigKey.whole("energy-per-tick", 200, 1, 1_000_000);
    private static final ConfigKey<Integer> RANGE = ConfigKey.integer("beam.range", 64, 1, 128).clamped();

    @Test
    void optionsAreReadWithTheirKeys() {
        List<String> warnings = new ArrayList<>();
        BlockOptions options = BlockOptions.of("test:block", Map.of("energy-per-tick", 50, "beam", Map.of("range", 500)), null,
                warnings::add);
        assertEquals(50L, options.get(ENERGY));
        assertEquals(128, options.get(RANGE), "a clamped key is limited to its range");
        assertEquals(1, warnings.size());
        options.get(RANGE);
        assertEquals(1, warnings.size(), "an invalid value is reported once");
        assertTrue(warnings.getFirst().startsWith("test:block: options.beam.range must be"), warnings.getFirst());

        assertEquals(200L, BlockOptions.EMPTY.get(ENERGY), "a missing option is the key's default");
        BlockOptions invalid = BlockOptions.of("test:block", Map.of("energy-per-tick", "fast"), null, warnings::add);
        assertEquals(200L, invalid.get(ENERGY), "an invalid option is the key's default");
        assertEquals(2, warnings.size());
    }

    @Test
    void capacitiesReplaceTheTypesAndInvalidOnesAreReported() {
        List<String> warnings = new ArrayList<>();
        BlockOptions options = BlockOptions.of("test:block", null,
                Map.of("items", Map.of("capacity", 250), "energy", Map.of("capacity", -1)), warnings::add);
        assertEquals(250, options.capacity("items", 1000));
        assertEquals(1000, options.capacity("energy", 1000), "an invalid capacity keeps the type's");
        assertEquals(1, warnings.size());
        assertSame(BlockOptions.EMPTY, BlockOptions.of("test:block", Map.of(), Map.of(), warnings::add));
    }

    @Test
    void portRatesReplaceTheTypesAndInvalidOnesAreReported() {
        List<String> warnings = new ArrayList<>();
        BlockOptions options = BlockOptions.of("test:block", null, null, Map.of("out", Map.of("rate", 64), "in", Map.of("rate", "fast")),
                warnings::add);
        assertEquals(64, options.portRate("out", 8));
        assertEquals(8, options.portRate("in", 8), "an invalid rate keeps the type's");
        assertEquals(8, options.portRate("other", 8));
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().startsWith("test:block: ports.in.rate must be"), warnings.getFirst());
    }

    @Test
    void aMachineIsSizedAndConfiguredByItsBlock() {
        CoreMachineEngine engine = TestEngine.builder().addon(MachineFixtures.alpha()).addon(MachineFixtures.beta()).start();
        FakeWorld world = FakeWorld.singleRegion(engine);
        world.setOptions(HERE, BlockOptions.of("beta:chest", Map.of("energy-per-tick", 75),
                Map.of("items", Map.of("capacity", 250)), message -> {
                }));
        MachineInstanceImpl chest = world.place(CHEST, HERE, Direction.NORTH);
        MachineInstanceImpl plain = world.place(CHEST, FakeWorld.at(5, 64, 0), Direction.NORTH);

        assertEquals(250, chest.inspect().storages().get("items").getFirst().capacity());
        assertEquals(1000, plain.inspect().storages().get("items").getFirst().capacity(), "another block keeps the type's");
        assertEquals(75L, chest.context().options().get(ENERGY));
        assertEquals(200L, plain.context().options().get(ENERGY));

        MachineFixtures.fill(chest, "items", A.stack(250));
        assertEquals(250, chest.inspect().storages().get("items").getFirst().amount(), "it holds what its block allows");
    }

    @Test
    void aMachineFollowsItsBlockConfigAfterAReload() {
        CoreMachineEngine engine = TestEngine.builder().addon(MachineFixtures.alpha()).addon(MachineFixtures.beta()).start();
        FakeWorld world = FakeWorld.singleRegion(engine);
        world.setOptions(HERE, BlockOptions.of("beta:chest", Map.of("energy-per-tick", 75), Map.of("items", Map.of("capacity", 250)),
                message -> {
                }));
        MachineInstanceImpl chest = world.place(CHEST, HERE, Direction.NORTH);
        assertEquals(75L, chest.context().options().get(ENERGY));

        world.setOptions(HERE, BlockOptions.of("beta:chest", Map.of("energy-per-tick", 90), Map.of("items", Map.of("capacity", 400)),
                message -> {
                }));
        assertEquals(75L, chest.context().options().get(ENERGY), "kept until the block configs are reloaded");
        chest.optionsReloaded();
        assertEquals(90L, chest.context().options().get(ENERGY));
        assertEquals(400, chest.inspect().storages().get("items").getFirst().capacity());
    }

    @Test
    void aTransportRateCanBeReplaced() {
        TransportType cable = TransportType.of(Key.key("test", "cable"), NetworkType.of(Key.key("test", "net"), NetworkBehavior.distributing()), 8,
                ResourcePredicate.any());
        TransportType faster = cable.withRate(32);
        assertEquals(32, faster.ratePerTick());
        assertEquals(cable.key(), faster.key());
        assertSame(cable.network(), faster.network());
        assertSame(cable, cable.withRate(8));
    }
}
