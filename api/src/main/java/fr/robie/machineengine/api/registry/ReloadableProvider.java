package fr.robie.machineengine.api.registry;

import net.kyori.adventure.key.Keyed;
import org.jetbrains.annotations.NotNull;

/**
 * Supplies entries of a reloadable registry. Called on the initial load and on every reload, and
 * must register its complete set of entries each time.
 */
@FunctionalInterface
public interface ReloadableProvider<T extends Keyed> {

    void provide(@NotNull ReloadContext<T> context);
}
