package fr.robie.machineengine.api.machine;

import fr.robie.machineengine.api.capability.CapabilityType;
import fr.robie.machineengine.api.world.RelativeSide;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * An extra capability a machine type exposes, besides the resource handlers of its ports.
 *
 * @param sides   faces it is exposed on; empty means "not sided" (any face, or no face)
 * @param factory creates the capability object once per machine instance
 */
public record CapabilityBinding<T>(@NotNull CapabilityType<T> type, @NotNull Set<RelativeSide> sides,
                                   @NotNull Function<MachineContext, ? extends T> factory) {

    public CapabilityBinding {
        Objects.requireNonNull(type);
        Objects.requireNonNull(factory);
        sides = sides.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(sides));
    }

    public static <T> @NotNull CapabilityBinding<T> of(@NotNull CapabilityType<T> type,
                                                     @NotNull Function<MachineContext, ? extends T> factory) {
        return new CapabilityBinding<>(type, Set.of(), factory);
    }
}
