package fr.robie.machineengine.api.network;

import fr.robie.machineengine.api.resource.ResourceVariant;
import fr.robie.machineengine.api.resource.tag.ResourceTags;
import fr.robie.machineengine.api.transfer.TransactionManager;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.function.Predicate;

/** What a {@link NetworkBehavior} sees during one network tick. Owner thread only. */
@ApiStatus.NonExtendable
public interface NetworkContext {

    @NotNull NetworkType type();

    /** Attached ports, sorted by position then side, so distribution is deterministic (§19). */
    @NotNull List<NetworkEndpoint> endpoints();

    /** Most the network may move this tick (its slowest transport's rate). */
    long ratePerTick();

    /** What every transport of the network accepts, bound to the current tag snapshot. */
    @NotNull Predicate<ResourceVariant> filter();

    @NotNull TransactionManager transactions();

    @NotNull ResourceTags tags();

    long gameTime();
}
