package fr.robie.machineengine.api.player;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** The {@link MachinePlayer}s of the server (D37): {@code MachineEngine.get().players()}. */
@ApiStatus.NonExtendable
public interface MachinePlayers {

    /**
     * The data of an online player; present from their {@code PlayerJoinEvent} (at {@code LOWEST}
     * priority) until their {@code PlayerQuitEvent} (at {@code MONITOR}).
     *
     * @throws IllegalStateException if the player is not online or MachineEngine has not loaded their data
     */
    @NotNull MachinePlayer get(@NotNull Player player);

    /** The data of an online player, if loaded. */
    @NotNull Optional<MachinePlayer> find(@NotNull UUID uuid);

    /** Players whose data is loaded. */
    @NotNull Collection<MachinePlayer> online();

    /**
     * The data of a player who may be offline: the online data if they are online, else what the storage
     * holds. Changes to offline data are saved with {@link #saveOffline}; a player who joins meanwhile
     * gets the stored data, so save before they can join.
     *
     * @return empty when the storage cannot read offline players (the default PDC storage)
     */
    @NotNull CompletableFuture<Optional<MachinePlayer>> loadOffline(@NotNull UUID uuid);

    /** Saves data returned by {@link #loadOffline} for an offline player. */
    @NotNull CompletableFuture<Void> saveOffline(@NotNull MachinePlayer player);

    /** The storage chosen in {@code players.storage}. */
    @NotNull PlayerDataStorage storage();
}
