package fr.robie.machineengine.craftengine;

import fr.robie.machineengine.api.data.DataMap;
import fr.robie.machineengine.api.resource.ResourceStack;
import fr.robie.machineengine.api.resource.ResourceVariant;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.core.data.DataBinary;
import fr.robie.machineengine.core.machine.CoreMachines;
import fr.robie.machineengine.paper.PaperItems;
import net.kyori.adventure.text.Component;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keep-contents (§20.5): a machine block with {@code keep_contents: true} keeps its state in its
 * dropped item instead of spilling what it holds.
 *
 * <p>Breaking packs the machine; the player's {@link BlockDropItemEvent} (same tick, after the block
 * is gone) then writes the state into the dropped block item. A break without that event (explosion,
 * creative mode, a drop-less loot table) drops the contents as usual one tick later, so nothing is
 * ever lost or duplicated. Placing the item restores the state once CraftEngine has accepted the
 * placement ({@code loadCustomDataFromItem}), after every cancellable place event.</p>
 */
public final class KeptContents implements Listener {
    public static final NamespacedKey KEY = new NamespacedKey("machineengine", "contents");
    private static final int LORE_LINES = 5;

    private final Plugin plugin;
    private final CraftEngineMachineHost host;
    private final Map<WorldPosition, Pending> pending = new ConcurrentHashMap<>();

    KeptContents(Plugin plugin, CraftEngineMachineHost host) {
        this.plugin = plugin;
        this.host = host;
    }

    private record Pending(CoreMachines.Packed packed, net.momirealms.craftengine.core.util.Key item) {
    }

    /** A machine was broken and packed (owner thread). */
    void packed(WorldPosition position, CoreMachines.Packed packed, net.momirealms.craftengine.core.util.Key blockId) {
        Pending entry = new Pending(packed, blockId);
        this.pending.put(position, entry);
        World world = world(position);
        if (world == null) {
            this.pending.remove(position, entry);
            return;
        }
        Bukkit.getRegionScheduler().runDelayed(this.plugin, world, position.position().chunkX(), position.position().chunkZ(), task -> {
            // No drop event claimed the state: drop the contents as a normal break would.
            if (this.pending.remove(position, entry)) this.host.dropResources(position, packed.contents());
        }, 1);
    }

    /** Lowest priority, so auto-pickup and other drop plugins see the item with its contents. */
    @EventHandler(priority = EventPriority.LOWEST)
    void onBlockDrop(BlockDropItemEvent event) {
        org.bukkit.block.Block block = event.getBlock();
        WorldPosition position = WorldPosition.of(block.getWorld().getKey(), block.getX(), block.getY(), block.getZ());
        Pending entry = this.pending.get(position);
        if (entry == null) return;
        for (Item drop : event.getItems()) {
            ItemStack stack = drop.getItemStack();
            if (stack.getAmount() != 1 || !entry.item().equals(CraftEngineItems.getCustomItemId(stack))) continue;
            if (!this.pending.remove(position, entry)) return;
            drop.setItemStack(withContents(stack, entry.packed()));
            return;
        }
    }

    /** The packed state in {@code stack}, or {@code null} if it has none or it is unreadable. */
    @Nullable DataMap read(@NotNull ItemStack stack) {
        if (!stack.hasItemMeta()) return null;
        byte[] bytes = stack.getItemMeta().getPersistentDataContainer().get(KEY, PersistentDataType.BYTE_ARRAY);
        if (bytes == null) return null;
        try {
            return DataBinary.read(bytes);
        } catch (IOException e) {
            this.host.logger().warning("Ignored unreadable machine contents in an item: " + e.getMessage());
            return null;
        }
    }

    static ItemStack withContents(ItemStack stack, CoreMachines.Packed packed) {
        ItemStack result = stack.clone();
        result.editMeta(meta -> {
            meta.getPersistentDataContainer().set(KEY, PersistentDataType.BYTE_ARRAY, DataBinary.write(packed.state()));
            List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            lore.addAll(describe(packed.contents()));
            meta.lore(lore);
        });
        return result;
    }

    /** Lore lines (translated, see {@link fr.robie.machineengine.paper.Messages}): a header, then one line per resource, merged by variant. */
    static List<Component> describe(List<ResourceStack> contents) {
        Map<ResourceVariant, Long> totals = new LinkedHashMap<>();
        for (ResourceStack stack : contents) totals.merge(stack.variant(), stack.amount(), Long::sum);
        List<Component> lines = new ArrayList<>();
        lines.add(fr.robie.machineengine.paper.Messages.contentsKept());
        int shown = 0;
        for (Map.Entry<ResourceVariant, Long> entry : totals.entrySet()) {
            if (shown++ == LORE_LINES) {
                lines.add(fr.robie.machineengine.paper.Messages.contentsMore(totals.size() - LORE_LINES));
                break;
            }
            lines.add(fr.robie.machineengine.paper.Messages.contentsEntry(entry.getValue(), name(entry.getKey())));
        }
        return lines;
    }

    private static Component name(ResourceVariant variant) {
        ItemStack item = PaperItems.toItemStack(variant, 1);
        return item != null ? Component.translatable(item) : Component.text(variant.type().key().asString());
    }

    private static @Nullable World world(WorldPosition position) {
        return Bukkit.getWorld(new NamespacedKey(position.world().namespace(), position.world().value()));
    }
}
