package fr.robie.machineengine.api.network;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.Keyed;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * A kind of network (ARCHITECTURE.md §15): which transports join each other and how the network
 * moves resources between the machines attached to it. MachineEngine attaches no meaning to it.
 */
public final class NetworkType implements Keyed {
    private final Key key;
    private final NetworkBehavior behavior;

    private NetworkType(Key key, NetworkBehavior behavior) {
        this.key = key;
        this.behavior = behavior;
    }

    public static @NotNull NetworkType of(@NotNull Key key, @NotNull NetworkBehavior behavior) {
        return new NetworkType(Objects.requireNonNull(key), Objects.requireNonNull(behavior));
    }

    @Override
    public @NotNull Key key() {
        return this.key;
    }

    public @NotNull NetworkBehavior behavior() {
        return this.behavior;
    }

    @Override
    public String toString() {
        return "NetworkType[" + this.key.asString() + "]";
    }
}
