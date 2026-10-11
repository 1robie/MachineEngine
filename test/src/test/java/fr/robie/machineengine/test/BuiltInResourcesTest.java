package fr.robie.machineengine.test;

import fr.robie.machineengine.api.addon.AddonId;
import fr.robie.machineengine.api.capability.Capabilities;
import fr.robie.machineengine.api.config.ConfigFile;
import fr.robie.machineengine.api.config.EngineConfig;
import fr.robie.machineengine.api.definition.DefinitionFile;
import fr.robie.machineengine.api.diagnostic.DiagnosticCodes;
import fr.robie.machineengine.api.error.Result;
import fr.robie.machineengine.api.item.ItemData;
import fr.robie.machineengine.api.item.Items;
import fr.robie.machineengine.api.registry.Registries;
import fr.robie.machineengine.api.resource.BuiltInResources;
import fr.robie.machineengine.api.resource.Energy;
import fr.robie.machineengine.api.resource.Fluids;
import fr.robie.machineengine.api.resource.ResourceCategory;
import fr.robie.machineengine.api.resource.ResourceType;
import fr.robie.machineengine.api.transfer.ResourceHandler;
import fr.robie.machineengine.api.transfer.Transaction;
import fr.robie.machineengine.api.world.Direction;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.core.lifecycle.CoreMachineEngine;
import fr.robie.machineengine.paper.YamlDefinitions;
import net.kyori.adventure.key.Key;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

import static fr.robie.machineengine.test.DefinitionTest.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MachineEngine's own energy, water and lava (D29), and their settings in config.yml. */
class BuiltInResourcesTest {
    private static final Key OIL = Key.key("mymod", "fluid/oil");
    private static final WorldPosition TANK_AT = FakeWorld.at(0, 64, 0);

    @Test
    void theEngineRegistersEnergyWaterAndLavaWithTheDefaultUnits() {
        CoreMachineEngine engine = TestEngine.builder().start();
        var types = engine.registries().registry(Registries.RESOURCE_TYPE);
        var categories = engine.registries().registry(Registries.RESOURCE_CATEGORY);

        assertSame(Energy.type(), types.getOrThrow(Energy.KEY));
        assertSame(Fluids.water(), types.getOrThrow(Fluids.WATER_KEY));
        assertSame(Fluids.lava(), types.getOrThrow(Fluids.LAVA_KEY));
        assertSame(Fluids.category(), categories.getOrThrow(Fluids.CATEGORY_KEY));
        assertEquals(AddonId.MACHINE_ENGINE, types.owner(Energy.KEY).orElseThrow());
        assertEquals("J", Energy.category().unit());
        assertEquals("mB", Fluids.category().unit());
        assertEquals(0, Fluids.category().scale());
        assertEquals(1000, Fluids.bucket());
        assertEquals(0x3F76E4, Fluids.water().color().intValue(), "vanilla's water blue, from config.yml's default");
        assertEquals(0xE0641A, Fluids.lava().color().intValue());
        assertNull(Energy.type().color(), "energy has no menu color");
        assertSame(Energy.type(), TestEngine.builder().start().registries().registry(Registries.RESOURCE_TYPE).getOrThrow(Energy.KEY),
                "built once: every engine shares the same objects");
    }

    @Test
    void unitsScalesAndBucketComeFromTheConfig(@TempDir Path folder) throws IOException {
        Path file = folder.resolve("config.yml");
        Files.writeString(file, """
                resources:
                  energy: { unit: FE, scale: 3 }
                  fluid: { unit: L, bucket: 81000, water-color: "#112233" }
                """);
        ConfigFile config = ConfigFile.load(file, EngineConfig.SPEC, Logger.getAnonymousLogger());

        BuiltInResources.Built built = BuiltInResources.create(config);
        assertEquals("FE", built.energyCategory().unit());
        assertEquals(3, built.energyCategory().scale());
        assertEquals("L", built.fluidCategory().unit());
        assertEquals(81000, built.bucket());
        assertEquals(0x112233, built.water().color().intValue(), "the water color from config.yml");
        assertEquals(0xE0641A, built.lava().color().intValue(), "lava keeps its default");
        assertSame(built.energyCategory(), built.energy().category());
        assertSame(built.fluidCategory(), built.water().category());

        assertSame(BuiltInResources.get(), BuiltInResources.install(EngineConfig.SPEC.defaults()), "same settings: nothing changes");
        assertThrows(IllegalStateException.class, () -> BuiltInResources.install(config),
                "the registries already hold the default units; only a restart can change them");
    }

    /** A tank for anything, with an input port taking only energy and one taking every fluid. */
    private static CoreMachineEngine tankEngine() {
        return TestEngine.builder()
                .addon("mymod", ctx -> ctx.resourceTypes().register(Fluids.create(OIL)))
                .definitions(List.of(new DefinitionFile("definitions/tank.yml", map(
                        "machines", map("pack:tank", map(
                                "storages", map("energy", map("capacity", 1000, "filter", map("type", "machineengine:energy")),
                                        "fluid", map("capacity", 10000, "filter", map("category", "machineengine:fluid"))),
                                "ports", map("power", map("storage", "energy", "direction", "input", "sides", List.of("left")),
                                        "pipe", map("storage", "fluid", "direction", "input", "sides", List.of("right"))),
                                "behavior", map("type", "machineengine:storage")))))))
                .start();
    }

