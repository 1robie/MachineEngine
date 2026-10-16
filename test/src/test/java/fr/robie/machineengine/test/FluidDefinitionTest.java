package fr.robie.machineengine.test;

import fr.robie.machineengine.api.data.DataMap;
import fr.robie.machineengine.api.data.DataValue;
import fr.robie.machineengine.api.definition.DefinitionFile;
import fr.robie.machineengine.api.diagnostic.Diagnostic;
import fr.robie.machineengine.api.registry.Registries;
import fr.robie.machineengine.api.resource.Fluids;
import fr.robie.machineengine.api.resource.ResourceType;
import fr.robie.machineengine.api.resource.fluid.FluidItems;
import fr.robie.machineengine.api.resource.fluid.FluidMixing;
import fr.robie.machineengine.api.resource.fluid.FluidPhysics;
import fr.robie.machineengine.api.resource.fluid.FluidReaction;
import fr.robie.machineengine.api.resource.fluid.FluidTexture;
import fr.robie.machineengine.api.resource.fluid.FluidType;
import fr.robie.machineengine.api.resource.fluid.Spread;
import fr.robie.machineengine.api.resource.fluid.WorldMode;
import fr.robie.machineengine.api.resource.tag.ResourceTags;
import fr.robie.machineengine.core.lifecycle.CoreMachineEngine;
import fr.robie.machineengine.core.resource.fluid.BuiltInFluidTypes;
import fr.robie.machineengine.core.resource.fluid.FluidOverrides;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fluids from definitions YAML and the server owner's fluids.yml (docs/FLUID_TYPES_PLAN.md stage 2). */
class FluidDefinitionTest {
    private static final Key OIL = Key.key("pack", "fluid/oil");
    private static final Key BRINE = Key.key("pack", "fluid/brine");

