package fr.robie.machineengine.api.capability;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.Keyed;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * A kind of functionality a machine can expose (ARCHITECTURE.md §11.1), e.g. a resource handler.
 * Addons define their own by registering new capability types.
 *
 * @param <T> the interface callers receive
 */
public final class CapabilityType<T> implements Keyed {
    private final Key key;
    private final Class<T> type;

    private CapabilityType(Key key, Class<T> type) {
        this.key = key;
        this.type = type;
    }

    public static <T> @NotNull CapabilityType<T> of(@NotNull Key key, @NotNull Class<T> type) {
        return new CapabilityType<>(Objects.requireNonNull(key), Objects.requireNonNull(type));
    }

    @Override
    public @NotNull Key key() {
        return this.key;
    }

    public @NotNull Class<T> type() {
        return this.type;
    }

    @Override
    public String toString() {
        return "CapabilityType[" + this.key.asString() + "]";
    }
}
