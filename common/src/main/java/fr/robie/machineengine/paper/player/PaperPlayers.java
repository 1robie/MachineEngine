package fr.robie.machineengine.paper.player;

import fr.robie.machineengine.api.MachineEngine;
import fr.robie.machineengine.api.config.ConfigValues;
import fr.robie.machineengine.api.config.EngineConfig;
import fr.robie.machineengine.api.data.DataMap;
import fr.robie.machineengine.api.error.Result;
import fr.robie.machineengine.api.data.Codecs;
import fr.robie.machineengine.api.platform.Scheduler;
import fr.robie.machineengine.api.platform.Task;
import fr.robie.machineengine.api.player.MachinePlayer;
import fr.robie.machineengine.api.player.PlayerDataKey;
import fr.robie.machineengine.api.player.PlayerDataStorage;
import fr.robie.machineengine.api.registry.Registries;
import fr.robie.machineengine.core.player.CorePlayerData;
import fr.robie.machineengine.core.player.CorePlayers;
import fr.robie.machineengine.paper.Messages;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Loads and saves players' data on Paper (D37): the storage chosen in {@code players.storage} loads a
 * player's data in the {@code AsyncPlayerPreLoginEvent} (or at join, for a storage that needs the online
 * player), they get it at join ({@code LOWEST}, so other plugins' join handlers see it), it is saved every
 * {@code players.save-interval} seconds when it changed, at quit ({@code MONITOR}) and when MachineEngine
 * stops. Each player's data is encoded and saved on that player's own thread (Folia).
 */
public final class PaperPlayers implements Listener {
    private final Plugin plugin;
    private final CorePlayers players;
    private final PlayerDataStorage storage;
    private final Scheduler scheduler;
    /** Loaded before join, by UUID; removed at join. */
    private final Map<UUID, DataMap> preloaded = new ConcurrentHashMap<>();
    private final Task saveTimer;
    private int secondsSinceSave;

    private PaperPlayers(Plugin plugin, CorePlayers players, PlayerDataStorage storage) {
        this.plugin = plugin;
        this.players = players;
        this.storage = storage;
        this.scheduler = MachineEngine.scheduler(plugin);
        this.saveTimer = this.scheduler.runTimer(this::everySecond, 20, 20);
    }

    /** Builds the storage of {@code players.storage}, starts loading and saving, and loads players already online. */
    public static @NotNull PaperPlayers install(@NotNull Plugin plugin, @NotNull CorePlayers players, @NotNull ConfigValues config) {
        PlayerDataStorage storage = storage(plugin, players, config);
        players.setStorage(storage);
        PaperPlayers paper = new PaperPlayers(plugin, players, storage);
        Bukkit.getPluginManager().registerEvents(paper, plugin);
        for (Player player : Bukkit.getOnlinePlayers()) {
            paper.scheduler.run(player, () -> paper.join(player), null);
        }
        return paper;
    }

    private static PlayerDataStorage storage(Plugin plugin, CorePlayers players, ConfigValues config) {
        String chosen = config.get(EngineConfig.PLAYER_STORAGE);
        Result<Key> key = Codecs.parseKey(chosen.contains(":") ? chosen : "machineengine:" + chosen);
        PlayerDataStorage.Type type = key.isOk()
                ? MachineEngine.get().registries().registry(Registries.PLAYER_STORAGE).get(key.value()).orElse(null) : null;
        if (type == null) {
            plugin.getLogger().warning("players.storage: no player data storage '" + chosen + "'; using machineengine:pdc. Storages: "
                    + MachineEngine.get().registries().registry(Registries.PLAYER_STORAGE).keys());
            type = PdcPlayerStorage.TYPE;
        }
        plugin.getLogger().info("Player data is saved with " + type.key().asString());
        return type.factory().create(plugin, config);
    }

    public @NotNull PlayerDataStorage storage() {
        return this.storage;
    }

    // -- join ----------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (this.storage.needsOnlinePlayer() || event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        UUID uuid = event.getUniqueId();
        int timeout = MachineEngine.config().get(EngineConfig.PLAYER_LOAD_TIMEOUT);
        try {
            this.preloaded.put(uuid, this.storage.load(uuid, null).get(timeout, TimeUnit.SECONDS));
        } catch (Exception e) {
            this.plugin.getLogger().log(Level.WARNING, "Could not load the data of " + event.getName() + " (" + uuid + "); login refused", e);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Messages.playerDataNotLoaded());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        join(event.getPlayer());
    }

    private void join(Player player) {
        UUID uuid = player.getUniqueId();
        if (this.players.find(uuid).isPresent()) return;
        DataMap saved = this.preloaded.remove(uuid);
        if (saved == null) {
            try {
                saved = this.storage.load(uuid, player).get(MachineEngine.config().get(EngineConfig.PLAYER_LOAD_TIMEOUT), TimeUnit.SECONDS);
            } catch (Exception e) {
                // Without their data, saving would overwrite it: keep them out.
                this.plugin.getLogger().log(Level.SEVERE, "Could not load the data of " + player.getName() + "; kicking them", e);
                player.kick(Messages.playerDataNotLoaded());
                return;
            }
        }
        CorePlayerData data = this.players.join(uuid, player, saved);
        migratePdc(player, data);
    }

    /** Values an older version kept in the player's PDC ({@link PlayerDataKey#migratePdc}) move into their key. */
    private void migratePdc(Player player, CorePlayerData data) {
        PersistentDataContainer container = player.getPersistentDataContainer();
        for (PlayerDataKey<?> key : this.players.keys()) {
            if (key.pdcMigration() != null) migrate(container, data, key);
        }
    }

    private static <T> void migrate(PersistentDataContainer container, MachinePlayer data, PlayerDataKey<T> key) {
        PlayerDataKey.PdcMigration<T> migration = key.pdcMigration();
        NamespacedKey old = migration == null ? null : NamespacedKey.fromString(migration.key());
        if (old == null || !container.has(old)) return;
        T value;
        try {
            value = container.get(old, migration.type());
        } catch (IllegalArgumentException e) {
            return; // another type: not ours
        }
        if (value != null) data.set(key, value);
        container.remove(old);
    }

    // -- leave, death ----------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        this.preloaded.remove(player.getUniqueId());
        CorePlayerData data = this.players.quit(player.getUniqueId());
        if (data != null) save(data, player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        this.players.find(event.getEntity().getUniqueId()).ifPresent(this.players::died);
    }

    // -- saving ----------------------------------------------------------------------------------------

    private void everySecond() {
        if (++this.secondsSinceSave < MachineEngine.config().get(EngineConfig.PLAYER_SAVE_INTERVAL)) return;
        this.secondsSinceSave = 0;
        for (MachinePlayer online : this.players.online()) {
            CorePlayerData data = (CorePlayerData) online;
            Player player = data.player();
            if (player == null || !data.isDirty()) continue;
            this.scheduler.run(player, () -> {
                // Still the same session (they may have left and come back meanwhile).
                if (this.players.find(data.uuid()).orElse(null) == data) save(data, player);
            }, null);
        }
    }

    /** Encodes on the calling thread (the player's) and hands the data to the storage. */
    private void save(CorePlayerData data, @Nullable Player player) {
        data.markSaved();
        DataMap encoded = this.players.encode(data);
        this.storage.save(data.uuid(), player, encoded).whenComplete((done, error) -> {
            if (error == null) return;
            data.markDirty();
            this.plugin.getLogger().log(Level.SEVERE, "Could not save the data of " + data, error);
        });
    }

    /** Saves every online player and waits for the storage; MachineEngine is stopping. */
    public void shutdown() {
        this.saveTimer.cancel();
        HandlerList.unregisterAll(this);
        for (MachinePlayer online : this.players.online()) {
            CorePlayerData data = (CorePlayerData) online;
            try {
                save(data, data.player());
            } catch (RuntimeException e) {
                this.plugin.getLogger().log(Level.SEVERE, "Could not save the data of " + data, e);
            }
        }
        this.storage.close();
    }
}