    /** A YAML-shaped tree: map("a", 1, "b", map(...), "c", list(...)). */
    static DataMap map(Object... entries) {
        Map<String, DataValue> out = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) out.put((String) entries[i], value(entries[i + 1]));
        return DataMap.of(out);
    }

    static DataValue list(Object... values) {
        List<DataValue> out = new ArrayList<>();
        for (Object value : values) out.add(value(value));
        return DataValue.list(out);
    }

    private static DataValue value(Object value) {
        return switch (value) {
            case DataValue data -> data;
            case String text -> DataValue.of(text);
            case Integer number -> DataValue.of(number.longValue());
            case Long number -> DataValue.of(number);
            case Double number -> DataValue.of(number);
            case Boolean bool -> DataValue.of(bool);
            default -> throw new IllegalArgumentException(String.valueOf(value));
        };
    }

    private static CoreMachineEngine start(DataMap content) {
        return TestEngine.builder().definitions(List.of(new DefinitionFile("definitions/fluids.yml", content))).start();
    }

    private static FluidType fluid(CoreMachineEngine engine, Key key) {
        return assertInstanceOf(FluidType.class, engine.registries().registry(Registries.RESOURCE_TYPE).getOrThrow(key));
    }

    private static List<String> skipped(CoreMachineEngine engine) {
        return engine.startupReport().diagnostics().stream().map(Diagnostic::format).toList();
    }

    @Test
    void aDefinitionWithAFluidSectionMakesAPlaceableFluidType() {
        CoreMachineEngine engine = start(map("resource-types", map(OIL.asString(), map(
                "color", "#2A2238",
                "fluid", map(
                        "world", "placeable",
                        "bucket", "pack:oil_bucket",
                        "look", map("still", map("source", "recolor", "pattern", "lava", "from", "#0A080E", "to", "#4E3E60"),
                                "flow", "pack:block/oil_flow", "opacity", 0.96, "glow", 0.1, "fog", "#0B0806", "frame-time", 3),
                        "physics", map("viscosity", map("speed", 0.5, "gravity", 1.6, "damping", 0.35),
                                "spread", map("ticks", 15, "range", 5), "infinite-source", false, "mixed-source", "water"),
                        "mixing", list(map("with", "water", "result", "flow"),
                                map("with", "#pack:salty", "result", "block", "block", "minecraft:calcite")),
                        "effects", list(map("type", "potion", "effect", "minecraft:slowness"),
                                map("type", "machineengine:fire", "ticks", 40)))))));

        FluidType oil = fluid(engine, OIL);
        assertTrue(skipped(engine).isEmpty(), () -> String.valueOf(skipped(engine)));
        assertSame(Fluids.category(), oil.category(), "a fluid section means the fluid category");
        assertEquals(WorldMode.PLACEABLE, oil.world());
        assertEquals(0x2A2238, oil.color().intValue());
        assertEquals(new FluidItems(new FluidItems.Bucket.Item(Key.key("pack", "oil_bucket"))), oil.items());
        assertNotNull(oil.look().still());
        assertNotNull(oil.look().flow());
        assertEquals(0.96, oil.look().opacity());
        assertEquals(0.1, oil.look().glow());
        assertEquals(0x0B0806, oil.look().fog().intValue());
        assertEquals(3, oil.look().frameTime());
        assertEquals(0.5, oil.physics().viscosity().speed());
        assertEquals(1.6, oil.physics().viscosity().gravity());
        assertEquals(1.0, oil.physics().viscosity().jump(), "not written: water's");
        assertEquals(new Spread(15, 5), oil.physics().spread());
        assertFalse(oil.physics().infiniteSource());
        assertEquals(FluidPhysics.MixedSource.WATER, oil.physics().mixedSource());
        assertEquals(2, oil.mixing().rules().size());
        assertEquals(FluidReaction.Outcome.FLOW, oil.mixing().reactionWith(Fluids.water(), ResourceTags.EMPTY).react(null, oil, Fluids.water()));
        assertEquals(2, oil.effects().size());
    }

    @Test
    void aResourceTypeInTheFluidCategoryIsAFluidTypeThatLivesInTanks() {
        CoreMachineEngine engine = start(map("resource-types", map(BRINE.asString(), map("category", "machineengine:fluid", "color", "#FEEF9C"))));

        FluidType brine = fluid(engine, BRINE);
        assertEquals(WorldMode.NONE, brine.world());
        assertEquals(0xFEEF9C, brine.color().intValue());
    }

    @Test
    void anInvalidFluidSectionSkipsThatFluidAndSaysWhy() {
        CoreMachineEngine engine = start(map("resource-types", map(
                OIL.asString(), map("fluid", map("world", "placeable", "look", map("opacity", 2.0))),
                BRINE.asString(), map("fluid", map("physics", map("viscosity", map("sped", 0.5)))),
                "pack:fluid/lava2", map("fluid", map("world", "vanilla")),
                "pack:fluid/acid", map("fluid", map("effects", list(map("type", "pack:unknown")))),
                "pack:fluid/slime", map("fluid", map("physics", map("spread", map("ticks", 2)))))));

        var types = engine.registries().registry(Registries.RESOURCE_TYPE);
        for (String key : List.of(OIL.asString(), BRINE.asString(), "pack:fluid/lava2", "pack:fluid/acid", "pack:fluid/slime")) {
            assertFalse(types.contains(Key.key(key)), key + " is skipped");
        }
        String all = String.join("\n", skipped(engine));
        assertTrue(all.contains("opacity must be between 0 and 1"), all);
        assertTrue(all.contains("unknown field physics.viscosity.sped"), all);
        assertTrue(all.contains("world must be none or placeable"), all);
        assertTrue(all.contains("pack:unknown is not a registered fluid effect type"), all);
        assertTrue(all.contains("ticks must be 5 or more"), all);
    }

    @Test
    void theBuiltInKindsAreRegistered() {
        CoreMachineEngine engine = TestEngine.builder().start();

        assertEquals(BuiltInFluidTypes.EFFECTS.size(), engine.registries().registry(Registries.FLUID_EFFECT_TYPE).keys().size());
        assertEquals(BuiltInFluidTypes.REACTIONS.size(), engine.registries().registry(Registries.FLUID_REACTION_TYPE).keys().size());
        assertEquals(BuiltInFluidTypes.TEXTURES.size(), engine.registries().registry(Registries.FLUID_TEXTURE_TYPE).keys().size());
    }

    @Test
    void fluidsYmlOverridesOnlyTheFieldsItWrites() {
        CoreMachineEngine engine = start(map("resource-types", map(OIL.asString(), map("fluid", map("world", "placeable",
                "look", map("opacity", 0.96), "physics", map("viscosity", map("speed", 0.5, "gravity", 1.6)))))));
        FluidType oil = fluid(engine, OIL);
        List<Diagnostic> problems = new ArrayList<>();

        FluidOverrides overrides = FluidOverrides.parse(map("fluids", map(OIL.asString(), map(
                "physics", map("viscosity", map("speed", 0.8)),
                "mixing", list(map("with", "any", "result", "flow"))))), engine.registries(), problems::add);

        assertTrue(problems.isEmpty(), String.valueOf(problems));
        assertEquals(Set.of(oil), overrides.overridden());
        assertEquals(0.8, overrides.physics(oil).viscosity().speed(), "overridden");
        assertEquals(1.6, overrides.physics(oil).viscosity().gravity(), "kept from the definition");
        assertEquals(0.96, overrides.look(oil).opacity(), "kept from the definition");
        assertEquals(1, overrides.mixing(oil).rules().size());
        assertInstanceOf(FluidMixing.Selector.Any.class, overrides.mixing(oil).rules().getFirst().with());
        assertEquals(0.5, oil.physics().viscosity().speed(), "the registered fluid never changes");
        assertSame(Fluids.water().physics(), overrides.physics(Fluids.water()), "no override: the definition's");
    }

    @Test
    void fluidsYmlCantChangeWorldOrBucketAndNamesBadEntries() {
        CoreMachineEngine engine = start(map("resource-types", map(OIL.asString(), map("fluid", map("world", "placeable")))));
        List<Diagnostic> problems = new ArrayList<>();

        FluidOverrides overrides = FluidOverrides.parse(map("fluids", map(
                OIL.asString(), map("world", "none"),
                "pack:fluid/nothing", map("look", map("opacity", 0.5)),
                "machineengine:energy", map("look", map("opacity", 0.5)))), engine.registries(), problems::add);

        assertTrue(overrides.overridden().isEmpty());
        String all = String.join("\n", problems.stream().map(Diagnostic::format).toList());
        assertTrue(all.contains("world can't be changed in fluids.yml"), all);
        assertTrue(all.contains("no fluid pack:fluid/nothing is registered"), all);
        assertTrue(all.contains("machineengine:energy is not a fluid"), all);
        assertTrue(all.contains("fluids.yml > fluids.pack:fluid/nothing"), "names where: " + all);
    }

    @Test
    void fluidsYmlIsWrittenOnFirstStartAndReloadsIntoWorldFluids(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws Exception {
        CoreMachineEngine engine = start(map("resource-types", map(OIL.asString(), map("fluid", map("world", "placeable",
                "physics", map("viscosity", map("speed", 0.5)))))));
        FluidType oil = fluid(engine, OIL);
        List<Diagnostic> problems = new ArrayList<>();
        fr.robie.machineengine.paper.fluid.FluidSettingsFile settings =
                fr.robie.machineengine.paper.fluid.FluidSettingsFile.install(folder, engine, problems);
        try {
            java.nio.file.Path file = folder.resolve("fluids.yml");
            String written = java.nio.file.Files.readString(file);
            assertTrue(written.contains("#  pack:fluid/oil:"), "every placeable fluid's values, commented: " + written);
            assertTrue(written.contains("viscosity: { speed: 0.5"), written);
            assertTrue(written.contains("fluids: {}"), written);
            assertTrue(problems.isEmpty(), String.valueOf(problems));
            assertEquals(0.5, fr.robie.machineengine.api.paper.WorldFluids.physics(oil).viscosity().speed());

            java.nio.file.Files.writeString(file, """
                    fluids:
                      pack:fluid/oil:
                        physics:
                          viscosity: { speed: 0.8 }
                    """);
            var report = settings.reload();

            assertTrue(report.diagnostics().isEmpty(), String.valueOf(report.diagnostics()));
            assertEquals(0.8, fr.robie.machineengine.api.paper.WorldFluids.physics(oil).viscosity().speed(), "applies at once");
            assertEquals(1, settings.current().overridden().size());

            java.nio.file.Files.writeString(file, "fluids: [ not: a map");
            assertTrue(settings.reload().hasErrors());
            assertEquals(0.8, fr.robie.machineengine.api.paper.WorldFluids.physics(oil).viscosity().speed(), "unreadable: the previous overrides stay");
        } finally {
            fr.robie.machineengine.paper.fluid.FluidSettingsFile.uninstall();
        }
    }

    @Test
    void theBuiltInTexturesDrawTilingFrames() throws Exception {
        FluidTexture.Context context = new FluidTexture.Context() {
            @Override
            public boolean flowing() {
                return false;
            }

            @Override
            public List<BufferedImage> vanilla(String path, int size) {
                BufferedImage gray = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) gray.setRGB(x, y, 0xFF808080);
                return List.of(gray, gray);
            }

            @Override
            public List<BufferedImage> pack(Key texture, int size) {
                return List.of();
            }
        };
        List<BufferedImage> recolored = BuiltInFluidTypes.RECOLOR.parse(map("pattern", "lava", "from", "#000000", "to", "#FF0000"))
                .orElseThrow().frames(16, context);
        List<BufferedImage> crystals = BuiltInFluidTypes.CRYSTALS.parse(DataMap.EMPTY).orElseThrow().frames(16, context);
        List<BufferedImage> bubbles = BuiltInFluidTypes.BUBBLES.parse(DataMap.EMPTY).orElseThrow().frames(32, context);

        assertEquals(2, recolored.size());
        assertEquals(0x80, (recolored.getFirst().getRGB(3, 3) >> 16) & 0xFF, "half way from black to red");
        assertEquals(32, crystals.size());
        assertEquals(16, crystals.getFirst().getWidth());
        assertEquals(32, bubbles.getFirst().getWidth());
    }
}
