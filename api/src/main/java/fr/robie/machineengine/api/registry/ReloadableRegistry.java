package fr.robie.machineengine.api.registry;

import net.kyori.adventure.key.Keyed;
import org.jetbrains.annotations.ApiStatus;

/**
 * A registry rebuilt from {@link ReloadableProvider}s on every reload (D5). Reads always see one
 * complete snapshot.
 */
@ApiStatus.NonExtendable
public interface ReloadableRegistry<T extends Keyed> extends Registry<T> {

    /**
     * @return a counter incremented on every successful swap. Holders of keys into this registry
     * re-resolve them when it changes (§7.3).
     */
    long generation();
}
