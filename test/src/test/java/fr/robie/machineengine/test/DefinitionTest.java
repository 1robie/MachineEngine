package fr.robie.machineengine.test;

import fr.robie.machineengine.api.addon.AddonId;
import fr.robie.machineengine.api.addon.AddonRegistration;
import fr.robie.machineengine.api.data.DataMap;
import fr.robie.machineengine.api.data.DataValue;
import fr.robie.machineengine.api.definition.BehaviorType;
import fr.robie.machineengine.api.definition.DefinitionFile;
import fr.robie.machineengine.api.diagnostic.Diagnostic;
import fr.robie.machineengine.api.diagnostic.DiagnosticCodes;
import fr.robie.machineengine.api.error.Result;
import fr.robie.machineengine.api.event.EventPhase;
import fr.robie.machineengine.api.machine.MachineContext;
import fr.robie.machineengine.api.machine.MachineEvents;
import fr.robie.machineengine.api.machine.MachineType;
import fr.robie.machineengine.api.recipe.ProcessingRecipe;
import fr.robie.machineengine.api.registry.Registries;
import fr.robie.machineengine.api.world.Direction;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.core.definition.DefinitionCompiler;
import fr.robie.machineengine.core.lifecycle.CoreMachineEngine;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Data-driven definitions (§29): YAML-shaped trees compiled into the same objects Java addons register. */
class DefinitionTest {
    private static final WorldPosition GENERATOR_AT = FakeWorld.at(0, 64, 0);
    private static final WorldPosition CONSUMER_AT = FakeWorld.at(1, 64, 0);

    /** The equivalent of a YAML file: a generator and a consumer of a new "pack:ore" resource. */
    private static DataMap pack(long recipeOutput) {
        return map(
                "resource-categories", map("pack:minerals", map("unit", "ore", "scale", 0)),
                "resource-types", map("pack:ore", map("category", "pack:minerals")),
                "recipe-types", List.of("pack:mining", "pack:smelting"),
                "machines", map(
                        "pack:drill", map(
                                "storages", map("output", map("capacity", 100)),
                                "ports", map("out", map("storage", "output", "direction", "output", "sides", List.of("front"))),
                                "behavior", map("type", "machineengine:processing", "recipes", "pack:mining",
                                        "output", "output", "push", "front")),
                        "pack:smelter", map(
                                "storages", map("input", map("capacity", 50, "filter", map("type", "pack:ore"))),
                                "ports", map("in", map("storage", "input", "direction", "input", "sides", List.of("back"),
                                        "filter", map("type", "pack:ore"))),
                                "upgrade-slots", 1,
                                "behavior", map("type", "machineengine:processing", "recipes", "pack:smelting",
                                        "inputs", List.of("input"), "output", "input"))),
                "recipes", map(
                        "pack:mine_ore", map("type", "pack:mining", "duration", 10,
                                "outputs", List.of(map("type", "pack:ore", "amount", recipeOutput))),
                        "pack:smelt_ore", map("type", "pack:smelting", "duration", 5,
                                "inputs", List.of(map("type", "pack:ore", "amount", 2)))));
    }

    @Test
    void definitionsCompileIntoWorkingMachines() {
        CoreMachineEngine engine = TestEngine.builder()
                .definitions(List.of(new DefinitionFile("definitions/pack.yml", pack(4))))
                .start();
        assertFalse(engine.startupReport().hasErrors(), engine.startupReport().format());
        assertTrue(engine.startupReport().warnings().isEmpty(), engine.startupReport().format());

        var machines = engine.registries().registry(Registries.MACHINE_TYPE);
        MachineType drill = machines.getOrThrow(Key.key("pack", "drill"));
        MachineType smelter = machines.getOrThrow(Key.key("pack", "smelter"));
        assertEquals(DefinitionCompiler.ADDON, machines.owner(drill.key()).orElseThrow());
        assertEquals(1, smelter.upgradeSlots());

        FakeWorld world = FakeWorld.singleRegion(engine);
        world.place(drill, GENERATOR_AT, Direction.EAST);
        world.place(smelter, CONSUMER_AT, Direction.EAST);
        AtomicLong smelted = new AtomicLong();
        engine.events().subscribe(new AddonId("test"),
                MachineEvents.PROCESS_COMPLETE,
                EventPhase.POST, event -> {
                    if (event.process().recipe().equals(Key.key("pack", "smelt_ore"))) smelted.incrementAndGet();
                });
        world.tick(200);
        assertTrue(smelted.get() > 10, "the YAML smelter processed the YAML drill's ore: " + smelted.get());
    }

    @Test
    void storageSizesOutOfRangeAreSkippedNotCrashingAtPlacement() {
        DataMap sizes = map("machines", map(
                "pack:negative_slots", map("storages", map("tank", map("capacity", 10, "slots", -1)),
                        "behavior", map("type", "machineengine:storage")),
                "pack:huge_slots", map("storages", map("tank", map("capacity", 10, "slots", 5_000_000_000L)),
                        "behavior", map("type", "machineengine:storage")),
                "pack:negative_capacity", map("storages", map("tank", map("capacity", -5)),
                        "behavior", map("type", "machineengine:storage")),
                "pack:negative_upgrades", map("storages", map("tank", map("capacity", 10)), "upgrade-slots", -2,
                        "behavior", map("type", "machineengine:storage"))));
        CoreMachineEngine engine = TestEngine.builder()
                .definitions(List.of(new DefinitionFile("definitions/sizes.yml", sizes)))
                .start();

        List<String> skipped = engine.startupReport().warnings().stream()
                .filter(d -> d.code().equals(DiagnosticCodes.DEFINITION_SKIPPED)).map(Diagnostic::location).toList();
        assertEquals(List.of(
                "definitions/sizes.yml > machines.pack:negative_slots",
                "definitions/sizes.yml > machines.pack:huge_slots",
                "definitions/sizes.yml > machines.pack:negative_capacity",
                "definitions/sizes.yml > machines.pack:negative_upgrades"), skipped);
    }

