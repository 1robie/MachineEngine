package fr.robie.machineengine.craftengine;

import fr.robie.machineengine.api.config.BlockOptions;
import fr.robie.messageflow.formatter.Placeholder;
import fr.robie.messageflow.logger.Logger;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/** Reads a block behavior's {@code storages:}, {@code ports:} and {@code options:} sections into {@link BlockOptions} (D34). */
final class BlockConfigOptions {
    private BlockConfigOptions() {
    }

    static @NotNull BlockOptions read(@NotNull BlockDefinition block, @NotNull ConfigSection section) {
        return BlockOptions.of(String.valueOf(block.id()), values(section, "options"), values(section, "storages"),
                values(section, "ports"), BlockConfigOptions::warn);
    }

    private static Map<String, Object> values(ConfigSection section, String key) {
        ConfigSection child = section.keySet().contains(key) ? section.getSection(key) : null;
        return child == null ? null : child.values();
    }

    static void warn(String message) {
        Logger.warn("[craftengine]", "%message%", Placeholder.of("message", message));
    }
}
