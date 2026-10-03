package fr.robie.machineengine.paper;

import fr.robie.machineengine.api.resource.ResourceVariant;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * How resources are named in menus (the {@code storage_<id>_name} placeholders), as MiniMessage text:
 * addons name their own resources, e.g. {@code <lang:chemical.mekanized.hydrogen>} (translated on each
 * player's client). Without a name, the resource type's key.
 */
public final class ResourceNames {
    private static final List<Function<ResourceVariant, String>> NAMERS = new CopyOnWriteArrayList<>();

    private ResourceNames() {
    }

    /** Adds a namer; it returns {@code null} for resources it does not name. */
    public static void register(@NotNull Function<ResourceVariant, String> namer) {
        NAMERS.add(namer);
    }

    public static void unregister(@NotNull Function<ResourceVariant, String> namer) {
        NAMERS.remove(namer);
    }

    /** The name of {@code variant}, or {@code "Empty"} for no resource. */
    public static @NotNull String of(@Nullable ResourceVariant variant) {
        if (variant == null) return "Empty";
        for (Function<ResourceVariant, String> namer : NAMERS) {
            String name = namer.apply(variant);
            if (name != null) return name;
        }
        return variant.type().key().asString();
    }
}
