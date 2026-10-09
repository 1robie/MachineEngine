package fr.robie.machineengine.plugin;

import fr.robie.machineengine.api.MachineEngine;
import fr.robie.machineengine.api.MachineEngineAccess;
import fr.robie.machineengine.api.MachineEngineApi;
import fr.robie.machineengine.api.addon.AddonRegistration;
import fr.robie.machineengine.api.config.ConfigFile;
import fr.robie.machineengine.api.config.ConfigLoadResult;
import fr.robie.machineengine.api.config.EngineConfig;
import fr.robie.machineengine.api.resource.BuiltInResources;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.core.lifecycle.CoreMachineEngine;
import fr.robie.machineengine.core.lifecycle.MachineEngineBuilder;
import fr.robie.machineengine.core.machine.LoadedMachine;
import fr.robie.machineengine.core.machine.PlaceholderMachine;
import fr.robie.machineengine.craftengine.CraftEngineIntegration;
import fr.robie.machineengine.api.item.ItemResources;
import fr.robie.machineengine.api.paper.PaperItemResources;
import fr.robie.machineengine.craftengine.CraftEngineItemBridge;
import fr.robie.machineengine.craftengine.item.CraftEngineUpgradeItems;
import fr.robie.machineengine.api.upgrade.UpgradeItems;
import fr.robie.machineengine.craftengine.CraftEngineMachineHost;
import fr.robie.machineengine.api.platform.Nms;
import fr.robie.machineengine.api.paper.ClassPreloader;
import fr.robie.machineengine.paper.FoliaLibScheduler;
import fr.robie.machineengine.paper.MachineEngineCommands;
import fr.robie.machineengine.paper.MachineMenuService;
import fr.robie.machineengine.paper.Messages;
import fr.robie.machineengine.paper.YamlDefinitions;
import fr.robie.machineengine.paper.event.BukkitEventBridge;
import fr.robie.machineengine.paper.player.FilePlayerStorage;
import fr.robie.machineengine.paper.player.PaperPlayers;
import fr.robie.machineengine.paper.player.PdcPlayerStorage;
import fr.robie.machineengine.paper.player.PlayerSettingsCommand;
import fr.robie.paperdispatch.manager.CommandManager;
import fr.robie.machineengine.zmenu.ZMenuMachineMenus;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockAttemptPlaceEvent;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockBreakEvent;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;

/**
 * MachineEngine plugin entry point (§8.2).
 *
 * <ol>
 *   <li>{@code onLoad}: load {@code config.yml}, register the CraftEngine block behavior; addons submit registrations.</li>
 *   <li>{@code onEnable} (STARTUP, after CraftEngine, before worlds load): start the engine
 *       (registration, validation, freeze), start the CraftEngine host, check block bindings.</li>
 *   <li>{@code onDisable}: snapshot machines into their block entities for CraftEngine's final save.</li>
 * </ol>
 */
@SuppressWarnings("UnstableApiUsage")
public final class MachineEnginePlugin extends JavaPlugin implements Listener {
    public static final String BREAK_PLACEHOLDERS = "machineengine.admin.breakplaceholders";
    private @Nullable CoreMachineEngine engine;
    private @Nullable CraftEngineMachineHost host;
    private @Nullable MachineEngineCommands commands;
    /** Paper-Dispatch's (D35). */
    private final CommandManager<MachineEnginePlugin> commandManager = new CommandManager<>(this);
    private @Nullable ZMenuMachineMenus menus;
    private @Nullable PaperPlayers players;
    private ConfigFile config;

