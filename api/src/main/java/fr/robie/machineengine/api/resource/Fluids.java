package fr.robie.machineengine.api.resource;

import fr.robie.machineengine.api.MachineEngine;
import fr.robie.machineengine.api.MachineEngineKeys;
import fr.robie.machineengine.api.config.ConfigKey;
import fr.robie.machineengine.api.registry.Registries;
import fr.robie.machineengine.api.resource.fluid.FluidType;
import fr.robie.machineengine.api.resource.fluid.WorldMode;
import fr.robie.machineengine.api.resource.match.ResourcePredicate;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Fluids: one global category, {@code machineengine:fluid}, for every addon and definition (D29).
 * MachineEngine registers water and lava; addons create their own fluids in the same category with
 * {@link #create}, so a pipe or port accepting {@link #any()} moves every registered fluid. Fluid
 * keys are {@code <namespace>:fluid/<name>}.
 *
 * <p>Every fluid MachineEngine and addons make is a {@link FluidType} (D47): water and lava are
 * vanilla fluids, others live in tanks and pipes, or are placeable in the world too
 * ({@link FluidType#builder}).</p>
 *
 * <p>The unit, scale and bucket size are set in {@code config.yml} ({@code resources.fluid}) and read
 * when the fluids are built.</p>
 */
public final class Fluids {
    public static final Key CATEGORY_KEY = MachineEngineKeys.key("fluid");
    public static final Key WATER_KEY = MachineEngineKeys.key("fluid/water");
    public static final Key LAVA_KEY = MachineEngineKeys.key("fluid/lava");
    /** The world fluids, as {@code WorldFluid} and the {@code MachineWorld} fluid calls name them. */
    public static final Key WORLD_WATER = Key.key(Key.MINECRAFT_NAMESPACE, "water");
    public static final Key WORLD_LAVA = Key.key(Key.MINECRAFT_NAMESPACE, "lava");

    public static final ConfigKey<String> UNIT = ConfigKey.string("resources.fluid.unit", "mB")
            .comment("Unit shown after fluid amounts.").restartOnly();
    public static final ConfigKey<Integer> SCALE = ConfigKey.integer("resources.fluid.scale", 0, 0, 18)
            .comment("Decimal digits of fluid amounts.").restartOnly();
    public static final ConfigKey<Long> BUCKET = ConfigKey.whole("resources.fluid.bucket", 1000, 1, 1_000_000_000L)
            .comment("Amount of fluid in one bucket (and one source block).").restartOnly();
    /** Vanilla's default water color; the menus' tint of water. */
    public static final ConfigKey<Integer> WATER_COLOR = ConfigKey.color("resources.fluid.water-color", 0x3F76E4)
            .comment("Color of water in menus, \"#RRGGBB\" (quote it).").restartOnly();
    public static final ConfigKey<Integer> LAVA_COLOR = ConfigKey.color("resources.fluid.lava-color", 0xE0641A)
            .comment("Color of lava in menus, \"#RRGGBB\" (quote it).").restartOnly();

    private Fluids() {
    }

    /** The category {@code machineengine:fluid}, with the configured unit and scale. */
    public static @NotNull ResourceCategory category() {
        return BuiltInResources.get().fluidCategory();
    }

    /** {@code machineengine:fluid/water}. */
    public static @NotNull FluidType water() {
        return BuiltInResources.get().water();
    }

    /** {@code machineengine:fluid/lava}. */
    public static @NotNull FluidType lava() {
        return BuiltInResources.get().lava();
    }

    /** The amount of fluid in one bucket. */
    public static long bucket() {
        return BuiltInResources.get().bucket();
    }

    /** Matches every fluid. */
    public static @NotNull ResourcePredicate any() {
        return ResourcePredicate.category(category());
    }

    /**
     * A new fluid in the global category that lives only in tanks, pipes and items, e.g.
     * {@code Fluids.create(Key.key("mymod", "fluid/oil"))}. Register it with
     * {@code context.resourceTypes().register(...)} like any resource type. For a fluid that can be
     * placed in the world, use {@link FluidType#builder}.
     */
    public static @NotNull FluidType create(@NotNull Key key) {
        return FluidType.builder(key).build();
    }

    /** A new fluid with a menu color ({@code 0xRRGGBB}), as {@link #create(Key)}. */
    public static @NotNull FluidType create(@NotNull Key key, int color) {
        return FluidType.builder(key).color(color).build();
    }

    /** Whether {@code type} is a fluid: a {@link FluidType}, or a plain type in the global fluid category (older addons). */
    public static boolean isFluid(@NotNull ResourceType type) {
        return type instanceof FluidType || type.category() == category();
    }

    /** {@code type} as a {@link FluidType}, or {@code null} if it is not one. */
    public static @Nullable FluidType asFluid(@NotNull ResourceType type) {
        return type instanceof FluidType fluid ? fluid : null;
    }

    /** Whether {@code key} is MachineEngine's water or lava. */
    public static boolean isVanillaKey(@NotNull Key key) {
        return key.equals(WATER_KEY) || key.equals(LAVA_KEY);
    }

    /**
     * The world fluid of a fluid, as {@code WorldFluid} and the {@code MachineWorld} fluid calls name
     * them: {@code minecraft:water} or {@code minecraft:lava} for those, the fluid's own key for a
     * placeable fluid (D46), {@code null} for fluids with no block.
     */
    public static @Nullable Key worldFluid(@NotNull ResourceType type) {
        if (type == water()) return WORLD_WATER;
        if (type == lava()) return WORLD_LAVA;
        if (type instanceof FluidType fluid && fluid.world() == WorldMode.PLACEABLE) return fluid.key();
        return null;
    }

    /**
     * The fluid of a world fluid: water or lava for {@code minecraft:water} / {@code minecraft:lava},
     * the placeable fluid registered under that key, {@code null} for others (or while MachineEngine
     * is not running).
     */
    public static @Nullable ResourceType fromWorld(@NotNull Key worldFluid) {
        if (worldFluid.equals(WORLD_WATER)) return water();
        if (worldFluid.equals(WORLD_LAVA)) return lava();
        MachineEngine engine;
        try {
            engine = MachineEngine.get();
        } catch (IllegalStateException notRunning) {
            return null;
        }
        return engine.registries().registry(Registries.RESOURCE_TYPE).get(worldFluid)
                .filter(type -> type instanceof FluidType fluid && fluid.world() == WorldMode.PLACEABLE)
                .orElse(null);
    }
}
