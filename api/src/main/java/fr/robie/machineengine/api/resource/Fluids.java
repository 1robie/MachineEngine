package fr.robie.machineengine.api.resource;

import fr.robie.machineengine.api.MachineEngineKeys;
import fr.robie.machineengine.api.config.ConfigKey;
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

    private Fluids() {
    }

    /** The category {@code machineengine:fluid}, with the configured unit and scale. */
    public static @NotNull ResourceCategory category() {
        return BuiltInResources.get().fluidCategory();
    }

    /** {@code machineengine:fluid/water}. */
    public static @NotNull ResourceType water() {
        return BuiltInResources.get().water();
    }

    /** {@code machineengine:fluid/lava}. */
    public static @NotNull ResourceType lava() {
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
     * A new fluid in the global category, e.g. {@code Fluids.create(Key.key("mymod", "fluid/oil"))}.
     * Register it with {@code context.resourceTypes().register(...)} like any resource type.
     */
    public static @NotNull ResourceType create(@NotNull Key key) {
        return ResourceType.of(key, category());
    }

    /** Whether {@code type} is in the global fluid category. */
    public static boolean isFluid(@NotNull ResourceType type) {
        return type.category() == category();
    }

    /** The world fluid ({@code minecraft:water} or {@code minecraft:lava}) of a fluid; {@code null} for fluids with no block. */
    public static @Nullable Key worldFluid(@NotNull ResourceType type) {
        if (type == water()) return WORLD_WATER;
        if (type == lava()) return WORLD_LAVA;
        return null;
    }

    /** The fluid of a world fluid ({@code minecraft:water} or {@code minecraft:lava}); {@code null} for others. */
    public static @Nullable ResourceType fromWorld(@NotNull Key worldFluid) {
        if (worldFluid.equals(WORLD_WATER)) return water();
        if (worldFluid.equals(WORLD_LAVA)) return lava();
        return null;
    }
}
