package fr.robie.machineengine.api.resource.fluid;

import fr.robie.machineengine.api.resource.Fluids;
import fr.robie.machineengine.api.resource.ResourceCategory;
import fr.robie.machineengine.api.resource.ResourceComponents;
import fr.robie.machineengine.api.resource.ResourceType;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A fluid: a {@link ResourceType} in the global fluid category (D29) that also says how it exists in
 * the world, looks, moves, mixes and affects entities (D47, docs/FLUID_TYPES_PLAN.md).
 *
 * <p>Registered like any resource type ({@code context.resourceTypes().register(fluid)}), so tanks,
 * pipes and recipes use it unchanged. Identity is the registered object (D9).</p>
 *
 * <pre>{@code
 * FluidType oil = FluidType.builder(Key.key("mymod", "fluid/oil"))
 *         .color(0x2A2238)
 *         .placeable()
 *         .opacity(0.96)
 *         .viscosity(new Viscosity(0.5, 1.6, 0.5, 0, 0.35, 0.3))
 *         .spread(new Spread(15, 5))
 *         .build();
 * }</pre>
 *
 * <p>The values here are the fluid's definition (code or definitions YAML). A server owner can
 * override them in {@code fluids.yml}; the values in effect are read from
 * {@link fr.robie.machineengine.api.paper.WorldFluids}.</p>
 */
public final class FluidType extends ResourceType {
    private final WorldMode world;
    private final FluidLook look;
    private final FluidPhysics physics;
    private final FluidMixing mixing;
    private final List<FluidEffect> effects;
    private final FluidItems items;

    private FluidType(Builder builder) {
        super(builder.key, builder.category != null ? builder.category : Fluids.category(), builder.components, builder.color);
        this.world = builder.world;
        this.look = builder.look();
        this.physics = new FluidPhysics(builder.viscosity, builder.spread, builder.infiniteSource, builder.mixedSource);
        this.mixing = new FluidMixing(builder.rules);
        this.effects = List.copyOf(builder.effects);
        this.items = builder.items != null ? builder.items
                : builder.world == WorldMode.PLACEABLE ? FluidItems.AUTO : FluidItems.NONE;
    }

    /** A fluid with key {@code key} (e.g. {@code mymod:fluid/oil}): only in tanks until {@link Builder#placeable()}. */
    public static @NotNull Builder builder(@NotNull Key key) {
        return new Builder(key);
    }

    /** Where it exists outside machines and pipes. */
    public @NotNull WorldMode world() {
        return this.world;
    }

    /** Whether it can be placed and flow in the world. */
    public boolean placeable() {
        return this.world == WorldMode.PLACEABLE;
    }

    public @NotNull FluidLook look() {
        return this.look;
    }

    public @NotNull FluidPhysics physics() {
        return this.physics;
    }

    public @NotNull FluidMixing mixing() {
        return this.mixing;
    }

    public @NotNull List<FluidEffect> effects() {
        return this.effects;
    }

    public @NotNull FluidItems items() {
        return this.items;
    }

    @Override
    public String toString() {
        return "FluidType[" + key().asString() + ", " + this.world + "]";
    }

    public static final class Builder {
        private final Key key;
        private @Nullable ResourceCategory category;
        private @Nullable Integer color;
        private ResourceComponents components = ResourceComponents.EMPTY;
        private WorldMode world = WorldMode.NONE;
        private @Nullable FluidTexture still;
        private @Nullable FluidTexture flow;
        private @Nullable Integer tint;
        private double opacity = FluidLook.TINTED_WATER.opacity();
        private double glow;
        private @Nullable Integer fog;
        private int frameTime = FluidLook.TINTED_WATER.frameTime();
        private Viscosity viscosity = Viscosity.WATER;
        private Spread spread = Spread.WATER;
        private boolean infiniteSource = true;
        private FluidPhysics.MixedSource mixedSource = FluidPhysics.MixedSource.NONE;
        private final List<FluidMixing.Rule> rules = new ArrayList<>();
        private final List<FluidEffect> effects = new ArrayList<>();
        private @Nullable FluidItems items;

        private Builder(Key key) {
            this.key = Objects.requireNonNull(key);
        }

        /**
         * The fluid category to use instead of {@link Fluids#category()}: only while that category is
         * being built (MachineEngine's water and lava).
         */
        @ApiStatus.Internal
        public @NotNull Builder category(@NotNull ResourceCategory value) {
            this.category = Objects.requireNonNull(value);
            return this;
        }

