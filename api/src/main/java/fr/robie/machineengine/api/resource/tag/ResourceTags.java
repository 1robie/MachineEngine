package fr.robie.machineengine.api.resource.tag;

import fr.robie.machineengine.api.resource.ResourceType;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Set;

/** A snapshot of tag contents, rebuilt on every reload of {@code machineengine:resource_tag}. */
public interface ResourceTags {
    ResourceTags EMPTY = of(Map.of());

    static @NotNull ResourceTags of(@NotNull Map<Key, Set<ResourceType>> tags) {
        Map<Key, Set<ResourceType>> copy = Map.copyOf(tags);
        return new ResourceTags() {
            @Override
            public @NotNull Set<ResourceType> members(@NotNull Key tag) {
                return copy.getOrDefault(tag, Set.of());
            }

            @Override
            public @NotNull Set<Key> tags() {
                return copy.keySet();
            }
        };
    }

    /** @return the types in {@code tag}; empty if the tag is unknown */
    @NotNull Set<ResourceType> members(@NotNull Key tag);

    @NotNull Set<Key> tags();

    default boolean contains(@NotNull Key tag, @NotNull ResourceType type) {
        return members(tag).contains(type);
    }

    /**
     * Whether the item {@code item} (its id, e.g. {@code minecraft:iron_ore} or a CraftEngine id) is in
     * the item tag {@code tag}: contributed by addons ({@code machineengine:item_tag}) or reported by
     * the platform (vanilla and CraftEngine tags).
     */
    default boolean containsItem(@NotNull Key tag, @NotNull Key item) {
        return false;
    }
}