    @Test
    void definitionsAndPortsTakeTheBuiltInsAndEveryRegisteredFluid() {
        CoreMachineEngine engine = tankEngine();
        assertFalse(engine.startupReport().hasErrors(), engine.startupReport().format());
        FakeWorld world = FakeWorld.singleRegion(engine);
        world.place(engine.registries().registry(Registries.MACHINE_TYPE).getOrThrow(Key.key("pack", "tank")), TANK_AT, Direction.NORTH);
        // Facing north: left is west, right is east.
        ResourceHandler power = world.machine(TANK_AT).capability(Capabilities.RESOURCE_HANDLER, Direction.WEST).orElseThrow();
        ResourceHandler pipe = world.machine(TANK_AT).capability(Capabilities.RESOURCE_HANDLER, Direction.EAST).orElseThrow();
        ResourceType oil = engine.registries().registry(Registries.RESOURCE_TYPE).getOrThrow(OIL);

        try (Transaction transaction = engine.transactions().openOuter()) {
            assertEquals(500, power.insert(Energy.type().variant(), 500, transaction));
            assertEquals(0, power.insert(Fluids.water().variant(), 500, transaction), "energy only");
            assertEquals(Fluids.bucket(), pipe.insert(Fluids.water().variant(), Fluids.bucket(), transaction));
        }
        try (Transaction transaction = engine.transactions().openOuter()) {
            assertEquals(100, pipe.insert(Fluids.lava().variant(), 100, transaction));
        }
        try (Transaction transaction = engine.transactions().openOuter()) {
            assertEquals(100, pipe.insert(oil.variant(), 100, transaction), "an addon's fluid flows like water");
            assertEquals(0, pipe.insert(Energy.type().variant(), 100, transaction));
        }
    }

    @Test
    void aFluidOutsideTheGlobalCategoryIsReported() {
        ResourceCategory ownFluids = ResourceCategory.builder(Key.key("lonely", "liquids")).unit("mB").build();
        CoreMachineEngine engine = TestEngine.builder().addon("lonely", ctx -> {
            ctx.resourceCategories().register(ownFluids);
            ctx.resourceTypes().register(ResourceType.of(Key.key("lonely", "fluid/brine"), ownFluids));
        }).start();

        assertFalse(engine.startupReport().hasErrors(), "a warning, not an error");
        assertTrue(engine.startupReport().warnings().stream().anyMatch(d -> d.code().equals(DiagnosticCodes.FLUID_OUTSIDE_CATEGORY)
                && d.subject().equals(Key.key("lonely", "fluid/brine"))), engine.startupReport().format());
        assertTrue(engine.enabledAddons().contains(new AddonId("lonely")));
    }

    @Test
    void worldFluidsMapToWaterAndLava() {
        assertSame(Fluids.water(), Fluids.fromWorld(Fluids.WORLD_WATER));
        assertSame(Fluids.lava(), Fluids.fromWorld(Fluids.WORLD_LAVA));
        assertEquals(Fluids.WORLD_WATER, Fluids.worldFluid(Fluids.water()));
        assertEquals(Fluids.WORLD_LAVA, Fluids.worldFluid(Fluids.lava()));
        assertNull(Fluids.worldFluid(Fluids.create(OIL)));
        assertNull(Fluids.fromWorld(Key.key("minecraft", "milk")));
    }

    @Test
    void aDefinitionSetsATypesColorOrIsReported() {
        CoreMachineEngine engine = TestEngine.builder()
                .definitions(List.of(new DefinitionFile("definitions/colors.yml", map(
                        "resource-types", map(
                                "pack:tar", map("category", "machineengine:fluid", "color", "#2B2118"),
                                "pack:red", map("category", "machineengine:fluid", "color", "red"))))))
                .start();
        var types = engine.registries().registry(Registries.RESOURCE_TYPE);

        assertEquals(0x2B2118, types.getOrThrow(Key.key("pack", "tar")).color().intValue());
        assertFalse(types.contains(Key.key("pack", "red")), "a color that is not #RRGGBB skips the type");
        assertTrue(engine.startupReport().format().contains("color must be \"#RRGGBB\" but is red"), engine.startupReport().format());
    }

    @Test
    void theBundledExampleDefinitionCompiles() throws IOException {
        YamlConfiguration yaml;
        try (InputStream in = Objects.requireNonNull(YamlDefinitions.class.getClassLoader().getResourceAsStream("definitions/example.yml"))) {
            yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        yaml.set("enabled", true);
        DefinitionFile example = new DefinitionFile("definitions/example.yml", YamlDefinitions.toData(yaml));
        CoreMachineEngine engine = TestEngine.builder()
                .definitions(() -> List.of(example), id -> Result.ok(Items.variant(new ItemData(id, new byte[] {1}))))
                .start();

        assertFalse(engine.startupReport().hasErrors(), engine.startupReport().format());
        assertTrue(engine.startupReport().warnings().isEmpty(), engine.startupReport().format());
        assertTrue(engine.registries().registry(Registries.MACHINE_TYPE).contains(Key.key("custom", "iron_press")));
        assertTrue(Fluids.isFluid(engine.registries().registry(Registries.RESOURCE_TYPE).getOrThrow(Key.key("custom", "fluid/oil"))));
    }
}
