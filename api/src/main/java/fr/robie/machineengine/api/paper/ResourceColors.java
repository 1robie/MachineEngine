package fr.robie.machineengine.api.paper;

import fr.robie.machineengine.api.resource.Fluids;
import fr.robie.machineengine.api.resource.ResourceVariant;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * The color of resources in menus (the {@code storage_<id>_color} placeholders), as {@code 0xRRGGBB}:
 * tinted fill bars take it, e.g. through zMenu's {@code dyed-color} component. Addons color their own
 * resources; MachineEngine colors water and lava. Without a color, white (the bar's own texture).
 */
public final class ResourceColors {
    public static final int WHITE = 0xFFFFFF;
    /** Vanilla's default water color. */
    public static final int WATER = 0x3F76E4;
    public static final int LAVA = 0xE0641A;
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

    /** The color of {@code variant} ({@code 0xRRGGBB}), or {@link #WHITE} for no resource or no color. */
    public static int of(@Nullable ResourceVariant variant) {
        if (variant == null) return WHITE;
        for (Function<ResourceVariant, Integer> colorer : COLORERS) {
            Integer color = colorer.apply(variant);
            if (color != null) return color & 0xFFFFFF;
        }
        if (variant.type().key().equals(Fluids.WATER_KEY)) return WATER;
        if (variant.type().key().equals(Fluids.LAVA_KEY)) return LAVA;
        return WHITE;
    }

    /** {@code #RRGGBB}, the form zMenu and MiniMessage read. */
    public static @NotNull String hex(int color) {
        return String.format("#%06X", color & 0xFFFFFF);
    }
}
