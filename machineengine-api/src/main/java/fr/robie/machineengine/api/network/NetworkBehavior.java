package fr.robie.machineengine.api.network;

import fr.robie.machineengine.api.transfer.ResourceHandler;
import fr.robie.machineengine.api.transfer.StorageView;
import fr.robie.machineengine.api.transfer.Transaction;
import fr.robie.machineengine.api.transfer.Transfers;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * How a network moves resources (SPI). Called once per tick while the network is active; a network
 * that moved nothing sleeps until one of its machines' storages changes (§17).
 */
@FunctionalInterface
public interface NetworkBehavior {

    /** @return how much was moved; 0 puts the network to sleep */
    long tick(@NotNull NetworkContext context);

    /**
     * Moves from every port that allows extraction into every other port that allows insertion,
     * round-robin over the targets and within the network's rate. Only what the network's filter
     * accepts moves. Each move is a transaction, so nothing is lost or created.
     */
    static @NotNull NetworkBehavior distributing() {
        return context -> {
            List<NetworkEndpoint> endpoints = context.endpoints();
            List<NetworkEndpoint> sources = new ArrayList<>();
            List<NetworkEndpoint> targets = new ArrayList<>();
            for (NetworkEndpoint endpoint : endpoints) {
                if (endpoint.handler().supportsExtraction()) sources.add(endpoint);
                if (endpoint.handler().supportsInsertion()) targets.add(endpoint);
            }
            if (sources.isEmpty() || targets.isEmpty()) return 0;
            long budget = context.ratePerTick();
            long moved = 0;
            // Start the round-robin at a tick-dependent target so no consumer is always served first.
            int start = Math.floorMod(context.gameTime(), targets.size());
            for (NetworkEndpoint source : sources) {
                for (StorageView view : source.handler().views()) {
                    if (view.isEmpty() || !context.filter().test(view.variant())) continue;
                    for (int i = 0; i < targets.size() && moved < budget; i++) {
                        NetworkEndpoint target = targets.get((start + i) % targets.size());
                        if (target.handler() == source.handler()) continue;
                        moved += move(context, source.handler(), target.handler(), view, budget - moved);
                    }
                }
            }
            return moved;
        };
    }

    private static long move(NetworkContext context, ResourceHandler source, ResourceHandler target, StorageView view, long max) {
        try (Transaction transaction = context.transactions().openOuter()) {
            long moved = Transfers.move(source, target, variant -> variant.equals(view.variant()), max, transaction).moved();
            transaction.commit();
            return moved;
        }
    }
}
