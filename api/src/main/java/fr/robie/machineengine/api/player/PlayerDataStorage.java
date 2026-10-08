package fr.robie.machineengine.api.player;

import fr.robie.machineengine.api.config.ConfigValues;
import fr.robie.machineengine.api.data.DataBinary;
import fr.robie.machineengine.api.data.DataMap;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.Keyed;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Where the persistent values of players are saved (D37). MachineEngine ships {@code machineengine:pdc}
 * (the player's {@code PersistentDataContainer}, the default) and {@code machineengine:files} (one file
 * per player); an addon adds another with {@code RegistrationContext#playerStorages()}, and the server
 * owner picks one with {@code players.storage} in MachineEngine's {@code config.yml}.
 *
 * <p>A storage gets and returns the whole saved data of a player as a {@link DataMap}; {@link DataBinary}
 * turns it into bytes. MachineEngine never asks for the same player's load and save at once, and calls
 * {@link #save} in order for one player.</p>
 */
public interface PlayerDataStorage {

    /**
     * The saved data of a player, {@link DataMap#EMPTY} if there is none. Called in the
     * {@code AsyncPlayerPreLoginEvent} (off the main thread, {@code online} null) unless
     * {@link #needsOnlinePlayer()}, then in the {@code PlayerJoinEvent} on the player's thread.
     */
    @NotNull CompletableFuture<DataMap> load(@NotNull UUID uuid, @Nullable Player online);

    /** Saves the data of a player; called on the player's thread when {@code online} is not null. */
    @NotNull CompletableFuture<Void> save(@NotNull UUID uuid, @Nullable Player online, @NotNull DataMap data);

    /** Whether loading and saving need the online player (and so cannot serve offline players). */
    default boolean needsOnlinePlayer() {
        return false;
    }

    /** Waits for every pending save; called when MachineEngine stops. */
    default void close() {
    }

    /** Builds the storage when MachineEngine enables. */
    @FunctionalInterface
    interface Factory {
        @NotNull PlayerDataStorage create(@NotNull Plugin machineEngine, @NotNull ConfigValues config);
    }

    /** A storage registered under a key, for {@code players.storage}. */
    record Type(@NotNull Key key, @NotNull Factory factory) implements Keyed {
        public Type {
            Objects.requireNonNull(key);
            Objects.requireNonNull(factory);
        }
    }
}
