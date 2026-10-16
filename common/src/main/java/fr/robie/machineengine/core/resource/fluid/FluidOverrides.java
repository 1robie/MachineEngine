package fr.robie.machineengine.core.resource.fluid;

import fr.robie.machineengine.api.data.Codecs;
import fr.robie.machineengine.api.data.DataMap;
import fr.robie.machineengine.api.data.DataValue;
import fr.robie.machineengine.api.diagnostic.Diagnostic;
import fr.robie.machineengine.api.diagnostic.DiagnosticCodes;
import fr.robie.machineengine.api.error.Result;
import fr.robie.machineengine.api.registry.Registries;
import fr.robie.machineengine.api.registry.RegistryAccess;
import fr.robie.machineengine.api.resource.ResourceType;
import fr.robie.machineengine.api.resource.fluid.FluidEffect;
import fr.robie.machineengine.api.resource.fluid.FluidLook;
import fr.robie.machineengine.api.resource.fluid.FluidMixing;
import fr.robie.machineengine.api.resource.fluid.FluidPhysics;
import fr.robie.machineengine.api.resource.fluid.FluidType;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The server owner's overrides from {@code fluids.yml} (docs/FLUID_TYPES_PLAN.md §3): each fluid's
 * values in effect, its definition with the overridden fields on top. Immutable; a reload builds a
 * new one.
 *
 * <pre>{@code
 * fluids:
 *   mekanized:fluid/brine:          # any field of a fluid spec except world and bucket
 *     look: { opacity: 0.9 }
 *     physics: { viscosity: { speed: 0.8 } }
 * }</pre>
 */
public final class FluidOverrides {
    public static final FluidOverrides NONE = new FluidOverrides(Map.of());

    /** Registered fluid → a fluid object carrying its values in effect (never registered). */
    private final Map<FluidType, FluidType> effective;

    private FluidOverrides(Map<FluidType, FluidType> effective) {
        this.effective = effective;
    }

    /**
     * Reads {@code root} (the whole {@code fluids.yml}). An invalid entry is skipped with a warning
     * naming it; the others still apply.
     */
    public static @NotNull FluidOverrides parse(@NotNull DataMap root, @NotNull RegistryAccess registries,
                                                @NotNull Consumer<Diagnostic> report) {
        DataValue raw = root.get("fluids").orElse(null);
        if (raw == null) return NONE;
        if (!(raw instanceof DataMap fluids)) {
            report.accept(warning("fluids.yml > fluids", "must be a map of <fluid key>: <overrides>"));
            return NONE;
        }
        Map<FluidType, FluidType> effective = new IdentityHashMap<>();
        fluids.entries().forEach((name, spec) -> {
            String where = "fluids.yml > fluids." + name;
            Result<Key> key = Codecs.parseKey(name);
            if (key.isErr() || !name.contains(":")) {
                report.accept(warning(where, "'" + name + "' is not a fluid key (namespace:fluid/name)"));
                return;
            }
            ResourceType type = registries.registry(Registries.RESOURCE_TYPE).get(key.value()).orElse(null);
            if (!(type instanceof FluidType fluid)) {
                report.accept(warning(where, type == null ? "no fluid " + name + " is registered" : name + " is not a fluid"));
                return;
            }
            if (!(spec instanceof DataMap map)) {
                report.accept(warning(where, "must be a map of fields"));
                return;
            }
            Result<FluidType.Builder> applied = FluidSpecParser.apply(FluidType.builder(fluid.key()).copy(fluid), map, registries, fluid);
            if (applied.isErr()) {
                report.accept(warning(where, applied.error().message()));
                return;
            }
            try {
                effective.put(fluid, applied.value().build());
            } catch (IllegalArgumentException | IllegalStateException e) {
                report.accept(warning(where, e.getMessage()));
            }
        });
        return new FluidOverrides(effective);
    }

    /** The fluids with overrides. */
    public @NotNull Set<FluidType> overridden() {
        return this.effective.keySet();
    }

    public @NotNull FluidPhysics physics(@NotNull FluidType fluid) {
        return values(fluid).physics();
    }

    public @NotNull FluidLook look(@NotNull FluidType fluid) {
        return values(fluid).look();
    }

    public @NotNull FluidMixing mixing(@NotNull FluidType fluid) {
        return values(fluid).mixing();
    }

    public @NotNull List<FluidEffect> effects(@NotNull FluidType fluid) {
        return values(fluid).effects();
    }

    private FluidType values(FluidType fluid) {
        return this.effective.getOrDefault(fluid, fluid);
    }

    private static Diagnostic warning(String where, String message) {
        return Diagnostic.warning(DiagnosticCodes.DEFINITION_SKIPPED, "Skipped: " + message).location(where).build();
    }
}
