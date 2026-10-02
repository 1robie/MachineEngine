package fr.robie.machineengine.craftengine;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * Asks CraftEngine's anti-grief integration (WorldGuard, Lands, GriefPrevention …) whether a player
 * may open a container at a location (V5). CraftEngine relocates AntiGriefLib inside its plugin jar,
 * so it is reached reflectively, resolved once. If it cannot be reached, access is allowed and a
 * warning is logged once.
 */
final class CraftEngineProtection {
    private static final String LIBRARY = "net.momirealms.craftengine.libraries.antigrieflib.";

    private final Logger logger;
    private boolean resolved;
    private Object provider;
    private Method test;
    private Object openContainer;

    CraftEngineProtection(Logger logger) {
        this.logger = logger;
    }

    boolean canOpen(@NotNull Player player, @NotNull Location location) {
        if (!this.resolved) resolve();
        if (this.provider == null) return true;
        try {
            return (Boolean) this.test.invoke(this.provider, player, this.openContainer, location);
        } catch (ReflectiveOperationException | RuntimeException e) {
            this.logger.warning("CraftEngine's protection check failed (" + e + "); allowing access");
            return true;
        }
    }

    private void resolve() {
        this.resolved = true;
        try {
            Class<?> craftEngine = Class.forName("net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine");
            Object instance = craftEngine.getMethod("instance").invoke(null);
            Object provider = craftEngine.getMethod("antiGriefProvider").invoke(instance);
            Class<?> flag = Class.forName(LIBRARY + "Flag", true, provider.getClass().getClassLoader());
            Field field = flag.getField("OPEN_CONTAINER");
            this.test = provider.getClass().getMethod("test", Player.class, flag, Object.class);
            this.openContainer = field.get(null);
            this.provider = provider;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            this.logger.warning("CraftEngine's protection integration is not available (" + e
                    + "); machine menus open without claim checks");
        }
    }
}
