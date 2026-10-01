package fr.robie.machineengine.api.resource.tag;

import fr.robie.machineengine.api.resource.ResourceType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.Keyed;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * Adds resource types to a tag. Tags are reloadable and open: several addons may contribute to
 * the same {@link #tag()}, each through its own contribution {@link #key()}.
 *
 * @param key     unique id of this contribution, e.g. {@code mymod:coolant_fluids}
 * @param tag     the tag contributed to, e.g. {@code machineengine:coolant}
 * @param members the resource types added to the tag
 */
public record ResourceTagContribution(@NotNull Key key, @NotNull Key tag, @NotNull Set<ResourceType> members) implements Keyed {

    public ResourceTagContribution {
        members = Set.copyOf(members);
    }

    public static @NotNull ResourceTagContribution of(@NotNull Key key, @NotNull Key tag, @NotNull ResourceType... members) {
        return new ResourceTagContribution(key, tag, Set.of(members));
    }
}
