package fr.robie.machineengine.test;

import fr.robie.machineengine.api.addon.AddonId;
import fr.robie.machineengine.api.event.EngineEvents;
import fr.robie.machineengine.api.event.EventPhase;
import fr.robie.machineengine.api.machine.MachineEvents;
import fr.robie.machineengine.api.registry.Registries;
import fr.robie.machineengine.api.world.Direction;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.core.lifecycle.CoreMachineEngine;
import fr.robie.machineengine.core.machine.MachineInstanceImpl;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static fr.robie.machineengine.test.MachineFixtures.B;
import static fr.robie.machineengine.test.MachineFixtures.CHEST;
import static fr.robie.machineengine.test.MachineFixtures.GENERATOR;
import static fr.robie.machineengine.test.MachineFixtures.SPEED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Engine, upgrade and aggregated resource events (§26). */
class EngineEventsTest {
    private static final WorldPosition HERE = FakeWorld.at(0, 64, 0);
    private static final AddonId TEST = new AddonId("test");

    @Test
    void addonsSubscribingDuringRegistrationSeeTheFreeze() {
        List<Boolean> recipesLoaded = new ArrayList<>();
        CoreMachineEngine engine = TestEngine.builder()
                .addon(MachineFixtures.alpha())
                .addon("watcher", ctx -> ctx.events().subscribe(ctx.addon(), EngineEvents.REGISTRIES_FROZEN, EventPhase.POST,
                        frozen -> recipesLoaded.add(frozen.registries().registry(Registries.RECIPE).contains(MachineFixtures.MAKE_B.key()))))
                .start();
        assertEquals(List.of(true), recipesLoaded, "posted once, after the first reloadable snapshots are live");
    }

    @Test
    void reloadsAreAnnounced() {
        CoreMachineEngine engine = MachineFixtures.bothAddons();
        List<EngineEvents.Reloaded> reloads = new ArrayList<>();
        engine.events().subscribe(TEST, EngineEvents.REGISTRY_RELOADED, EventPhase.POST, reloads::add);
        engine.reload(Registries.RECIPE);
        assertEquals(1, reloads.size());
        assertEquals(Registries.RECIPE, reloads.getFirst().registry());
        assertTrue(reloads.getFirst().accepted());
    }

    @Test
    void resourceChangesAreSummedIntoOneEventPerTick() {
        CoreMachineEngine engine = MachineFixtures.bothAddons();
        FakeWorld world = FakeWorld.singleRegion(engine);
        MachineInstanceImpl chest = world.place(CHEST, HERE, Direction.NORTH);
        List<MachineEvents.Resources> events = new ArrayList<>();
        engine.events().subscribe(TEST, MachineEvents.RESOURCES_CHANGED, EventPhase.POST, events::add);

        world.runInRegionOf(HERE, () -> {
            MachineFixtures.fill(chest, "items", B.stack(3));
            MachineFixtures.fill(chest, "items", B.stack(4));
            MachineFixtures.fill(chest, "items", B.stack(5));
        });
        assertTrue(events.isEmpty(), "posted after the changes, not per transfer");
        world.tick();
        assertEquals(1, events.size());
        assertEquals(Map.of(B.variant(), 12L), events.getFirst().inserted());
        assertTrue(events.getFirst().extracted().isEmpty());
        world.tick(5);
        assertEquals(1, events.size(), "nothing changed since");
    }

    @Test
    void upgradeChangesAreAnnounced() {
        CoreMachineEngine engine = MachineFixtures.bothAddons();
        FakeWorld world = FakeWorld.singleRegion(engine);
        MachineInstanceImpl generator = world.place(GENERATOR, HERE, Direction.NORTH);
        List<MachineEvents.Upgrade> events = new ArrayList<>();
        engine.events().subscribe(TEST, MachineEvents.UPGRADE_CHANGE, EventPhase.POST, events::add);
        world.runInRegionOf(HERE, () -> {
            generator.installUpgrade(SPEED, 2);
            generator.removeUpgrade(SPEED, 2);
        });
        assertEquals(List.of(new MachineEvents.Upgrade(generator, SPEED, 0, 2), new MachineEvents.Upgrade(generator, SPEED, 2, 0)), events);
    }
}
