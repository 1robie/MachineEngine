package fr.robie.machineengine.api.resource;

import fr.robie.machineengine.api.data.StorageCodec;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.Keyed;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * A typed piece of resource metadata, e.g. {@code mymod:temperature} (ARCHITECTURE.md §9.2).
 * Values must be immutable and implement {@code equals}/{@code hashCode}: they decide whether two
 * stacks are the same variant.
 */
public final class ResourceComponentType<T> implements Keyed {
    private final Key key;
    private final Class<T> valueType;
    private final StorageCodec<T> codec;

    private ResourceComponentType(Key key, Class<T> valueType, StorageCodec<T> codec) {
        this.key = key;
        this.valueType = valueType;
        this.codec = codec;
    }

    public static <T> @NotNull ResourceComponentType<T> of(@NotNull Key key, @NotNull Class<T> valueType,
                                                         @NotNull StorageCodec<T> codec) {
        return new ResourceComponentType<>(Objects.requireNonNull(key), Objects.requireNonNull(valueType), Objects.requireNonNull(codec));
    }

    @Override
    public @NotNull Key key() {
        return this.key;
    }

    public @NotNull Class<T> valueType() {
        return this.valueType;
    }

    public @NotNull StorageCodec<T> codec() {
        return this.codec;
    }

    /**
     * @throws ClassCastException if {@code value} is not a {@link #valueType()}
     */
    public @NotNull T cast(@NotNull Object value) {
        return this.valueType.cast(value);
    }

    @Override
    public String toString() {
        return "ResourceComponentType[" + this.key.asString() + "]";
    }
}
