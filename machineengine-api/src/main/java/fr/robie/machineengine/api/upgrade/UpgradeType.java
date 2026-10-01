package fr.robie.machineengine.api.upgrade;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.Keyed;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/**
 * A kind of upgrade (ARCHITECTURE.md §14). Each installed unit contributes {@link #modifiers()}
 * once; installing three units applies them three times.
 */
public final class UpgradeType implements Keyed {
    private final Key key;
    private final List<Modifier> modifiers;
    private final int maxStack;

    private UpgradeType(Key key, List<Modifier> modifiers, int maxStack) {
        this.key = key;
        this.modifiers = List.copyOf(modifiers);
        this.maxStack = maxStack;
    }

    public static @NotNull UpgradeType of(@NotNull Key key, int maxStack, @NotNull Modifier... modifiers) {
        if (maxStack < 1) throw new IllegalArgumentException("maxStack must be at least 1");
        return new UpgradeType(Objects.requireNonNull(key), List.of(modifiers), maxStack);
    }

    @Override
    public @NotNull Key key() {
        return this.key;
    }

    /** Modifiers of one unit. */
    public @NotNull List<Modifier> modifiers() {
        return this.modifiers;
    }

    /** Most units one machine may hold. */
    public int maxStack() {
        return this.maxStack;
    }

    @Override
    public String toString() {
        return "UpgradeType[" + this.key.asString() + "]";
    }
}
