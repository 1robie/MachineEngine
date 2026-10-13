package fr.robie.machineengine.plugin;

import fr.robie.machineengine.api.platform.NmsAccess;
import fr.robie.machineengine.nms.ReflectiveNms;
import org.bukkit.Bukkit;
import fr.robie.messageflow.formatter.Placeholder;
import fr.robie.messageflow.logger.Logger;
import org.jetbrains.annotations.NotNull;


/**
 * Picks the nms module of the running Minecraft version (D24, D25). Only that module's class is
 * loaded: nms:v26_3 is Java 25 bytecode and must never load on a 1.20.6 server (Java 21). Versions
 * without a module of their own use {@link ReflectiveNms}.
 */
final class NmsLoader {
    private NmsLoader() {
    }

    static @NotNull NmsAccess load() {
        String version = Bukkit.getMinecraftVersion();
        String module = switch (version) {
            case "1.20.6" -> "v1_20_6";
            case "26.3" -> "v26_3";
            default -> null;
        };
        if (module == null) {
            Logger.info("[nms]", "No version module for Minecraft %version%: version-specific calls use reflection", Placeholder.of("version", version));
            return new ReflectiveNms();
        }
        try {
            NmsAccess access = (NmsAccess) Class.forName("fr.robie.machineengine.nms." + module + ".VersionNms")
                    .getDeclaredConstructor().newInstance();
            Logger.info("[nms]", "Minecraft %version%: version module %module%", Placeholder.of("version", version, "module", access.name()));
            return access;
        } catch (ReflectiveOperationException | LinkageError e) {
            Logger.warn("[nms]", "Could not load the version module %module%; using reflection", e, Placeholder.of("module", module));
            return new ReflectiveNms();
        }
    }
}
