package fr.robie.machineengine.plugin;

import fr.robie.machineengine.api.MachineEngineAccess;
import fr.robie.machineengine.api.MachineEngineApi;
import fr.robie.machineengine.api.addon.AddonRegistration;
import fr.robie.machineengine.core.lifecycle.CoreMachineEngine;
import fr.robie.machineengine.core.lifecycle.MachineEngineBuilder;
import fr.robie.machineengine.craftengine.CraftEngineIntegration;
import fr.robie.machineengine.craftengine.CraftEngineMachineHost;
import fr.robie.machineengine.paper.MachineEngineCommands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.core.machine.LoadedMachine;
import fr.robie.machineengine.core.machine.PlaceholderMachine;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockAttemptPlaceEvent;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockBreakEvent;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * MachineEngine plugin entry point (§8.2).
 *
 * <ol>
 *   <li>{@code onLoad}: register the CraftEngine block behavior; addons submit registrations.</li>
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
    private @Nullable fr.robie.machineengine.zmenu.ZMenuMachineMenus menus;
    private int maxMachinesPerChunk;
    private boolean allowBreakingPlaceholders;

    @Override
    public void onLoad() {
        getLogger().info("Loading MachineEngine " + getPluginMeta().getVersion() + " (API " + MachineEngineApi.version() + ")");
        CraftEngineIntegration.registerBlockBehavior();
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

        saveDefaultConfig();
        this.maxMachinesPerChunk = getConfig().getInt("limits.max-machines-per-chunk", 256);
        this.allowBreakingPlaceholders = getConfig().getBoolean("placeholders.allow-breaking", false);

        List<AddonRegistration> registrations = MachineEngineAccess.closeRegistration();
        MachineEngineBuilder builder = new MachineEngineBuilder().logger(getLogger());
        registrations.forEach(builder::addon);
        // Data-driven definitions (§29): plugins/MachineEngine/definitions/*.yml
        fr.robie.machineengine.paper.YamlDefinitions definitions = new fr.robie.machineengine.paper.YamlDefinitions(this);
        builder.definitions(definitions::load, fr.robie.machineengine.paper.YamlDefinitions.vanillaItems());
        CoreMachineEngine started = builder.start();
        this.engine = started;
        MachineEngineAccess.install(started);
        this.host = CraftEngineMachineHost.start(this, started);
        started.setMachineLookup(this.host::machineAt);
        getLogger().info("Started with " + started.enabledAddons().size() + " addon(s) " + started.enabledAddons()
                + (started.disabledAddons().isEmpty() ? "" : "; disabled: " + started.disabledAddons()));
        checkBlockBindings();
        enableMenus();

        getServer().getPluginManager().registerEvents(this, this);
        MachineEngineCommands commands = new MachineEngineCommands(this, () -> this.engine, () -> this.host);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                event -> event.registrar().register(commands.build(), "MachineEngine admin commands", List.of("me")));
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
            this.menus = fr.robie.machineengine.zmenu.ZMenuMachineMenus.enable(this, this.host);
            this.host.setMenus(this.menus);
        } catch (LinkageError | RuntimeException e) {
            getLogger().log(java.util.logging.Level.WARNING, "Could not enable zMenu machine menus", e);
        }
    }

    @Override
    public void onDisable() {
        if (this.menus != null) this.menus.disable();
        this.menus = null;
        if (this.host != null) this.host.shutdown();
        this.host = null;
        this.engine = null;
        MachineEngineAccess.reset();
    }

    @EventHandler
    public void onPluginEnable(org.bukkit.event.server.PluginEnableEvent event) {
        if (event.getPlugin().getName().equals("zMenu") && this.menus == null) enableMenus();
    }

    @EventHandler
    public void onCraftEngineReload(CraftEngineReloadEvent event) {
        // Block configs were parsed again; machine keys are resolved lazily (D14), so only report.
        checkBlockBindings();
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
        if (machine instanceof PlaceholderMachine placeholder && !this.allowBreakingPlaceholders
                && !event.getPlayer().hasPermission(BREAK_PLACEHOLDERS)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Component.text("This machine (" + placeholder.missingType().asString()
                    + ") belongs to an addon that is not installed. Breaking it would destroy its data.", NamedTextColor.RED));
        }
    }

    /** Enforces {@code limits.max-machines-per-chunk} (§23.3). */
    @EventHandler(ignoreCancelled = true)
    public void onCustomBlockPlace(CustomBlockAttemptPlaceEvent event) {
        if (this.host == null || this.maxMachinesPerChunk <= 0 || event.getPlayer().hasPermission(MachineEngineCommands.PERMISSION)) return;
        if (!CraftEngineIntegration.isMachineBlock(event.blockState())) return;
        Location location = event.location();
        int count = this.host.machinesInChunk(location.getWorld().getKey(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
        if (count >= this.maxMachinesPerChunk) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Component.text("This chunk already holds " + count + " machines (limit "
                    + this.maxMachinesPerChunk + ").", NamedTextColor.RED));
        }
    }

    private void checkBlockBindings() {
        if (this.engine == null) return;
        for (String problem : CraftEngineIntegration.unboundBlocks(this.engine.registries())) getLogger().warning(problem);
    }
}
