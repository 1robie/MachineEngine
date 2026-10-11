package fr.robie.machineengine.api.paper;

import fr.robie.machineengine.api.resource.ResourceVariant;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * The color of resources in menus (the {@code storage_<id>_color} placeholders), as {@code 0xRRGGBB}:
 * tinted fill bars take it, e.g. through zMenu's {@code dyed-color} component. A resource's color is its
 * type's {@link fr.robie.machineengine.api.resource.ResourceType#color()}; colorers color types by rule
 * for the ones without one. Without a color, white (the bar's own texture).
 *
 * <p>Order: the type's color, then the colorers in registration order, then white.</p>
 */
public final class ResourceColors {
    public static final int WHITE = 0xFFFFFF;
    private static final List<Function<ResourceVariant, Integer>> COLORERS = new CopyOnWriteArrayList<>();

    private ResourceColors() {
    }

    /** Adds a colorer; it returns {@code null} for resources it does not color. */
    public static void register(@NotNull Function<ResourceVariant, Integer> colorer) {
        COLORERS.add(colorer);
    }

    public static void unregister(@NotNull Function<ResourceVariant, Integer> colorer) {
        COLORERS.remove(colorer);
    }

    /** The color of {@code variant} ({@code  0xRRGGBB}), or {@link #WHITE} for no resource or no color. */
    public static int of(@Nullable ResourceVariant variant) {
        if (variant == null) return WHITE;
        Integer own = variant.type().color();
        if (own != null) return own;
        for (Function<ResourceVariant, Integer> colorer : COLORERS) {
            Integer color = colorer.apply(variant);
            if (color != null) return color & 0xFFFFFF;
        }
        return WHITE;
    }

    /** {@code #RRGGBB}, the form zMenu and MiniMessage read. */
    public static @NotNull String hex(int color) {
        return String.format("#%06X", color & 0xFFFFFF);
    }
}
