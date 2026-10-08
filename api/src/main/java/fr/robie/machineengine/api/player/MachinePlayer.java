package fr.robie.machineengine.api.player;

import fr.robie.machineengine.api.error.Result;
import net.kyori.adventure.key.Key;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Everything MachineEngine and its addons keep for one player (D37): values of registered
 * {@link PlayerDataKey}s and {@link PlayerSetting}s. Get it with {@code MachineEngine.get().players().get(player)}.
 *
 * <p>Use it on the player's own thread (their entity scheduler on Folia), like a machine on its region.
 * Reads and writes are safe from any thread, but two threads updating the same value race.</p>
 */
@ApiStatus.NonExtendable
public interface MachinePlayer {

    @NotNull UUID uuid();

    /** The online player; {@code null} for data loaded for an offline player ({@link MachinePlayers#loadOffline}). */
    @Nullable Player player();

    /**
     * @return the value, or the key's default if none was set
     * @throws IllegalArgumentException if {@code key} is not registered
     */
    <T> @NotNull T get(@NotNull PlayerDataKey<T> key);

    /** @throws IllegalArgumentException if {@code key} is not registered */
    <T> void set(@NotNull PlayerDataKey<T> key, @NotNull T value);

    /** Sets the value to {@code change} of the current one, atomically, and returns it. */
    <T> @NotNull T update(@NotNull PlayerDataKey<T> key, @NotNull UnaryOperator<T> change);

    /** Back to the key's default (removed from the saved data). */
    void reset(@NotNull PlayerDataKey<?> key);

    /**
     * The setting's value: the server owner's value if they locked it, else the player's choice, else
     * the server owner's default, else the setting's own default.
     */
    <T> @NotNull T get(@NotNull PlayerSetting<T> setting);

    /**
     * Changes a setting from what the player typed.
     *
     * @return the new value, or an error when {@code text} is not valid or the setting is locked
     */
    <T> @NotNull Result<T> set(@NotNull PlayerSetting<T> setting, @NotNull String text);

    /** Saved values whose key is not registered (their addon is missing); kept and saved again (D3). */
    @NotNull Set<Key> unknownKeys();
}
