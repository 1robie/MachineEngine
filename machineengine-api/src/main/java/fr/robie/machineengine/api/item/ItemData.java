package fr.robie.machineengine.api.item;

import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Which item a {@link Items#ITEM} resource is: the item id plus the platform's serialized form of
 * one such item (Paper: {@code ItemStack#serializeAsBytes}). Two items stack in a machine exactly
 * when both are equal.
 *
 * @param item the item id, e.g. {@code minecraft:iron_ingot} or a CraftEngine item id
 * @param data the serialized single item (never mutated)
 */
public record ItemData(@NotNull Key item, byte @NotNull [] data) {

    public ItemData {
        Objects.requireNonNull(item);
        data = data.clone();
    }

    @Override
    public byte @NotNull [] data() {
        return this.data.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ItemData that && this.item.equals(that.item) && Arrays.equals(this.data, that.data);
    }

    @Override
    public int hashCode() {
        return 31 * this.item.hashCode() + Arrays.hashCode(this.data);
    }

    @Override
    public @NotNull String toString() {
        return this.item.asString() + "#" + HexFormat.of().formatHex(this.data, 0, Math.min(4, this.data.length));
    }
}
