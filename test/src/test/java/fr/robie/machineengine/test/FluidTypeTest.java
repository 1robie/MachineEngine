package fr.robie.machineengine.test;

import fr.robie.machineengine.api.MachineEngineAccess;
import fr.robie.machineengine.api.error.Result;
import fr.robie.machineengine.api.registry.Registries;
import fr.robie.machineengine.api.resource.Fluids;
import fr.robie.machineengine.api.resource.ResourceType;
import fr.robie.machineengine.api.resource.fluid.FluidEffectType;
import fr.robie.machineengine.api.resource.fluid.FluidItems;
import fr.robie.machineengine.api.resource.fluid.FluidLook;
import fr.robie.machineengine.api.resource.fluid.FluidMixing;
import fr.robie.machineengine.api.resource.fluid.FluidPhysics;
import fr.robie.machineengine.api.resource.fluid.FluidReaction;
import fr.robie.machineengine.api.resource.fluid.FluidType;
import fr.robie.machineengine.api.resource.fluid.Spread;
import fr.robie.machineengine.api.resource.fluid.Viscosity;
import fr.robie.machineengine.api.resource.fluid.WorldMode;
import fr.robie.machineengine.api.resource.tag.ResourceTags;
import fr.robie.machineengine.core.lifecycle.CoreMachineEngine;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fluid types (D47, docs/FLUID_TYPES_PLAN.md stage 1). */
class FluidTypeTest {
    private static final Key OIL = Key.key("mymod", "fluid/oil");
    private static final Key BRINE = Key.key("mymod", "fluid/brine");

    @Test
    void aFluidBuiltWithNothingSetLivesOnlyInTanksAndLooksLikeTintedWater() {
        FluidType oil = FluidType.builder(OIL).color(0x2A2238).build();

        assertSame(Fluids.category(), oil.category());
        assertEquals(WorldMode.NONE, oil.world());
        assertFalse(oil.placeable());
        assertEquals(FluidLook.TINTED_WATER, oil.look());
        assertFalse(oil.look().textured());
        assertEquals(0x2A2238, oil.look().tintFor(oil.color()), "no texture: water tinted with the fluid's color");
        assertEquals(FluidPhysics.WATER, oil.physics());
        assertEquals(FluidMixing.STOP, oil.mixing());
        assertTrue(oil.effects().isEmpty());
        assertEquals(FluidItems.NONE, oil.items());
    }

    @Test
    void aPlaceableFluidGetsABucketUnlessItSaysOtherwise() {
        assertEquals(FluidItems.AUTO, FluidType.builder(OIL).placeable().build().items());
        assertEquals(FluidItems.NONE, FluidType.builder(OIL).placeable().items(FluidItems.NONE).build().items());
        assertEquals(new FluidItems(new FluidItems.Bucket.Item(Key.key("mymod", "oil_bucket"))),
                FluidType.builder(OIL).placeable().bucket(new FluidItems.Bucket.Item(Key.key("mymod", "oil_bucket"))).build().items());
    }

    @Test
    void theBuilderKeepsEveryValue() {
        FluidReaction calcite = (at, incoming, present) -> FluidReaction.Outcome.STOP;
        FluidType oil = FluidType.builder(OIL).color(0x2A2238).placeable()
                .tint(0xFFFFFF).opacity(0.96).glow(0.2).fog(0x0B0806).frameTime(3)
                .viscosity(new Viscosity(0.5, 1.6, 0.5, 0, 0.35, 0.3)).spread(new Spread(15, 5))
                .infiniteSource(false).mixedSource(FluidPhysics.MixedSource.WATER)
                .mix(new FluidMixing.Selector.Fluid(BRINE), calcite)
                .effect((entity, fluid, ticks) -> { })
                .build();

        assertEquals(0.96, oil.look().opacity());
        assertEquals(0.2, oil.look().glow());
        assertEquals(0x0B0806, oil.look().fogFor(oil.color()));
        assertEquals(3, oil.look().frameTime());
        assertEquals(0.5, oil.physics().viscosity().speed());
        assertEquals(new Spread(15, 5), oil.physics().spread());
        assertFalse(oil.physics().infiniteSource());
        assertEquals(FluidPhysics.MixedSource.WATER, oil.physics().mixedSource());
        assertEquals(1, oil.mixing().rules().size());
        assertEquals(1, oil.effects().size());
    }

