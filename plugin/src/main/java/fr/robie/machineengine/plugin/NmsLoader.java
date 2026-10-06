package fr.robie.machineengine.plugin;

import fr.robie.machineengine.nms.NmsAccess;
import fr.robie.machineengine.nms.ReflectiveNms;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Picks the nms module of the running Minecraft version (D24, D25). Only that module's class is
 * loaded: nms:v26_3 is Java 25 bytecode and must never load on a 1.20.6 server (Java 21). Versions
 * without a module of their own use {@link ReflectiveNms}.
 */
final class NmsLoader {
    private NmsLoader() {
    }

    static @NotNull NmsAccess load(@NotNull Logger logger) {
        String version = Bukkit.getMinecraftVersion();
        String module = switch (version) {
            case "1.20.6" -> "v1_20_6";
            case "26.3" -> "v26_3";
            default -> null;
        };
        if (module == null) {
            logger.info("No version module for Minecraft " + version + ": version-specific calls use reflection");
            return new ReflectiveNms();
        }
        try {
            NmsAccess access = (NmsAccess) Class.forName("fr.robie.machineengine.nms." + module + ".VersionNms")
                    .getDeclaredConstructor().newInstance();
            logger.info("Minecraft " + version + ": version module " + access.name());
            return access;
        } catch (ReflectiveOperationException | LinkageError e) {
            logger.log(Level.WARNING, "Could not load the version module " + module + "; using reflection", e);
            return new ReflectiveNms();
        }
    }
}
