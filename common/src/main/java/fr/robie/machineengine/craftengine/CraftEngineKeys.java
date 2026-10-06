package fr.robie.machineengine.craftengine;

import org.jetbrains.annotations.NotNull;

/**
 * Converts between Adventure keys (MachineEngine API, D6) and CraftEngine's own key type.
 */
public final class CraftEngineKeys {
    private CraftEngineKeys() {
    }

    public static net.momirealms.craftengine.core.util.@NotNull Key toCraftEngine(net.kyori.adventure.key.@NotNull Key key) {
        return net.momirealms.craftengine.core.util.Key.of(key.namespace(), key.value());
    }

    public static net.kyori.adventure.key.@NotNull Key fromCraftEngine(net.momirealms.craftengine.core.util.@NotNull Key key) {
        return net.kyori.adventure.key.Key.key(key.namespace(), key.value());
    }
}
