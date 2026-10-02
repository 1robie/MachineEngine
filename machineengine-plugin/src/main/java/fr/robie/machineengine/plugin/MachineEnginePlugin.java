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
import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;
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
    private @Nullable CoreMachineEngine engine;
    private @Nullable CraftEngineMachineHost host;

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

        List<AddonRegistration> registrations = MachineEngineAccess.closeRegistration();
        MachineEngineBuilder builder = new MachineEngineBuilder().logger(getLogger());
        registrations.forEach(builder::addon);
        CoreMachineEngine started = builder.start();
        this.engine = started;
        MachineEngineAccess.install(started);
        this.host = CraftEngineMachineHost.start(this, started);
        getLogger().info("Started with " + started.enabledAddons().size() + " addon(s) " + started.enabledAddons()
                + (started.disabledAddons().isEmpty() ? "" : "; disabled: " + started.disabledAddons()));
        checkBlockBindings();

        getServer().getPluginManager().registerEvents(this, this);
        MachineEngineCommands commands = new MachineEngineCommands(() -> this.engine, () -> this.host);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                event -> event.registrar().register(commands.build(), "MachineEngine admin commands", List.of("me")));
    }

    @Override
    public void onDisable() {
        if (this.host != null) this.host.shutdown();
        this.host = null;
        this.engine = null;
        MachineEngineAccess.reset();
    }

    @EventHandler
    public void onCraftEngineReload(CraftEngineReloadEvent event) {
        // Block configs were parsed again; machine keys are resolved lazily (D14), so only report.
        checkBlockBindings();
    }

    private void checkBlockBindings() {
        if (this.engine == null) return;
        for (String problem : CraftEngineIntegration.unboundBlocks(this.engine.registries())) getLogger().warning(problem);
    }
}
