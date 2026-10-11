package fr.robie.machineengine.api.resource;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.Keyed;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * A kind of resource within a {@link ResourceCategory}, e.g. {@code energyplugin:fe} or
 * {@code mymod:oil}. Runtime identity is the registered object (D9): compare by reference.
 *
 * <p>A type may carry its menu {@linkplain #color() color}, set once when it is built and never changed:
 * a server owner sets it in {@code config.yml} (water, lava) or a definition's {@code color:}, and code
 * passes it to the factories. Menus draw tinted bars with it ({@code api.paper.ResourceColors}).</p>
 */
public final class ResourceType implements Keyed {
    private final Key key;
    private final ResourceCategory category;
    private final ResourceComponents defaultComponents;
    private final @Nullable Integer color;
    // Created lazily: building it in the constructor would leak `this`. Benign race (immutable value).
    private ResourceVariant defaultVariant;

    private ResourceType(Key key, ResourceCategory category, ResourceComponents defaultComponents, @Nullable Integer color) {
        this.key = key;
        this.category = category;
        this.defaultComponents = defaultComponents;
        this.color = color;
    }

    public static @NotNull ResourceType of(@NotNull Key key, @NotNull ResourceCategory category) {
        return new ResourceType(Objects.requireNonNull(key), Objects.requireNonNull(category), ResourceComponents.EMPTY, null);
    }

    public static @NotNull ResourceType of(@NotNull Key key, @NotNull ResourceCategory category,
                                           @NotNull ResourceComponents defaultComponents) {
        return new ResourceType(Objects.requireNonNull(key), Objects.requireNonNull(category), Objects.requireNonNull(defaultComponents), null);
    }

    /** A type with a menu color ({@code 0xRRGGBB}; bits above 24 are ignored). */
    public static @NotNull ResourceType of(@NotNull Key key, @NotNull ResourceCategory category, int color) {
        return new ResourceType(Objects.requireNonNull(key), Objects.requireNonNull(category), ResourceComponents.EMPTY, color & 0xFFFFFF);
    }

    /** A type with default components and a menu color ({@code 0xRRGGBB}; bits above 24 are ignored). */
    public static @NotNull ResourceType of(@NotNull Key key, @NotNull ResourceCategory category,
                                           @NotNull ResourceComponents defaultComponents, int color) {
        return new ResourceType(Objects.requireNonNull(key), Objects.requireNonNull(category), Objects.requireNonNull(defaultComponents),
                color & 0xFFFFFF);
    }

    /**
     * Parses a color written as {@code #RRGGBB} (or {@code RRGGBB}), as config.yml and definitions write it.
     *
     * @return the color as {@code 0xRRGGBB}, or {@code null} if the text is not one
     */
    public static @Nullable Integer parseColor(@Nullable String text) {
        if (text == null) return null;
        String digits = text.startsWith("#") ? text.substring(1) : text;
        if (digits.length() != 6) return null;
        for (int i = 0; i < 6; i++) {
            if (Character.digit(digits.charAt(i), 16) < 0) return null;
        }
        return Integer.parseInt(digits, 16);
    }

    @Override
    public @NotNull Key key() {
        return this.key;
    }

    public @NotNull ResourceCategory category() {
        return this.category;
    }

    /** The menu color ({@code 0xRRGGBB}), or {@code null} if the type has none. */
    public @Nullable Integer color() {
        return this.color;
    }

    public @NotNull ResourceComponents defaultComponents() {
        return this.defaultComponents;
    }

    /** @return this type with its default components */
    public @NotNull ResourceVariant variant() {
        ResourceVariant variant = this.defaultVariant;
        if (variant == null) {
            variant = new ResourceVariant(this, this.defaultComponents);
            this.defaultVariant = variant;
        }
        return variant;
    }

    public @NotNull ResourceStack stack(long amount) {
        return new ResourceStack(variant(), amount);
    }

    @Override
    public String toString() {
        return "ResourceType[" + this.key.asString() + "]";
    }
}
