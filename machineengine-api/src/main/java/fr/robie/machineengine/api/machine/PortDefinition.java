package fr.robie.machineengine.api.machine;

import fr.robie.machineengine.api.resource.match.ResourcePredicate;
import fr.robie.machineengine.api.world.RelativeSide;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * A configured exposure of one of the machine's storages on some faces (ARCHITECTURE.md §11.2).
 * Other machines, transports and hoppers reach the storage only through ports.
 *
 * @param id          unique within the machine type
 * @param storage     the {@link StorageDefinition#id() storage} exposed
 * @param direction   what callers may do through it
 * @param sides       faces it is exposed on, relative to the machine's facing
 * @param filter      what may pass, on top of the storage's own filter
 * @param ratePerTick most that may pass per game tick ({@link Long#MAX_VALUE}: unlimited);
 *                    scaled by the {@code machineengine:transfer_rate} modifier
 */
public record PortDefinition(@NotNull String id, @NotNull String storage, @NotNull PortDirection direction,
                             @NotNull Set<RelativeSide> sides, @NotNull ResourcePredicate filter, long ratePerTick) {

    public PortDefinition {
        Objects.requireNonNull(id);
        Objects.requireNonNull(storage);
        Objects.requireNonNull(direction);
        Objects.requireNonNull(filter);
        sides = sides.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(sides));
    }

    public static @NotNull Builder builder(@NotNull String id, @NotNull String storage) {
        return new Builder(id, storage);
    }

    public static final class Builder {
        private final String id;
        private final String storage;
        private PortDirection direction = PortDirection.INPUT_OUTPUT;
        private Set<RelativeSide> sides = EnumSet.allOf(RelativeSide.class);
        private ResourcePredicate filter = ResourcePredicate.any();
        private long ratePerTick = Long.MAX_VALUE;

        private Builder(String id, String storage) {
            this.id = Objects.requireNonNull(id);
            this.storage = Objects.requireNonNull(storage);
        }

        public @NotNull Builder direction(@NotNull PortDirection direction) {
            this.direction = Objects.requireNonNull(direction);
            return this;
        }

        public @NotNull Builder sides(@NotNull RelativeSide first, @NotNull RelativeSide... more) {
            this.sides = EnumSet.of(first, more);
            return this;
        }

        public @NotNull Builder filter(@NotNull ResourcePredicate filter) {
            this.filter = Objects.requireNonNull(filter);
            return this;
        }

        public @NotNull Builder ratePerTick(long ratePerTick) {
            this.ratePerTick = ratePerTick;
            return this;
        }

        public @NotNull PortDefinition build() {
            return new PortDefinition(this.id, this.storage, this.direction, this.sides, this.filter, this.ratePerTick);
        }
    }
}