        /**
         * Every value of {@code fluid} except its key: to make a fluid like another, or to apply a
         * server owner's overrides on top of a registered fluid.
         */
        public @NotNull Builder copy(@NotNull FluidType fluid) {
            this.category = fluid.category();
            this.color = fluid.color();
            this.components = fluid.defaultComponents();
            this.world = fluid.world();
            look(fluid.look());
            physics(fluid.physics());
            mixing(fluid.mixing());
            this.effects.clear();
            this.effects.addAll(fluid.effects());
            this.items = fluid.items();
            return this;
        }

        /** Removes every effect. */
        public @NotNull Builder clearEffects() {
            this.effects.clear();
            return this;
        }

        /** The menu color ({@code 0xRRGGBB}), also the look of a fluid without a texture. */
        public @NotNull Builder color(int value) {
            this.color = value & 0xFFFFFF;
            return this;
        }

        public @NotNull Builder components(@NotNull ResourceComponents value) {
            this.components = Objects.requireNonNull(value);
            return this;
        }

        public @NotNull Builder world(@NotNull WorldMode value) {
            this.world = Objects.requireNonNull(value);
            return this;
        }

        /** {@code world(PLACEABLE)}: it can be placed and flows in the world. */
        public @NotNull Builder placeable() {
            return world(WorldMode.PLACEABLE);
        }

        /** The look at once (replaces textures, tint, opacity, glow, fog and frame time). */
        public @NotNull Builder look(@NotNull FluidLook value) {
            this.still = value.still();
            this.flow = value.flow();
            this.tint = value.tint();
            this.opacity = value.opacity();
            this.glow = value.glow();
            this.fog = value.fog();
            this.frameTime = value.frameTime();
            return this;
        }

        public @NotNull Builder still(@Nullable FluidTexture value) {
            this.still = value;
            return this;
        }

        public @NotNull Builder flow(@Nullable FluidTexture value) {
            this.flow = value;
            return this;
        }

        /** One texture for still and flowing. */
        public @NotNull Builder texture(@Nullable FluidTexture value) {
            this.still = value;
            this.flow = value;
            return this;
        }

        public @NotNull Builder tint(int value) {
            this.tint = value & 0xFFFFFF;
            return this;
        }

        public @NotNull Builder opacity(double value) {
            this.opacity = value;
            return this;
        }

        public @NotNull Builder glow(double value) {
            this.glow = value;
            return this;
        }

        public @NotNull Builder fog(int value) {
            this.fog = value & 0xFFFFFF;
            return this;
        }

        public @NotNull Builder frameTime(int value) {
            this.frameTime = value;
            return this;
        }

        /** The physics at once (replaces viscosity, spread and both source rules). */
        public @NotNull Builder physics(@NotNull FluidPhysics value) {
            this.viscosity = value.viscosity();
            this.spread = value.spread();
            this.infiniteSource = value.infiniteSource();
            this.mixedSource = value.mixedSource();
            return this;
        }

        public @NotNull Builder viscosity(@NotNull Viscosity value) {
            this.viscosity = Objects.requireNonNull(value);
            return this;
        }

        public @NotNull Builder spread(@NotNull Spread value) {
            this.spread = Objects.requireNonNull(value);
            return this;
        }

        public @NotNull Builder infiniteSource(boolean value) {
            this.infiniteSource = value;
            return this;
        }

        public @NotNull Builder mixedSource(@NotNull FluidPhysics.MixedSource value) {
            this.mixedSource = Objects.requireNonNull(value);
            return this;
        }

        /** Adds a mixing rule; the first matching rule decides. */
        public @NotNull Builder mix(@NotNull FluidMixing.Selector with, @NotNull FluidReaction reaction) {
            this.rules.add(new FluidMixing.Rule(with, reaction));
            return this;
        }

        public @NotNull Builder mixing(@NotNull FluidMixing value) {
            this.rules.clear();
            this.rules.addAll(value.rules());
            return this;
        }

        public @NotNull Builder effect(@NotNull FluidEffect value) {
            this.effects.add(Objects.requireNonNull(value));
            return this;
        }

        public @NotNull Builder items(@NotNull FluidItems value) {
            this.items = Objects.requireNonNull(value);
            return this;
        }

        public @NotNull Builder bucket(@NotNull FluidItems.Bucket value) {
            return items(new FluidItems(value));
        }

        private FluidLook look() {
            return new FluidLook(this.still, this.flow, this.tint, this.opacity, this.glow, this.fog, this.frameTime);
        }

        /**
         * @throws IllegalArgumentException if a value is out of range
         * @throws IllegalStateException    if a VANILLA fluid is built here (only MachineEngine's water and lava are)
         */
        public @NotNull FluidType build() {
            if (this.world == WorldMode.VANILLA && !Fluids.isVanillaKey(this.key)) {
                throw new IllegalStateException("only water and lava are vanilla fluids: " + this.key.asString());
            }
            return new FluidType(this);
        }
    }
}
