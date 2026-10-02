package fr.robie.machineengine.test;

import fr.robie.machineengine.api.addon.AddonRegistration;
import fr.robie.machineengine.api.network.NetworkBehavior;
import fr.robie.machineengine.api.network.NetworkType;
import fr.robie.machineengine.api.network.NetworkView;
import fr.robie.machineengine.api.network.TransportType;
import fr.robie.machineengine.api.resource.match.ResourcePredicate;
import fr.robie.machineengine.api.world.Direction;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.core.lifecycle.CoreMachineEngine;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

import java.util.List;

import static fr.robie.machineengine.test.MachineFixtures.A;
import static fr.robie.machineengine.test.MachineFixtures.CONSUMER;
import static fr.robie.machineengine.test.MachineFixtures.GENERATOR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Networks (§15). Layout along the x axis: generator (x=0, front east) – cables (x=1..3) – consumer
 * (x=4, back west). The generator has no machine in front of it, so only the network feeds the
 * consumer.
 */
class NetworkTest {
    private static final NetworkType SPARK_NET = NetworkType.of(Key.key("gamma", "matter_net"), NetworkBehavior.distributing());
    private static final TransportType CABLE = TransportType.of(Key.key("gamma", "cable"), SPARK_NET, 8, ResourcePredicate.any());
    private static final TransportType SLOW_CABLE = TransportType.of(Key.key("gamma", "slow_cable"), SPARK_NET, 2, ResourcePredicate.any());
    private static final TransportType A_ONLY_CABLE = TransportType.of(Key.key("gamma", "a_only_cable"), SPARK_NET, 8, ResourcePredicate.type(A));

    private static final WorldPosition GENERATOR_AT = FakeWorld.at(0, 64, 0);
    private static final WorldPosition CONSUMER_AT = FakeWorld.at(4, 64, 0);
    private static final List<WorldPosition> CABLES = List.of(FakeWorld.at(1, 64, 0), FakeWorld.at(2, 64, 0), FakeWorld.at(3, 64, 0));

    private static CoreMachineEngine engine() {
        CoreMachineEngine engine = TestEngine.builder()
                .addon(MachineFixtures.alpha())
                .addon(MachineFixtures.beta())
                .addon(AddonRegistration.builder("gamma", "1.0.0").register(ctx -> {
                    ctx.networkTypes().register(SPARK_NET);
                    ctx.transportTypes().register(CABLE);
                    ctx.transportTypes().register(SLOW_CABLE);
                    ctx.transportTypes().register(A_ONLY_CABLE);
                }).build())
                .start();
        if (engine.startupReport().hasErrors()) throw new AssertionError(engine.startupReport().format());
        return engine;
    }

    private static FakeWorld line(FakeWorld world, TransportType middle) {
        world.place(GENERATOR, GENERATOR_AT, Direction.EAST);
        world.place(CONSUMER, CONSUMER_AT, Direction.EAST);
        world.placeTransport(CABLE, CABLES.get(0));
        world.placeTransport(middle, CABLES.get(1));
        world.placeTransport(CABLE, CABLES.get(2));
        return world;
    }

    @Test
    void aNetworkCarriesResourcesBetweenMachinesAndConservesThem() {
        CoreMachineEngine engine = engine();
        FakeWorld world = line(FakeWorld.singleRegion(engine), CABLE);
        MachineIntegrationTest.Ledger ledger = new MachineIntegrationTest.Ledger(engine);

        for (int i = 0; i < 300; i++) {
            world.tick();
            ledger.assertConserved(world);
        }
        assertTrue(ledger.destroyed >= 100, "the consumer was fed through the network: " + ledger.destroyed);
        NetworkView network = engine.machines().networks().network(CABLES.get(1)).orElseThrow();
        assertEquals(3, network.transports().size());
        assertEquals(2, network.endpoints().size(), "the generator's output port and the consumer's input port");
        assertEquals(8, network.ratePerTick());
    }

    @Test
    void theSlowestTransportLimitsTheNetwork() {
        CoreMachineEngine engine = engine();
        FakeWorld world = line(FakeWorld.singleRegion(engine), SLOW_CABLE);
        long maxMoved = 0;
        for (int i = 0; i < 100; i++) {
            world.tick();
            maxMoved = Math.max(maxMoved, engine.machines().networks().network(CABLES.get(0)).orElseThrow().movedLastTick());
        }
        assertEquals(2, engine.machines().networks().network(CABLES.get(0)).orElseThrow().ratePerTick());
        assertEquals(2, maxMoved);
    }

    @Test
    void removingATransportSplitsTheNetworkAndReplacingItMerges() {
        CoreMachineEngine engine = engine();
        FakeWorld world = line(FakeWorld.singleRegion(engine), CABLE);
        MachineIntegrationTest.Ledger ledger = new MachineIntegrationTest.Ledger(engine);
        world.tick(50);
        world.removeTransport(CABLES.get(1));
        var networks = engine.machines().networks();
        assertEquals(1, networks.network(CABLES.get(0)).orElseThrow().transports().size());
        assertEquals(1, networks.network(CABLES.get(2)).orElseThrow().transports().size());
        assertTrue(networks.network(CABLES.get(1)).isEmpty());

        world.tick(200);
        long eatenWhileSplit = ledger.destroyed;
        world.tick(100);
        assertEquals(eatenWhileSplit, ledger.destroyed, "cut off: the consumer starves");

        world.placeTransport(CABLE, CABLES.get(1));
        assertEquals(3, networks.network(CABLES.get(0)).orElseThrow().transports().size(), "merged again");
        world.tick(100);
        assertTrue(ledger.destroyed > eatenWhileSplit, "fed again after the merge");
        ledger.assertConserved(world);
    }

    @Test
    void anIdleNetworkSleeps() {
        CoreMachineEngine engine = engine();
        FakeWorld world = FakeWorld.singleRegion(engine);
        world.place(CONSUMER, CONSUMER_AT, Direction.EAST);
        CABLES.forEach(cable -> world.placeTransport(CABLE, cable));
        world.tick(3);
        NetworkView network = engine.machines().networks().network(CABLES.get(0)).orElseThrow();
        assertFalse(network.active(), "nothing to move: the network does not tick");
        assertEquals(0, world.pendingTasks());
    }

    @Test
    void transportFiltersApplyToTheWholeNetwork() {
        CoreMachineEngine engine = engine();
        FakeWorld world = line(FakeWorld.singleRegion(engine), A_ONLY_CABLE);
        world.tick(200);
        assertEquals(0, world.inspect(CONSUMER_AT).storages().get("input").getFirst().amount(), "alpha:b may not pass");
    }

    @Test
    void networksDoNotSpanRegions() {
        CoreMachineEngine engine = engine();
        FakeWorld world = line(FakeWorld.regions(engine, position -> position.x() <= 1 ? "west" : "east"), CABLE);
        var networks = engine.machines().networks();
        assertEquals(1, networks.network(CABLES.get(0)).orElseThrow().transports().size());
        assertEquals(2, networks.network(CABLES.get(1)).orElseThrow().transports().size());
    }
}