    @Override
    public void onLoad() {
        getLogger().info("Loading MachineEngine " + getPluginMeta().getVersion() + " (API " + MachineEngineApi.version() + ")");
        // Before addons' onLoad: they may read MachineEngine.config().
        this.config = ConfigFile.load(this, "config.yml", EngineConfig.SPEC);
        MachineEngineAccess.installConfig(this.config);
        MachineEngineAccess.installSchedulers(FoliaLibScheduler.perPlugin());
        BuiltInResources.install(this.config);
        // The running version's nms class only (NmsLoader); another version's may not even load here.
        Nms.install(NmsLoader.load(getLogger()));
        // Every class now: if the jar is replaced while the server runs, the shutdown save still works.
        ClassPreloader.preload(MachineEnginePlugin.class, "fr/robie/machineengine/", "fr/robie/machineengine/nms/v");
        CraftEngineIntegration.registerBehaviors();
        installCraftEnginePack();
    }

    /**
     * Installs MachineEngine's CraftEngine pack (translations of player messages, §23.4) before
     * CraftEngine loads its packs. Existing files are kept: server owners edit them.
     */
    private void installCraftEnginePack() {
        Path target = getDataFolder().toPath().getParent().resolve("CraftEngine/resources/machineengine");
        // lang.yml is the server owner's to edit; the templates are what MachineEngine's types need (kept up to date).
        for (String file : List.of("pack.yml", "configuration/lang.yml", "configuration/templates.yml")) {
            Path destination = target.resolve(file);
            if (Files.exists(destination) && !file.endsWith("templates.yml")) continue;
            try (InputStream in = getResource("craftengine-pack/" + file)) {
                if (in == null) throw new IOException("missing resource " + file);
                Files.createDirectories(destination.getParent());
                Files.copy(in, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                getLogger().warning("Could not install the CraftEngine pack file " + file + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void onEnable() {
        // Paper refuses to load MachineEngine without CraftEngine (required dependency); this guards
        // against a broken or disabled CraftEngine.
        Plugin craftEngine = getServer().getPluginManager().getPlugin("CraftEngine");
        if (craftEngine == null || !craftEngine.isEnabled()) {
            getLogger().severe("CraftEngine is required but is not enabled. Disabling MachineEngine.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getLogger().info("Using CraftEngine " + craftEngine.getPluginMeta().getVersion());
        getLogger().info("Scheduling with FoliaLib on " + (MachineEngine.scheduler(this).isFolia() ? "Folia" : "Paper"));

        List<AddonRegistration> registrations = MachineEngineAccess.closeRegistration();
        MachineEngineBuilder builder = new MachineEngineBuilder().logger(getLogger());
        registrations.forEach(builder::addon);
        // Data-driven definitions (§29): plugins/MachineEngine/definitions/*.yml
        YamlDefinitions definitions = new YamlDefinitions(this);
        // CraftEngine items in definitions and item tags (E10, E11); recipes wait for CraftEngine's first load.
        CraftEngineItemBridge items = new CraftEngineItemBridge(this);
        builder.definitions(definitions::load, items);
        builder.itemTags(items);
        // Where player data can be saved (D37); players.storage picks one.
        builder.playerStorage(PdcPlayerStorage.TYPE);
        builder.playerStorage(FilePlayerStorage.TYPE);
        CoreMachineEngine started = builder.start();
        this.engine = started;
        items.attach(() -> this.engine);
        // Items holding energy or other resources: CraftEngine item behaviors and item data (D32).
        ItemResources.setAccess(PaperItemResources.ACCESS);
        getServer().getPluginManager().registerEvents(items, this);
        // Upgrade items: the machineengine:upgrade item behavior (D40).
        CraftEngineUpgradeItems upgradeItems = new CraftEngineUpgradeItems(getLogger());
        UpgradeItems.setAccess(upgradeItems);
        getServer().getPluginManager().registerEvents(upgradeItems, this);
        MachineEngineAccess.install(started);
        if (this.config.get(EngineConfig.BUKKIT_EVENT_BRIDGE)) {
            BukkitEventBridge.install(started.events());
        }
        this.players = PaperPlayers.install(this, started.players(), this.config);
        this.host = CraftEngineMachineHost.start(this, started);
        started.setMachineLookup(this.host::machineAt);
        getLogger().info("Started with " + started.enabledAddons().size() + " addon(s) " + started.enabledAddons()
                + (started.disabledAddons().isEmpty() ? "" : "; disabled: " + started.disabledAddons()));
        checkContentBindings();
        enableMenus();

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(this.host.leftClicks(), this);
        this.commands = new MachineEngineCommands(this, () -> this.engine, () -> this.host, this::reloadSettings);
        this.commandManager.trackCommand(this.commands);
        this.commandManager.trackCommand(new PlayerSettingsCommand(this, () -> this.engine));
        this.commandManager.flushRegistrations();
    }

    /** {@code /machineengine reload config}: new values apply at once; menus are reloaded for {@code menus.*}. */
    private ConfigLoadResult reloadSettings() {
        ConfigLoadResult result = this.config.reload();
        if (this.menus != null && result.error() == null) this.menus.reload();
        return result;
    }

    /** Machine GUIs need zMenu (optional dependency); without it machines simply have no menu. */
    private void enableMenus() {
        Plugin zMenu = getServer().getPluginManager().getPlugin("zMenu");
        if (zMenu == null || this.host == null) {
            getLogger().info("zMenu is not installed: machines have no menus");
            return;
        }
        // MachineEngine enables at STARTUP (before worlds); zMenu enables later, so wait for it.
        if (!zMenu.isEnabled()) return;
        try {
            this.menus = ZMenuMachineMenus.enable(this, this.host);
            this.host.setMenus(this.menus);
            MachineMenuService.setCurrent(this.menus);
        } catch (LinkageError | RuntimeException e) {
            getLogger().log(Level.WARNING, "Could not enable zMenu machine menus", e);
        }
    }

    @Override
    public void onDisable() {
        if (this.commands != null) this.commands.debugView().stopAll();
        this.commandManager.unregisterAll(this);
        if (this.menus != null) this.menus.disable();
        this.menus = null;
        MachineMenuService.setCurrent(null);
        if (this.host != null) this.host.shutdown();
        this.host = null;
        if (this.players != null) this.players.shutdown();
        this.players = null;
        this.engine = null;
        MachineEngineAccess.reset();
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if (event.getPlugin().getName().equals("zMenu") && this.menus == null) enableMenus();
    }

    @EventHandler
    public void onCraftEngineReload(CraftEngineReloadEvent event) {
        // Block configs were parsed again; machine keys are resolved lazily (D14), so only report.
        checkContentBindings();
    }

    /**
     * Placeholders hold the data of an addon that is not installed; breaking one would destroy it, so
     * only admins may (§21).
     */
    @EventHandler(ignoreCancelled = true)
    public void onCustomBlockBreak(CustomBlockBreakEvent event) {
        if (this.host == null) return;
        Location location = event.location();
        LoadedMachine machine = this.host.machine(WorldPosition.of(location.getWorld().getKey(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ()));
        if (machine instanceof PlaceholderMachine placeholder && !this.config.get(EngineConfig.ALLOW_BREAKING_PLACEHOLDERS)
                && !event.getPlayer().hasPermission(BREAK_PLACEHOLDERS)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Messages.placeholderBreakDenied(placeholder.missingType().asString()));
        }
    }

    /** Enforces {@code limits.max-machines-per-chunk} (§23.3). */
    @EventHandler(ignoreCancelled = true)
    public void onCustomBlockPlace(CustomBlockAttemptPlaceEvent event) {
        int limit = this.config.get(EngineConfig.MAX_MACHINES_PER_CHUNK);
        if (this.host == null || limit <= 0 || event.getPlayer().hasPermission(MachineEngineCommands.PERMISSION)) return;
        if (!CraftEngineIntegration.isMachineBlock(event.blockState())) return;
        Location location = event.location();
        int count = this.host.machinesInChunk(location.getWorld().getKey(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
        if (count >= limit) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Messages.chunkLimit(count, limit));
        }
    }

    private void checkContentBindings() {
        if (this.engine == null) return;
        for (String problem : CraftEngineIntegration.unboundContent(this.engine.registries())) getLogger().warning(problem);
    }
}
