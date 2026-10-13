package fr.robie.machineengine.core.player;

import fr.robie.machineengine.api.MachineEngineKeys;
import fr.robie.machineengine.api.config.ConfigValues;
import fr.robie.machineengine.api.config.EngineConfig;
import fr.robie.machineengine.api.data.Codecs;
import fr.robie.machineengine.api.data.DataMap;
import fr.robie.machineengine.api.data.DataValue;
import fr.robie.machineengine.api.data.VersionedCodec;
import fr.robie.machineengine.api.error.MachineError;
import fr.robie.machineengine.api.error.Result;
import fr.robie.machineengine.api.event.EventBus;
import fr.robie.machineengine.api.event.EventPhase;
import fr.robie.machineengine.api.player.MachinePlayer;
import fr.robie.machineengine.api.player.MachinePlayers;
import fr.robie.machineengine.api.player.PlayerDataKey;
import fr.robie.machineengine.api.player.PlayerDataStorage;
import fr.robie.machineengine.api.player.PlayerEvents;
import fr.robie.machineengine.api.player.PlayerSetting;
import fr.robie.machineengine.api.registry.Registries;
import fr.robie.machineengine.api.registry.RegistryAccess;
import fr.robie.messageflow.formatter.Placeholder;
import fr.robie.messageflow.logger.Logger;
import net.kyori.adventure.key.Key;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The players' data (D37): the registered keys and settings, the online players, their saved form.
 * Platform-neutral; the platform calls {@link #join}, {@link #quit} and {@link #died} and saves
 * {@link #encode} through the {@link #storage()} it installed ({@link MemoryPlayerStorage} until then).
 *
 * <p>Saved form: {@code {version: 1, values: {"<namespace>:<id>": <the key's versioned value>, …}}}.</p>
 */
public final class CorePlayers implements MachinePlayers {
    public static final int FORMAT_VERSION = 1;
    public static final Key SETTING_LOCKED = MachineEngineKeys.key("setting_locked");
    public static final Key INVALID_SETTING_VALUE = MachineEngineKeys.key("invalid_setting_value");

    private final Map<Key, PlayerDataKey<?>> keys = new LinkedHashMap<>();
    private final Map<Key, PlayerSetting<?>> settings = new LinkedHashMap<>();
    private final List<String> problems = new ArrayList<>();
    private final Map<UUID, CorePlayerData> online = new ConcurrentHashMap<>();
    private final Set<String> reportedDefaults = ConcurrentHashMap.newKeySet();
    private final EventBus events;
    private final Supplier<ConfigValues> config;
    private final Logger logger;
    private volatile PlayerDataStorage storage = new MemoryPlayerStorage();

    public CorePlayers(@NotNull RegistryAccess registries, @NotNull EventBus events, @NotNull Supplier<ConfigValues> config,
                       @NotNull Logger logger) {
        this.events = events;
        this.config = config;
        this.logger = logger;
        registries.registry(Registries.PLAYER_DATA).values().forEach(key -> this.keys.put(key.key(), key));
        registries.registry(Registries.PLAYER_SETTING).values().forEach(setting -> {
            if (this.keys.containsKey(setting.key())) {
                this.problems.add("player setting " + setting.key().asString() + " has the id of a player data key; the setting is ignored");
                return;
            }
            this.settings.put(setting.key(), setting);
            this.keys.put(setting.key(), setting.data());
        });
    }

    /** Conflicts found between registered keys and settings, for the startup report. */
    public @NotNull List<String> problems() {
        return List.copyOf(this.problems);
    }

    /** Registered keys, settings' included. */
    public @NotNull Collection<PlayerDataKey<?>> keys() {
        return Collections.unmodifiableCollection(this.keys.values());
    }

    public @NotNull Collection<PlayerSetting<?>> settings() {
        return Collections.unmodifiableCollection(this.settings.values());
    }

    public @Nullable PlayerDataKey<?> key(@NotNull Key key) {
        return this.keys.get(key);
    }

    public @Nullable PlayerSetting<?> setting(@NotNull Key key) {
        return this.settings.get(key);
    }

    /** Installs the storage chosen in {@code players.storage}; the platform does it before anyone joins. */
    public void setStorage(@NotNull PlayerDataStorage storage) {
        this.storage = storage;
    }

    @Override
    public @NotNull PlayerDataStorage storage() {
        return this.storage;
    }

    // -- online players ---------------------------------------------------------------------------

    /** A player joined with {@code saved} (their stored data); they are online from now on. */
    public @NotNull CorePlayerData join(@NotNull UUID uuid, @Nullable Player player, @NotNull DataMap saved) {
        CorePlayerData data = decode(uuid, player, saved);
        this.online.put(uuid, data);
        return data;
    }

    /** A player left: their data, to save one last time; their session values are gone with it. */
    public @Nullable CorePlayerData quit(@NotNull UUID uuid) {
        return this.online.remove(uuid);
    }

    /** A player died: values that reset on death go back to their default. */
    public void died(@NotNull MachinePlayer player) {
        for (PlayerDataKey<?> key : this.keys.values()) {
            if (key.isResetOnDeath()) player.reset(key);
        }
    }

    @Override
    public @NotNull MachinePlayer get(@NotNull Player player) {
        CorePlayerData data = this.online.get(player.getUniqueId());
        if (data == null) throw new IllegalStateException("no data loaded for " + player.getName() + " (not online?)");
        return data;
    }

    @Override
    public @NotNull Optional<MachinePlayer> find(@NotNull UUID uuid) {
        return Optional.ofNullable(this.online.get(uuid));
    }

    @Override
    public @NotNull Collection<MachinePlayer> online() {
        return List.copyOf(this.online.values());
    }

    @Override
    public @NotNull CompletableFuture<Optional<MachinePlayer>> loadOffline(@NotNull UUID uuid) {
        CorePlayerData data = this.online.get(uuid);
        if (data != null) return CompletableFuture.completedFuture(Optional.of(data));
        PlayerDataStorage current = this.storage;
        if (current.needsOnlinePlayer()) return CompletableFuture.completedFuture(Optional.empty());
        return current.load(uuid, null).thenApply(saved -> Optional.of(decode(uuid, null, saved)));
    }

    @Override
    public @NotNull CompletableFuture<Void> saveOffline(@NotNull MachinePlayer player) {
        CorePlayerData data = (CorePlayerData) player;
        if (this.online.get(data.uuid()) == data) {
            throw new IllegalArgumentException(data.uuid() + " is online: their data is saved by MachineEngine");
        }
        return this.storage.save(data.uuid(), null, encode(data));
    }

    // -- saved form ---------------------------------------------------------------------------------

    /** The saved form of a player's persistent values and the unknown values they kept. */
    public @NotNull DataMap encode(@NotNull CorePlayerData data) {
        DataMap.Builder values = DataMap.builder();
        data.unknown().forEach((key, value) -> values.put(key.asString(), value));
        data.values().forEach((id, value) -> {
            PlayerDataKey<?> key = this.keys.get(id);
            if (key != null && key.codec() != null) values.put(id.asString(), encode(key.codec(), value));
        });
        return DataMap.builder().put("version", FORMAT_VERSION).put("values", values.build()).build();
    }

    @SuppressWarnings("unchecked")
    private static <T> DataValue encode(VersionedCodec<T> codec, Object value) {
        return codec.encode((T) value);
    }

    /** A player's data from its saved form; values that do not decode are kept as they are. */
    public @NotNull CorePlayerData decode(@NotNull UUID uuid, @Nullable Player player, @NotNull DataMap saved) {
        Map<Key, Object> values = new LinkedHashMap<>();
        Map<Key, DataValue> unknown = new LinkedHashMap<>();
        int version = saved.getInt("version").orElse(FORMAT_VERSION);
        if (version > FORMAT_VERSION) {
            this.logger.logWarn("[players]", "Player data of %uuid% is format %version%, newer than this MachineEngine (%supported%); reading what it can",
                    Placeholder.builder().register("uuid", uuid.toString()).register("version", String.valueOf(version))
                            .register("supported", String.valueOf(FORMAT_VERSION)).build());
        }
        DataMap entries = saved.getMap("values").orElse(DataMap.EMPTY);
        for (Map.Entry<String, DataValue> entry : entries.entries().entrySet()) {
            Result<Key> id = Codecs.parseKey(entry.getKey());
            if (id.isErr()) {
                this.logger.logWarn("[players]", "Player data of %uuid% has an invalid key '%key%'; dropped",
                        Placeholder.of("uuid", uuid.toString(), "key", entry.getKey()));
                continue;
            }
            PlayerDataKey<?> key = this.keys.get(id.value());
            if (key == null) {
                unknown.put(id.value(), entry.getValue());
                continue;
            }
            if (key.codec() == null) continue; // became a session key
            Result<?> value = key.codec().decode(entry.getValue());
            if (value.isOk()) {
                values.put(id.value(), value.value());
            } else {
                this.logger.logWarn("[players]", "Player data %key% of %uuid% could not be read (%error%); kept as it is, the default is used",
                        Placeholder.builder().register("key", id.value().asString()).register("uuid", uuid.toString())
                                .register("error", value.error().message()).build());
                unknown.put(id.value(), entry.getValue());
            }
        }
        return new CorePlayerData(this, uuid, player, values, unknown);
    }

    // -- keys and settings --------------------------------------------------------------------------

    /** @throws IllegalArgumentException if {@code key} is not the registered key of its id */
    void check(PlayerDataKey<?> key) {
        if (this.keys.get(key.key()) != key) {
            throw new IllegalArgumentException(key + " is not registered (RegistrationContext#playerData)");
        }
    }

    private void check(PlayerSetting<?> setting) {
        if (this.settings.get(setting.key()) != setting) {
            throw new IllegalArgumentException(setting + " is not registered (RegistrationContext#playerSettings)");
        }
    }

    /** Whether the server owner locked {@code setting} ({@code players.locked-settings}). */
    public boolean isLocked(@NotNull PlayerSetting<?> setting) {
        return this.config.get().get(EngineConfig.PLAYER_LOCKED_SETTINGS).contains(setting.key().asString());
    }

    /** The server owner's default ({@code players.setting-defaults}), else the setting's. */
    public <T> @NotNull T ownerDefault(@NotNull PlayerSetting<T> setting) {
        String text = this.config.get().get(EngineConfig.PLAYER_SETTING_DEFAULTS).get(setting.key().asString());
        if (text == null) return setting.defaultValue();
        T value = setting.parse(text);
        if (value == null) {
            if (this.reportedDefaults.add(setting.key().asString() + "=" + text)) {
                this.logger.logWarn("[players]", "players.setting-defaults: '%value%' is not a valid value of %setting% (%expected%); using %default%",
                        Placeholder.builder().register("value", text).register("setting", setting.key().asString())
                                .register("expected", setting.expected()).register("default", setting.format(setting.defaultValue())).build());
            }
            return setting.defaultValue();
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    <T> T settingValue(CorePlayerData data, PlayerSetting<T> setting) {
        check(setting);
        if (isLocked(setting)) return ownerDefault(setting);
        Object chosen = data.values().get(setting.key());
        return chosen != null ? (T) chosen : ownerDefault(setting);
    }

    <T> Result<T> setSetting(CorePlayerData data, PlayerSetting<T> setting, String text) {
        check(setting);
        if (isLocked(setting)) {
            return Result.err(MachineError.of(SETTING_LOCKED, setting.key().asString() + " is set by the server"));
        }
        T value = setting.parse(text);
        if (value == null) {
            return Result.err(MachineError.of(INVALID_SETTING_VALUE, "'" + text + "' is not " + setting.expected()));
        }
        T old = settingValue(data, setting);
        data.set(setting.data(), value);
        if (!old.equals(value)) {
            this.events.post(PlayerEvents.SETTING_CHANGED, EventPhase.POST, new PlayerEvents.SettingChanged(data, setting, old, value));
        }
        return Result.ok(value);
    }
}
