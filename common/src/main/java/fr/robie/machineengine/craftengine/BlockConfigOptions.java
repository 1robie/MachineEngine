package fr.robie.machineengine.craftengine;

import fr.robie.machineengine.api.config.BlockOptions;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/** Reads a block behavior's {@code storages:} and {@code options:} sections into {@link BlockOptions} (D34). */
final class BlockConfigOptions {
    private BlockConfigOptions() {
    }

    static @NotNull BlockOptions read(@NotNull BlockDefinition block, @NotNull ConfigSection section) {
        return BlockOptions.of(String.valueOf(block.id()), values(section, "options"), values(section, "storages"),
                BlockConfigOptions::warn);
    }

    private static Map<String, Object> values(ConfigSection section, String key) {
        ConfigSection child = section.keySet().contains(key) ? section.getSection(key) : null;
        return child == null ? null : child.values();
    }

    /** Block configs are parsed before the host exists: warnings go to the host's logger once it does. */
    static void warn(String message) {
        CraftEngineMachineHost host = CraftEngineMachineHost.current();
        if (host != null) {
            host.logger().warning(message);
        } else {
            Bukkit.getLogger().warning("[MachineEngine] " + message);
        }
    }
}
