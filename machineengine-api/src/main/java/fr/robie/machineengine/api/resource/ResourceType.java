package fr.robie.machineengine.api.resource;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.Keyed;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * A kind of resource within a {@link ResourceCategory}, e.g. {@code energyplugin:fe} or
 * {@code mymod:oil}. Runtime identity is the registered object (D9): compare by reference.
 */
public final class ResourceType implements Keyed {
    private final Key key;
    private final ResourceCategory category;
    private final ResourceComponents defaultComponents;
    // Created lazily: building it in the constructor would leak `this`. Benign race (immutable value).
    private ResourceVariant defaultVariant;

    private ResourceType(Key key, ResourceCategory category, ResourceComponents defaultComponents) {
        this.key = key;
        this.category = category;
        this.defaultComponents = defaultComponents;
    }

    public static @NotNull ResourceType of(@NotNull Key key, @NotNull ResourceCategory category) {
        return new ResourceType(Objects.requireNonNull(key), Objects.requireNonNull(category), ResourceComponents.EMPTY);
    }

    public static @NotNull ResourceType of(@NotNull Key key, @NotNull ResourceCategory category,
                                           @NotNull ResourceComponents defaultComponents) {
        return new ResourceType(Objects.requireNonNull(key), Objects.requireNonNull(category), Objects.requireNonNull(defaultComponents));
    }

    @Override
    public @NotNull Key key() {
        return this.key;
    }

    public @NotNull ResourceCategory category() {
        return this.category;
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
