package fr.robie.machineengine.api.paper;

import fr.robie.machineengine.api.item.ItemData;
import fr.robie.machineengine.api.item.ItemResources;
import fr.robie.machineengine.api.item.Items;
import fr.robie.machineengine.api.item.ResourceItem;
import fr.robie.machineengine.api.resource.ResourceVariant;
import net.kyori.adventure.key.Key;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.core.item.Item;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Resources held by items on Paper (D32): what an item holds comes from its {@link ResourceItem}
 * CraftEngine behaviors, the amounts live in its CraftEngine item data under
 * {@code minecraft:custom_data} → {@code machineengine.resources.<type>}.
 *
 * <p>Methods that change an {@link ItemStack} change the given stack itself, whether it is a server
 * stack (from an inventory) or a plain Bukkit one.</p>
 */
public final class PaperItemResources implements ItemResources.Access {
    /** The {@link ItemResources.Access} the engine installs on Paper. */
    public static final PaperItemResources ACCESS = new PaperItemResources();

    private static final String ROOT = "machineengine";
    private static final String RESOURCES = "resources";
    private static final String OLD_PDC = "PublicBukkitValues";

    private static final List<Consumer<Item>> MIGRATIONS = new CopyOnWriteArrayList<>();
    private static final List<Consumer<ItemStack>> CHANGE_LISTENERS = new CopyOnWriteArrayList<>();

    private PaperItemResources() {
    }

    // -- items -----------------------------------------------------------------------------------

    /** The behavior of {@code stack} that holds {@code resource}, or {@code null}. */
    public static @Nullable ResourceItem behavior(@NotNull ItemStack stack, @NotNull Key resource) {
        return behavior(BukkitAdaptor.adapt(stack), resource);
    }

    /** Every resource {@code stack} can hold, in the order of its behaviors. */
    public static @NotNull List<ResourceItem> behaviors(@NotNull ItemStack stack) {
        List<ResourceItem> found = new ArrayList<>();
        BukkitAdaptor.adapt(stack).getBehavior().ifPresent(behavior -> behavior.let(ResourceItem.class, found::add));
        return found;
    }

    /** How much of {@code resource} {@code stack} holds when full; 0 if it cannot hold it. */
    public static long capacity(@NotNull ItemStack stack, @NotNull Key resource) {
        Item item = BukkitAdaptor.adapt(stack);
        ResourceItem behavior = behavior(item, resource);
        return behavior == null ? 0 : behavior.capacity(item);
    }

    public static long amount(@NotNull ItemStack stack, @NotNull Key resource) {
        Item item = BukkitAdaptor.adapt(stack);
        migrate(item);
        return read(item, resource);
    }

    /**
     * Sets the amount of {@code resource} in {@code stack}, clamped to its capacity; nothing changes if
     * it cannot hold it.
     *
     * @return {@code stack}, changed
     */
    public static @NotNull ItemStack withAmount(@NotNull ItemStack stack, @NotNull Key resource, long amount) {
        Item item = BukkitAdaptor.adapt(stack);
        ResourceItem behavior = behavior(item, resource);
        if (behavior == null) return stack;
        migrate(item);
        long clamped = Math.clamp(amount, 0, behavior.capacity(item));
        if (clamped == 0) item.removeTag(ROOT, RESOURCES, resource.asString());
        else item.setTag(clamped, ROOT, RESOURCES, resource.asString());
        // A server stack was changed in place; a plain Bukkit stack was copied by CraftEngine: copy back.
        ItemStack result = (ItemStack) item.platformItem();
        if (result != stack) stack.setItemMeta(result.getItemMeta());
        for (Consumer<ItemStack> listener : CHANGE_LISTENERS) listener.accept(stack);
        return stack;
    }

    /**
     * Adds (or with a negative {@code amount}, takes) up to {@code amount}, within the capacity.
     *
     * @return the amount actually moved, signed like {@code amount}
     */
    public static long add(@NotNull ItemStack stack, @NotNull Key resource, long amount) {
        long stored = amount(stack, resource);
        long capacity = capacity(stack, resource);
        long target = Math.clamp(stored + amount, 0, Math.max(capacity, stored));
        if (target != stored) withAmount(stack, resource, target);
        return target - stored;
    }

    // -- extension points --------------------------------------------------------------------------

    /**
     * Converts data an addon kept in Bukkit's persistent data before D32: a {@code long} under the
     * persistent data key {@code pdcKey} becomes the amount of the resource {@code resource} returns
     * for the item ({@code null}: drop the value). Runs the first time such an item is read.
     */
    public static void migratePdcLong(@NotNull String pdcKey, @NotNull Function<Item, @Nullable Key> resource) {
        Objects.requireNonNull(pdcKey);
        Objects.requireNonNull(resource);
        MIGRATIONS.add(item -> {
            if (!(item.getTagAsJava(OLD_PDC, pdcKey) instanceof Number old)) return;
            item.removeTag(OLD_PDC, pdcKey);
            Key target = resource.apply(item);
            if (target != null && old.longValue() > 0 && !item.hasTag(ROOT, RESOURCES, target.asString())) {
                item.setTag(old.longValue(), ROOT, RESOURCES, target.asString());
            }
        });
    }

    public static void migratePdcLong(@NotNull String pdcKey, @NotNull Key resource) {
        migratePdcLong(pdcKey, item -> resource);
    }

    /** Called with the resulting stack after every amount change, e.g. to update the lore. */
    public static void onChange(@NotNull Consumer<ItemStack> listener) {
        CHANGE_LISTENERS.add(Objects.requireNonNull(listener));
    }

    // -- ItemResources.Access ----------------------------------------------------------------------

    @Override
    public long capacity(@NotNull ResourceVariant item, @NotNull Key resource) {
        ItemStack stack = PaperItems.toItemStack(item, 1);
        return stack == null ? 0 : capacity(stack, resource);
    }

    @Override
    public long amount(@NotNull ResourceVariant item, @NotNull Key resource) {
        ItemStack stack = PaperItems.toItemStack(item, 1);
        return stack == null ? 0 : amount(stack, resource);
    }

    @Override
    public @NotNull ResourceVariant withAmount(@NotNull ResourceVariant item, @NotNull Key resource, long amount) {
        ItemStack stack = PaperItems.toItemStack(item, 1);
        ItemData data = item.type() == Items.ITEM ? item.components().get(Items.DATA) : null;
        if (stack == null || data == null) return item;
        return PaperItems.toResource(withAmount(stack, resource, amount), data.item()).variant();
    }

    // -- internals ---------------------------------------------------------------------------------

    private static @Nullable ResourceItem behavior(Item item, Key resource) {
        ResourceItem[] found = {null};
        item.getBehavior().ifPresent(behavior -> behavior.let(ResourceItem.class, candidate -> {
            if (found[0] == null && candidate.resource().equals(resource)) found[0] = candidate;
        }));
        return found[0];
    }

    private static long read(Item item, Key resource) {
        return item.getTagAsJava(ROOT, RESOURCES, resource.asString()) instanceof Number amount ? amount.longValue() : 0;
    }

    private static void migrate(Item item) {
        if (MIGRATIONS.isEmpty() || !item.hasTag(OLD_PDC)) return;
        for (Consumer<Item> migration : MIGRATIONS) migration.accept(item);
    }
}
