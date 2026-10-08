package fr.robie.machineengine.api.player;

import fr.robie.machineengine.api.data.VersionedCodec;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.Keyed;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * A value MachineEngine keeps for each player (D37), declared by an addon in its own namespace and
 * registered with {@code RegistrationContext#playerData()}. Read and written through
 * {@link MachinePlayer#get}/{@link MachinePlayer#set}. Values must be immutable.
 *
 * <pre>{@code
 * public static final PlayerDataKey<Double> DOSE = PlayerDataKey.persistent(Key.key("mymod", "dose"),
 *         VersionedCodec.builder(Key.key("mymod", "dose"), Codecs.DOUBLE).build(), 0.0).resetOnDeath();
 * public static final PlayerDataKey<Long> LAST_TELEPORT = PlayerDataKey.session(Key.key("mymod", "last_teleport"), 0L);
 * }</pre>
 *
 * <p>Keys are immutable: {@link #resetOnDeath} and {@link #migratePdc} return a new key.</p>
 */
public final class PlayerDataKey<T> implements Keyed {
    private final Key key;
    private final @Nullable VersionedCodec<T> codec;
    private final T defaultValue;
    private final boolean resetOnDeath;
    private final @Nullable PdcMigration<T> pdcMigration;

    private PlayerDataKey(Key key, @Nullable VersionedCodec<T> codec, T defaultValue, boolean resetOnDeath,
                          @Nullable PdcMigration<T> pdcMigration) {
        this.key = Objects.requireNonNull(key, "key");
        this.codec = codec;
        this.defaultValue = Objects.requireNonNull(defaultValue, "defaultValue");
        this.resetOnDeath = resetOnDeath;
        this.pdcMigration = pdcMigration;
    }

    /** A value saved with the player's data and loaded again when they join. */
    public static <T> @NotNull PlayerDataKey<T> persistent(@NotNull Key key, @NotNull VersionedCodec<T> codec, @NotNull T defaultValue) {
        return new PlayerDataKey<>(key, Objects.requireNonNull(codec, "codec"), defaultValue, false, null);
    }

    /** A value kept while the player is online and forgotten when they leave; never saved. */
    public static <T> @NotNull PlayerDataKey<T> session(@NotNull Key key, @NotNull T defaultValue) {
        return new PlayerDataKey<>(key, null, defaultValue, false, null);
    }

    /** The value goes back to its default when the player dies. */
    public @NotNull PlayerDataKey<T> resetOnDeath() {
        return new PlayerDataKey<>(this.key, this.codec, this.defaultValue, true, this.pdcMigration);
    }

    /**
     * Where an older version kept the value: a {@code namespace:key} entry of the player's
     * {@code PersistentDataContainer}. When the player joins, a value found there replaces this key's,
     * and the old entry is removed. Only for {@link #persistent} keys.
     */
    public @NotNull PlayerDataKey<T> migratePdc(@NotNull String oldKey, @NotNull PersistentDataType<?, T> type) {
        if (this.codec == null) throw new IllegalStateException(this.key.asString() + " is a session key: nothing to migrate into");
        return new PlayerDataKey<>(this.key, this.codec, this.defaultValue, this.resetOnDeath, new PdcMigration<>(oldKey, type));
    }

    @Override
    public @NotNull Key key() {
        return this.key;
    }

    /** How the value is saved; {@code null} for a session key. */
    public @Nullable VersionedCodec<T> codec() {
        return this.codec;
    }

    public boolean isPersistent() {
        return this.codec != null;
    }

    public @NotNull T defaultValue() {
        return this.defaultValue;
    }

    public boolean isResetOnDeath() {
        return this.resetOnDeath;
    }

    public @Nullable PdcMigration<T> pdcMigration() {
        return this.pdcMigration;
    }

    @Override
    public String toString() {
        return "PlayerDataKey[" + this.key.asString() + (isPersistent() ? "" : ", session") + "]";
    }

    /** An entry of the player's PDC an older version wrote, see {@link #migratePdc}. */
    public record PdcMigration<T>(@NotNull String key, @NotNull PersistentDataType<?, T> type) {
        public PdcMigration {
            Objects.requireNonNull(key);
            Objects.requireNonNull(type);
        }
    }
}
