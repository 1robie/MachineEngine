package fr.robie.machineengine.api.resource;

import fr.robie.machineengine.api.data.DataValue;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable component values of a resource variant (ARCHITECTURE.md §9.2).
 *
 * <p>Components whose type is no longer registered are kept as raw {@linkplain #unknown() data}
 * and written back unchanged (D3). They take part in equality, so such a variant never stacks with
 * anything else.</p>
 */
public final class ResourceComponents {
    public static final ResourceComponents EMPTY = new ResourceComponents(Map.of(), Map.of());

    private final Map<ResourceComponentType<?>, Object> values;
    private final Map<Key, DataValue> unknown;

    private ResourceComponents(Map<ResourceComponentType<?>, Object> values, Map<Key, DataValue> unknown) {
        this.values = values;
        this.unknown = unknown;
    }

    public static @NotNull Builder builder() {
        return new Builder(Map.of(), Map.of());
    }

    public static <T> @NotNull ResourceComponents of(@NotNull ResourceComponentType<T> type, @NotNull T value) {
        return builder().set(type, value).build();
    }

    public <T> @Nullable T get(@NotNull ResourceComponentType<T> type) {
        Object value = this.values.get(type);
        return value == null ? null : type.cast(value);
    }

    public <T> @NotNull Optional<T> find(@NotNull ResourceComponentType<T> type) {
        return Optional.ofNullable(get(type));
    }

    public boolean has(@NotNull ResourceComponentType<?> type) {
        return this.values.containsKey(type);
    }

    /** Known component types, sorted by key for deterministic encoding. */
    public @NotNull Set<ResourceComponentType<?>> types() {
        return this.values.keySet();
    }

    /** Raw data of components whose type is not registered, by component key. */
    public @NotNull Map<Key, DataValue> unknown() {
        return this.unknown;
    }

    public boolean isEmpty() {
        return this.values.isEmpty() && this.unknown.isEmpty();
    }

    public <T> @NotNull ResourceComponents with(@NotNull ResourceComponentType<T> type, @NotNull T value) {
        return toBuilder().set(type, value).build();
    }

    public @NotNull ResourceComponents without(@NotNull ResourceComponentType<?> type) {
        return has(type) ? toBuilder().remove(type).build() : this;
    }

    public @NotNull Builder toBuilder() {
        return new Builder(this.values, this.unknown);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ResourceComponents components
                && this.values.equals(components.values)
                && this.unknown.equals(components.unknown);
    }

    @Override
    public int hashCode() {
        return 31 * this.values.hashCode() + this.unknown.hashCode();
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder("{");
        this.values.forEach((type, value) -> {
            if (builder.length() > 1) builder.append(", ");
            builder.append(type.key().asString()).append('=').append(value);
        });
        this.unknown.forEach((key, value) -> {
            if (builder.length() > 1) builder.append(", ");
            builder.append(key.asString()).append("=<unknown>");
        });
        return builder.append('}').toString();
    }

    public static final class Builder {
        private final Map<ResourceComponentType<?>, Object> values;
        private final Map<Key, DataValue> unknown;

        private Builder(Map<ResourceComponentType<?>, Object> values, Map<Key, DataValue> unknown) {
            this.values = new LinkedHashMap<>(values);
            this.unknown = new LinkedHashMap<>(unknown);
        }

        public <T> @NotNull Builder set(@NotNull ResourceComponentType<T> type, @NotNull T value) {
            this.values.put(type, type.cast(value));
            this.unknown.remove(type.key());
            return this;
        }

        public @NotNull Builder remove(@NotNull ResourceComponentType<?> type) {
            this.values.remove(type);
            return this;
        }

        /** Keeps the raw data of a component whose type is not registered. */
        public @NotNull Builder unknown(@NotNull Key key, @NotNull DataValue data) {
            this.unknown.put(key, data);
            return this;
        }

        public @NotNull ResourceComponents build() {
            if (this.values.isEmpty() && this.unknown.isEmpty()) return EMPTY;
            Map<ResourceComponentType<?>, Object> sortedValues = new LinkedHashMap<>();
            this.values.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> entry.getKey().key().asString()))
                    .forEach(entry -> sortedValues.put(entry.getKey(), entry.getValue()));
            Map<Key, DataValue> sortedUnknown = new LinkedHashMap<>();
            this.unknown.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> entry.getKey().asString()))
                    .forEach(entry -> sortedUnknown.put(entry.getKey(), entry.getValue()));
            return new ResourceComponents(Collections.unmodifiableMap(sortedValues), Collections.unmodifiableMap(sortedUnknown));
        }
    }
}