    @Test
    void invalidEntriesAreSkippedWithTheirLocationAndTheRestLoads() {
        DataMap broken = map(
                "resource-types", map(
                        "pack:ghost", map("category", "pack:nowhere"),
                        "not-namespaced", map("category", "pack:minerals")),
                "machines", map(
                        "pack:bad_machine", map("storages", map("tank", map("capacity", 10)),
                                "behavior", map("type", "pack:unknown_behavior")),
                        "pack:bad_port", map("storages", map("tank", map("capacity", 10)),
                                "ports", map("p", map("storage", "tank", "sides", List.of("sideways"))),
                                "behavior", map("type", "machineengine:storage"))),
                "recipes", map("pack:bad_recipe", map("type", "pack:mining",
                        "outputs", List.of(map("type", "pack:ghost")))));
        CoreMachineEngine engine = TestEngine.builder()
                .definitions(List.of(new DefinitionFile("definitions/pack.yml", pack(4)), new DefinitionFile("definitions/broken.yml", broken)))
                .start();

        assertFalse(engine.startupReport().hasErrors(), "skipped entries are warnings: " + engine.startupReport().format());
        List<String> skipped = engine.startupReport().warnings().stream()
                .filter(d -> d.code().equals(DiagnosticCodes.DEFINITION_SKIPPED)).map(Diagnostic::location).toList();
        assertEquals(List.of(
                "definitions/broken.yml > resource-types.pack:ghost",
                "definitions/broken.yml > resource-types.not-namespaced",
                "definitions/broken.yml > machines.pack:bad_machine",
                "definitions/broken.yml > machines.pack:bad_port",
                "definitions/broken.yml > recipes.pack:bad_recipe"), skipped);
        assertTrue(engine.registries().registry(Registries.MACHINE_TYPE).contains(Key.key("pack", "drill")), "pack.yml still loaded");
        assertTrue(engine.enabledAddons().contains(DefinitionCompiler.ADDON));
    }

    @Test
    void recipeDefinitionsReloadLive() {
        List<DefinitionFile> files = new ArrayList<>(List.of(new DefinitionFile("definitions/pack.yml", pack(4))));
        CoreMachineEngine engine = TestEngine.builder().definitions(files).start();
        files.set(0, new DefinitionFile("definitions/pack.yml", pack(9)));
        assertFalse(engine.reload(Registries.RECIPE).hasErrors());
        var recipe = (ProcessingRecipe) engine.registries().registry(Registries.RECIPE)
                .getOrThrow(Key.key("pack", "mine_ore"));
        assertEquals(9, recipe.outputs().getFirst().amount());
    }

    @Test
    void addonsProvideBehaviorTypesForDefinitions() {
        BehaviorType sleeper = BehaviorType.of(Key.key("mymod", "sleeper"),
                (config, context) -> Result.ok(type -> MachineContext::sleep));
        CoreMachineEngine engine = TestEngine.builder()
                .addon(AddonRegistration.builder("mymod", "1.0.0").register(ctx -> ctx.behaviorTypes().register(sleeper)).build())
                .definitions(List.of(new DefinitionFile("definitions/x.yml", map("machines",
                        map("pack:idle", map("storages", map("tank", map("capacity", 1)), "behavior", map("type", "mymod:sleeper")))))))
                .start();
        assertFalse(engine.startupReport().hasErrors(), engine.startupReport().format());
        assertTrue(engine.registries().registry(Registries.MACHINE_TYPE).contains(Key.key("pack", "idle")));
    }

    @Test
    void disabledFilesAreIgnored() {
        CoreMachineEngine engine = TestEngine.builder()
                .definitions(List.of(new DefinitionFile("definitions/off.yml", pack(4).with("enabled", DataValue.of(false)))))
                .start();
        assertFalse(engine.registries().registry(Registries.MACHINE_TYPE).contains(Key.key("pack", "drill")));
    }

    // ------------------------------------------------------------------------------------------

    /** Builds a data tree from alternating keys and values (String, Number, Boolean, List, DataValue). */
    static DataMap map(Object... keyValues) {
        Map<String, DataValue> entries = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) entries.put((String) keyValues[i], value(keyValues[i + 1]));
        return DataMap.of(entries);
    }

    private static DataValue value(Object value) {
        return switch (value) {
            case DataValue data -> data;
            case String string -> DataValue.of(string);
            case Boolean bool -> DataValue.of(bool);
            case Integer number -> DataValue.of((long) number);
            case Long number -> DataValue.of(number);
            case List<?> list -> DataValue.list(list.stream().map(DefinitionTest::value).toList());
            default -> throw new IllegalArgumentException("Unsupported " + value);
        };
    }
}
