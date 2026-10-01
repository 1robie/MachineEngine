package fr.robie.machineengine.plugin;

import fr.robie.machineengine.api.MachineEngineApi;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * MachineEngine plugin entry point. Wires the Paper integration and the CraftEngine adapter
 * together; the lifecycle phases of ARCHITECTURE.md §8.2 are implemented from M1 on.
 */
public final class MachineEnginePlugin extends JavaPlugin {

    @Override
    public void onLoad() {
        getLogger().info("Loading MachineEngine " + getPluginMeta().getVersion()
                + " (API " + MachineEngineApi.version() + ")");
    }

    @Override
    public void onEnable() {
        // Paper already refuses to load MachineEngine without CraftEngine (required dependency
        // in paper-plugin.yml); this guards against a broken or disabled CraftEngine.
        Plugin craftEngine = getServer().getPluginManager().getPlugin("CraftEngine");
        if (craftEngine == null || !craftEngine.isEnabled()) {
            getLogger().severe("CraftEngine is required but is not enabled. Disabling MachineEngine.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getLogger().info("Using CraftEngine " + craftEngine.getPluginMeta().getVersion());
    }
}
