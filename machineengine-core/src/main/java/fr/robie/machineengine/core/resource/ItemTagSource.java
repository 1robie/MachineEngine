package fr.robie.machineengine.core.resource;

import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;

/**
 * Item tags known to the platform (on Paper: vanilla tags and CraftEngine item tags). Consulted after
 * the tags addons contribute ({@code machineengine:item_tag}); must be safe to call from any thread.
 */
@FunctionalInterface
public interface ItemTagSource {
    ItemTagSource NONE = (tag, item) -> false;

    boolean contains(@NotNull Key tag, @NotNull Key item);
}
