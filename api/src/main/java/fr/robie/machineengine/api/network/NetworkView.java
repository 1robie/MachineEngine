package fr.robie.machineengine.api.network;

import fr.robie.machineengine.api.world.WorldPosition;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;

/**
 * Snapshot of a network for inspection (§27).
 *
 * @param transports    loaded transport blocks in the network
 * @param endpoints     attached machine ports, as {@code position side}
 * @param movedLastTick what the last network tick moved
 */
public record NetworkView(@NotNull NetworkType type, @NotNull Set<WorldPosition> transports, @NotNull List<String> endpoints,
                          long ratePerTick, boolean active, long movedLastTick) {

    public NetworkView {
        transports = Set.copyOf(transports);
        endpoints = List.copyOf(endpoints);
    }
}
