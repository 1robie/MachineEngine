package fr.robie.machineengine.api.network;

import fr.robie.machineengine.api.resource.match.ResourcePredicate;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.Keyed;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * A kind of transport block, e.g. a cable or pipe (ARCHITECTURE.md §15). Adjacent transports of the
 * same {@link NetworkType} form one network; the network moves at most the lowest
 * {@link #ratePerTick()} of its transports, and only what {@link #filter()} accepts.
 */
public final class TransportType implements Keyed {
    private final Key key;
    private final NetworkType network;
    private final long ratePerTick;
    private final ResourcePredicate filter;

    private TransportType(Key key, NetworkType network, long ratePerTick, ResourcePredicate filter) {
        this.key = key;
        this.network = network;
        this.ratePerTick = ratePerTick;
        this.filter = filter;
    }

    public static @NotNull TransportType of(@NotNull Key key, @NotNull NetworkType network, long ratePerTick,
                                            @NotNull ResourcePredicate filter) {
        return new TransportType(Objects.requireNonNull(key), Objects.requireNonNull(network), ratePerTick, Objects.requireNonNull(filter));
    }

    @Override
    public @NotNull Key key() {
        return this.key;
    }

    public @NotNull NetworkType network() {
        return this.network;
    }

    public long ratePerTick() {
        return this.ratePerTick;
    }

    public @NotNull ResourcePredicate filter() {
        return this.filter;
    }

    @Override
    public String toString() {
        return "TransportType[" + this.key.asString() + "]";
    }
}
