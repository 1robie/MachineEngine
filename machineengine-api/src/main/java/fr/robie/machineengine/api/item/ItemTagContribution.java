package fr.robie.machineengine.api.item;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.Keyed;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.Set;

/**
 * Adds items to an item tag (e.g. {@code c:ores/osmium}). Item tags are reloadable and open: several
 * addons may contribute to one tag. Besides these contributions, a tag also contains what the platform
 * reports for it (on Paper: vanilla tags and CraftEngine item tags).
 *
 * @param key      unique id of this contribution
 * @param tag      the item tag contributed to
 * @param items    item ids added to the tag
 * @param includes other item tags whose items are added too (e.g. {@code minecraft:iron_ores})
 */
public record ItemTagContribution(@NotNull Key key, @NotNull Key tag, @NotNull Set<Key> items, @NotNull Set<Key> includes)
        implements Keyed {

    public ItemTagContribution {
        Objects.requireNonNull(key);
        Objects.requireNonNull(tag);
        items = Set.copyOf(items);
        includes = Set.copyOf(includes);
    }
}
