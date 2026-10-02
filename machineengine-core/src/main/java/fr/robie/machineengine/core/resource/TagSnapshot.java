package fr.robie.machineengine.core.resource;

import fr.robie.machineengine.api.item.ItemTagContribution;
import fr.robie.machineengine.api.resource.ResourceType;
import fr.robie.machineengine.api.resource.tag.ResourceTags;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Resource and item tags of one reload. Item tags combine addon contributions (items and included
 * tags, followed recursively and cycle-safe) with the platform's own tags.
 */
public final class TagSnapshot implements ResourceTags {
    private final Map<Key, Set<ResourceType>> resources;
    private final Map<Key, Set<Key>> itemMembers;
    private final Map<Key, Set<Key>> itemIncludes;
    private final ItemTagSource platform;

    public TagSnapshot(@NotNull Map<Key, Set<ResourceType>> resources, @NotNull Iterable<ItemTagContribution> items,
                       @NotNull ItemTagSource platform) {
        this.resources = Map.copyOf(resources);
        Map<Key, Set<Key>> members = new LinkedHashMap<>();
        Map<Key, Set<Key>> includes = new LinkedHashMap<>();
        for (ItemTagContribution contribution : items) {
            members.computeIfAbsent(contribution.tag(), tag -> new LinkedHashSet<>()).addAll(contribution.items());
            includes.computeIfAbsent(contribution.tag(), tag -> new LinkedHashSet<>()).addAll(contribution.includes());
        }
        members.replaceAll((tag, set) -> Set.copyOf(set));
        includes.replaceAll((tag, set) -> Set.copyOf(set));
        this.itemMembers = Map.copyOf(members);
        this.itemIncludes = Map.copyOf(includes);
        this.platform = platform;
    }

    @Override
    public @NotNull Set<ResourceType> members(@NotNull Key tag) {
        return this.resources.getOrDefault(tag, Set.of());
    }

    @Override
    public @NotNull Set<Key> tags() {
        return this.resources.keySet();
    }

    @Override
    public boolean containsItem(@NotNull Key tag, @NotNull Key item) {
        return containsItem(tag, item, new HashSet<>());
    }

    private boolean containsItem(Key tag, Key item, Set<Key> visited) {
        if (!visited.add(tag)) return false;
        if (this.itemMembers.getOrDefault(tag, Set.of()).contains(item)) return true;
        for (Key included : this.itemIncludes.getOrDefault(tag, Set.of())) {
            if (containsItem(included, item, visited)) return true;
        }
        return this.platform.contains(tag, item);
    }

    /** Contributed item tags, for inspection. */
    public @NotNull Set<Key> itemTags() {
        return this.itemMembers.keySet();
    }
}