    @Test
    void valuesOutOfRangeAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new Viscosity(0, 1, 1, 0, 0, 0), "speed 0");
        assertThrows(IllegalArgumentException.class, () -> new Viscosity(1, 1, 1, 0, 1, 0), "damping above 0.9");
        assertThrows(IllegalArgumentException.class, () -> new Spread(4, 7), "faster than water");
        assertThrows(IllegalArgumentException.class, () -> new Spread(5, 8), "farther than water");
        assertThrows(IllegalArgumentException.class, () -> FluidType.builder(OIL).opacity(1.5).build());
        assertThrows(IllegalStateException.class, () -> FluidType.builder(OIL).world(WorldMode.VANILLA).build(),
                "only water and lava are vanilla fluids");
    }

    @Test
    void identityIsTheObjectEvenWithTheSameKey() {
        FluidType one = FluidType.builder(OIL).build();
        FluidType two = FluidType.builder(OIL).build();

        assertNotEquals(one, two);
        assertEquals(one, one);
        assertNotEquals(one.hashCode(), two.hashCode());
    }

    @Test
    void waterAndLavaAreVanillaFluidTypesRegisteredByTheEngine() {
        CoreMachineEngine engine = TestEngine.builder().start();
        var types = engine.registries().registry(Registries.RESOURCE_TYPE);

        assertInstanceOf(FluidType.class, types.getOrThrow(Fluids.WATER_KEY));
        assertSame(Fluids.water(), types.getOrThrow(Fluids.WATER_KEY));
        assertEquals(WorldMode.VANILLA, Fluids.water().world());
        assertEquals(WorldMode.VANILLA, Fluids.lava().world());
        assertEquals(Fluids.WORLD_WATER, Fluids.worldFluid(Fluids.water()));
        assertEquals(Fluids.WORLD_LAVA, Fluids.worldFluid(Fluids.lava()));
        assertSame(Fluids.water(), Fluids.fromWorld(Fluids.WORLD_WATER));
    }

    @Test
    void anAddonRegistersAPlaceableFluidLikeAnyResourceType() {
        FluidType oil = FluidType.builder(OIL).placeable().build();
        FluidType inTanks = Fluids.create(BRINE);
        CoreMachineEngine engine = TestEngine.builder().addon("mymod", ctx -> {
            ctx.resourceTypes().register(oil);
            ctx.resourceTypes().register(inTanks);
        }).start();

        assertSame(oil, engine.registries().registry(Registries.RESOURCE_TYPE).getOrThrow(OIL));
        assertTrue(Fluids.isFluid(oil));
        assertEquals(OIL, Fluids.worldFluid(oil), "a placeable fluid is its own world fluid");
        assertNull(Fluids.worldFluid(inTanks), "a fluid that only lives in tanks has no block");
        MachineEngineAccess.install(engine);
        try {
            assertSame(oil, Fluids.fromWorld(OIL));
            assertNull(Fluids.fromWorld(BRINE), "not placeable");
        } finally {
            MachineEngineAccess.install(null);
        }
        assertNull(Fluids.fromWorld(OIL), "MachineEngine not running: only water and lava are known");
    }

    @Test
    void anOldAddonsPlainTypeInTheFluidCategoryIsStillAFluid() {
        ResourceType juice = ResourceType.of(Key.key("oldmod", "fluid/juice"), Fluids.category());

        assertTrue(Fluids.isFluid(juice));
        assertNull(Fluids.asFluid(juice));
        assertNull(Fluids.worldFluid(juice));
    }

    @Test
    void theFirstMatchingMixingRuleDecides() {
        FluidReaction replace = (at, incoming, present) -> FluidReaction.Outcome.FLOW;
        FluidReaction toBlock = (at, incoming, present) -> FluidReaction.Outcome.STOP;
        FluidType brine = Fluids.create(BRINE);
        FluidType oil = FluidType.builder(OIL)
                .mix(new FluidMixing.Selector.Fluid(Fluids.WATER_KEY), replace)
                .mix(new FluidMixing.Selector.Tag(Key.key("mymod", "salty")), toBlock)
                .build();
        ResourceTags tags = ResourceTags.of(Map.of(Key.key("mymod", "salty"), Set.of(brine)));

        assertSame(replace, oil.mixing().reactionWith(Fluids.water(), tags));
        assertSame(toBlock, oil.mixing().reactionWith(brine, tags));
        assertSame(FluidReaction.STOP, oil.mixing().reactionWith(Fluids.lava(), tags), "no rule: both stop");
    }

    @Test
    void addonsRegisterFluidEffectTypesInTheirRegistry() {
        FluidEffectType burn = FluidEffectType.of(Key.key("mymod", "burn"),
                config -> Result.ok((entity, fluid, ticks) -> entity.setFireTicks(40)));
        CoreMachineEngine engine = TestEngine.builder().addon("mymod", ctx -> ctx.fluidEffectTypes().register(burn)).start();

        assertSame(burn, engine.registries().registry(Registries.FLUID_EFFECT_TYPE).getOrThrow(Key.key("mymod", "burn")));
        assertTrue(engine.registries().registry(Registries.FLUID_REACTION_TYPE).keys().isEmpty());
        assertTrue(engine.registries().registry(Registries.FLUID_TEXTURE_TYPE).keys().isEmpty());
    }

    @Test
    void aTexturedFluidIsWhiteUnlessItHasATint() {
        FluidLook textured = FluidType.builder(OIL).color(0x123456).texture((size, context) -> java.util.List.of()).build().look();

        assertTrue(textured.textured());
        assertEquals(0xFFFFFF, textured.tintFor(0x123456), "the texture has its own colors");
        assertEquals(0xABCDEF, textured.withTint(0xABCDEF).tintFor(0x123456));
    }
}
