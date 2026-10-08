package fr.robie.machineengine.core.player;

import fr.robie.machineengine.api.data.DataValue;
import fr.robie.machineengine.api.error.Result;
import fr.robie.machineengine.api.player.MachinePlayer;
import fr.robie.machineengine.api.player.PlayerDataKey;
import fr.robie.machineengine.api.player.PlayerSetting;
import net.kyori.adventure.key.Key;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

/** One player's values (D37); see {@link CorePlayers}. */
public final class CorePlayerData implements MachinePlayer {
    private final CorePlayers players;
    private final UUID uuid;
    private final @Nullable Player player;
    private final Map<Key, Object> values = new ConcurrentHashMap<>();
    /** Saved values of keys nobody registered, or that did not decode (D3). */
    private final Map<Key, DataValue> unknown = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean();

    CorePlayerData(CorePlayers players, UUID uuid, @Nullable Player player, Map<Key, Object> values, Map<Key, DataValue> unknown) {
        this.players = players;
        this.uuid = uuid;
        this.player = player;
        this.values.putAll(values);
        this.unknown.putAll(unknown);
    }

    @Override
    public @NotNull UUID uuid() {
        return this.uuid;
    }

    @Override
    public @Nullable Player player() {
        return this.player;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> @NotNull T get(@NotNull PlayerDataKey<T> key) {
        this.players.check(key);
        Object value = this.values.get(key.key());
        return value != null ? (T) value : key.defaultValue();
    }

    @Override
    public <T> void set(@NotNull PlayerDataKey<T> key, @NotNull T value) {
        this.players.check(key);
        Objects.requireNonNull(value, "value");
        this.values.put(key.key(), value);
        this.unknown.remove(key.key());
        if (key.isPersistent()) this.dirty.set(true);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> @NotNull T update(@NotNull PlayerDataKey<T> key, @NotNull UnaryOperator<T> change) {
        this.players.check(key);
        T updated = (T) this.values.compute(key.key(), (id, old) -> Objects.requireNonNull(
                change.apply(old != null ? (T) old : key.defaultValue()), "updated value"));
        this.unknown.remove(key.key());
        if (key.isPersistent()) this.dirty.set(true);
        return updated;
    }

    @Override
    public void reset(@NotNull PlayerDataKey<?> key) {
        this.players.check(key);
        boolean removed = this.values.remove(key.key()) != null | this.unknown.remove(key.key()) != null;
        if (removed && key.isPersistent()) this.dirty.set(true);
    }

    @Override
    public <T> @NotNull T get(@NotNull PlayerSetting<T> setting) {
        return this.players.settingValue(this, setting);
    }

    @Override
    public <T> @NotNull Result<T> set(@NotNull PlayerSetting<T> setting, @NotNull String text) {
        return this.players.setSetting(this, setting, text);
    }

    @Override
    public @NotNull Set<Key> unknownKeys() {
        return Set.copyOf(this.unknown.keySet());
    }

    /** Whether a persistent value changed since the last {@link #markSaved()}. */
    public boolean isDirty() {
        return this.dirty.get();
    }

    /** Called before encoding for a save: a change made meanwhile marks the data dirty again. */
    public void markSaved() {
        this.dirty.set(false);
    }

    /** A save failed: the data must be saved again. */
    public void markDirty() {
        this.dirty.set(true);
    }

    Map<Key, Object> values() {
        return Collections.unmodifiableMap(this.values);
    }

    Map<Key, DataValue> unknown() {
        return Collections.unmodifiableMap(this.unknown);
    }

    @Override
    public String toString() {
        return "MachinePlayer[" + (this.player != null ? this.player.getName() : this.uuid) + "]";
    }
}
