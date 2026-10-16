package fr.robie.machineengine.api.resource;

import fr.robie.machineengine.api.MachineEngine;
import fr.robie.machineengine.api.config.ConfigValues;
import fr.robie.machineengine.api.resource.fluid.FluidType;
import fr.robie.machineengine.api.resource.fluid.WorldMode;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * The resources MachineEngine provides to every addon and definition: {@link Energy} and
 * {@link Fluids} (D29). Their units, scales and bucket size come from {@code config.yml}, so they
 * are built once, from the config, the first time they are needed; addons read them through the
 * getters of {@link Energy} and {@link Fluids}.
 */
@ApiStatus.Internal
public final class BuiltInResources {
    private static volatile @Nullable Built built;

    private BuiltInResources() {
    }

    /** The resources, units, scales and bucket size built from {@code config}. */
    public record Built(@NotNull ResourceCategory energyCategory, @NotNull ResourceType energy,
                        @NotNull ResourceCategory fluidCategory, @NotNull FluidType water, @NotNull FluidType lava, long bucket) {
        boolean sameSettings(Built other) {
            return same(this.energyCategory, other.energyCategory) && same(this.fluidCategory, other.fluidCategory) && this.bucket == other.bucket
                    && Objects.equals(this.water.color(), other.water.color()) && Objects.equals(this.lava.color(), other.lava.color());
        }

        private static boolean same(ResourceCategory a, ResourceCategory b) {
            return a.unit().equals(b.unit()) && a.scale() == b.scale();
        }
    }

    /** Builds new resources from {@code config} without installing them (tests compare settings with it). */
    public static @NotNull Built create(@NotNull ConfigValues config) {
        ResourceCategory energyCategory = ResourceCategory.builder(Energy.KEY)
                .unit(config.get(Energy.UNIT)).scale(config.get(Energy.SCALE)).build();
        ResourceCategory fluidCategory = ResourceCategory.builder(Fluids.CATEGORY_KEY)
                .unit(config.get(Fluids.UNIT)).scale(config.get(Fluids.SCALE)).build();
        return new Built(energyCategory, ResourceType.of(Energy.KEY, energyCategory), fluidCategory,
                vanilla(Fluids.WATER_KEY, fluidCategory, config.get(Fluids.WATER_COLOR)),
                vanilla(Fluids.LAVA_KEY, fluidCategory, config.get(Fluids.LAVA_COLOR)),
                config.get(Fluids.BUCKET));
    }

    private static FluidType vanilla(Key key, ResourceCategory category, int color) {
        return FluidType.builder(key).category(category).color(color).world(WorldMode.VANILLA).build();
    }

    /**
     * Builds the resources from the loaded {@code config.yml}. MachineEngine calls it in {@code onLoad},
     * right after loading the config and before any addon loads.
     *
     * @throws IllegalStateException if they were already built with other settings: registries
     *                               cannot change while the server runs
     */
    public static synchronized @NotNull Built install(@NotNull ConfigValues config) {
        Built wanted = create(config);
        Built current = built;
        if (current == null) {
            built = wanted;
            return wanted;
        }
        if (!current.sameSettings(wanted)) {
            throw new IllegalStateException("MachineEngine's resources were already built with other units or bucket size;"
                    + " restart the server to apply config.yml's resources section");
        }
        return current;
    }

    /**
     * The built resources. Built from {@link MachineEngine#config()} on first use: on a server that is
     * the loaded {@code config.yml}; without a server, the defaults.
     */
    public static @NotNull Built get() {
        Built current = built;
        if (current != null) return current;
        synchronized (BuiltInResources.class) {
            if (built == null) built = create(MachineEngine.config());
            return Objects.requireNonNull(built);
        }
    }
}
