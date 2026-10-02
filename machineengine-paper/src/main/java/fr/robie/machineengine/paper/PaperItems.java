package fr.robie.machineengine.paper;

import fr.robie.machineengine.api.item.ItemData;
import fr.robie.machineengine.api.item.Items;
import fr.robie.machineengine.api.resource.ResourceStack;
import fr.robie.machineengine.api.resource.ResourceVariant;
import net.kyori.adventure.key.Key;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Converts between Paper {@link ItemStack}s and {@link Items#ITEM} resources (§23.4). */
public final class PaperItems {

    private static volatile Function<ItemStack, Key> idResolver = stack -> stack.getType().getKey();

    private PaperItems() {
    }

    /** How an item stack's id is found; the CraftEngine integration reports custom ids (E10). */
    public static void setIdResolver(@NotNull Function<ItemStack, Key> resolver) {
        idResolver = java.util.Objects.requireNonNull(resolver);
    }

    /** The item's id: a CraftEngine custom id for CraftEngine items, else the material key. */
    public static @NotNull Key idOf(@NotNull ItemStack stack) {
        return idResolver.apply(stack);
    }

    /**
     * @param itemId the item's id; pass a CraftEngine id for CraftEngine items, otherwise the
     *               material key is used
     */
    public static @NotNull ResourceStack toResource(@NotNull ItemStack stack, @Nullable Key itemId) {
        Key id = itemId != null ? itemId : idOf(stack);
        return Items.variant(new ItemData(id, stack.asOne().serializeAsBytes())).stack(stack.getAmount());
    }

    public static @NotNull ResourceStack toResource(@NotNull ItemStack stack) {
        return toResource(stack, null);
    }

    /** @return {@code null} if the variant is not an item */
    public static @Nullable ItemStack toItemStack(@NotNull ResourceVariant variant, int amount) {
        ItemData data = variant.type() == Items.ITEM ? variant.components().get(Items.DATA) : null;
        if (data == null) return null;
        return ItemStack.deserializeBytes(data.data()).asQuantity(amount);
    }

    /** Splits an item resource into stacks no larger than the item's max stack size. */
    public static @NotNull List<ItemStack> toItemStacks(@NotNull ResourceStack stack) {
        List<ItemStack> stacks = new ArrayList<>();
        ItemStack one = toItemStack(stack.variant(), 1);
        if (one == null) return stacks;
        long remaining = stack.amount();
        int max = Math.max(1, one.getMaxStackSize());
        while (remaining > 0) {
            int amount = (int) Math.min(max, remaining);
            stacks.add(one.asQuantity(amount));
            remaining -= amount;
        }
        return stacks;
    }

    /**
     * Drops the item resources among {@code resources} at the block's position (owner thread).
     *
     * @return the resources that have no item form and were not dropped
     */
    public static @NotNull List<ResourceStack> drop(@NotNull World world, int x, int y, int z, @NotNull List<ResourceStack> resources) {
        return drop(resources, stack -> world.dropItemNaturally(new Location(world, x + 0.5, y + 0.5, z + 0.5), stack));
    }

    static @NotNull List<ResourceStack> drop(List<ResourceStack> resources, Function<ItemStack, ?> spawn) {
        List<ResourceStack> notItems = new ArrayList<>();
        for (ResourceStack resource : resources) {
            if (resource.type() != Items.ITEM) {
                notItems.add(resource);
                continue;
            }
            toItemStacks(resource).forEach(spawn::apply);
        }
        return notItems;
    }
}
