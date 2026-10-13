package fr.robie.machineengine.craftengine;

import fr.robie.messageflow.formatter.Placeholder;
import fr.robie.messageflow.logger.Logger;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Asks CraftEngine's anti-grief integration (WorldGuard, Lands, GriefPrevention …) whether a player
 * may open a container at a location (V5). CraftEngine relocates AntiGriefLib inside its plugin jar,
 * so it is reached reflectively, resolved once. If it cannot be reached, access is allowed and a
 * warning is logged once.
 */
final class CraftEngineProtection {
    private static final String LIBRARY = "net.momirealms.craftengine.libraries.antigrieflib.";

    private boolean resolved;
    private Object provider;
    private Method test;
    private Object openContainer;

    boolean canOpen(@NotNull Player player, @NotNull Location location) {
        if (!this.resolved) resolve();
        if (this.provider == null) return true;
        try {
            return (Boolean) this.test.invoke(this.provider, player, this.openContainer, location);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Logger.warn("[craftengine]", "CraftEngine's protection check failed (%error%); allowing access", Placeholder.of("error", e.toString()));
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
            Logger.warn("[craftengine]", "CraftEngine's protection integration is not available (%error%); machine menus open without claim checks",
                    Placeholder.of("error", e.toString()));
        }
    }
}
