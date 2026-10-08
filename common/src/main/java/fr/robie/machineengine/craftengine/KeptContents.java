package fr.robie.machineengine.craftengine;

import fr.robie.machineengine.api.MachineEngine;
import fr.robie.machineengine.api.data.DataMap;
import fr.robie.machineengine.api.platform.Scheduler;
import fr.robie.machineengine.api.resource.ResourceStack;
import fr.robie.machineengine.api.resource.ResourceVariant;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.api.data.DataBinary;
import fr.robie.machineengine.core.machine.CoreMachines;
import fr.robie.machineengine.paper.Messages;
import fr.robie.machineengine.api.paper.PaperItems;
import fr.robie.machineengine.paper.PaperWorlds;
import net.kyori.adventure.text.Component;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.libraries.nbt.ByteArrayTag;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
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
 * <p>Breaking packs the machine; the block's loot table, computed right after (same tick, the block is
 * gone), writes the state into the dropped block item through the {@code machineengine:keep_contents}
 * loot function ({@link KeepContentsFunction}, template {@code machineengine:loot_table/keep_contents}).
 * A break without drops (an explosion, creative mode, a drop-less loot table) drops the contents as
 * usual one tick later, so nothing is ever lost or duplicated. Placing the item restores the state once
 * CraftEngine has accepted the placement ({@code loadCustomDataFromItem}), after every cancellable place
 * event. The state is CraftEngine item data, {@code minecraft:custom_data} → {@code machineengine.contents}
 * (D32); items from before keep theirs in Bukkit persistent data and are still read.</p>
 */
public final class KeptContents {
    /** Before D32: the state in Bukkit persistent data. Still read. */
    private static final NamespacedKey OLD_KEY = new NamespacedKey("machineengine", "contents");
    private static final String[] PATH = {"machineengine", "contents"};
    private static final int LORE_LINES = 5;

    private final Scheduler scheduler;
    private final CraftEngineMachineHost host;
    private final Map<WorldPosition, Pending> pending = new ConcurrentHashMap<>();

    KeptContents(Plugin plugin, CraftEngineMachineHost host) {
        this.scheduler = MachineEngine.scheduler(plugin);
        this.host = host;
    }

    private record Pending(CoreMachines.Packed packed, Key item) {
    }

    /** A machine was broken and packed (owner thread). */
    void packed(WorldPosition position, CoreMachines.Packed packed, Key blockId) {
        Pending entry = new Pending(packed, blockId);
        this.pending.put(position, entry);
        World world = world(position);
        if (world == null) {
            this.pending.remove(position, entry);
            return;
        }
        this.scheduler.runLater(world, position.position().chunkX(), position.position().chunkZ(), () -> {
            // No drop event claimed the state: drop the contents as a normal break would.
            if (this.pending.remove(position, entry)) this.host.dropResources(position, packed.contents());
        }, 1);
    }

    /**
     * The block item dropped where a machine was just broken: the same item with the machine's state,
     * or {@code null} if nothing waits there for this item (the drop stays as it is).
     */
    @Nullable ItemStack claim(@NotNull WorldPosition position, @NotNull ItemStack drop) {
        Pending entry = this.pending.get(position);
        if (entry == null || drop.getAmount() != 1 || !entry.item().equals(CraftEngineItems.getCustomItemId(drop))) return null;
        if (!this.pending.remove(position, entry)) return null;
        return withContents(drop, entry.packed());
    }

    /** The packed state in {@code stack}, or {@code null} if it has none or it is unreadable. */
    @Nullable DataMap read(@NotNull ItemStack stack) {
        byte[] bytes = BukkitAdaptor.adapt(stack).getSparrowTag((Object[]) PATH) instanceof ByteArrayTag tag ? tag.getAsByteArray() : null;
        if (bytes == null && stack.hasItemMeta()) {
            bytes = stack.getItemMeta().getPersistentDataContainer().get(OLD_KEY, PersistentDataType.BYTE_ARRAY);
        }
        if (bytes == null) return null;
        try {
            return DataBinary.read(bytes);
        } catch (IOException e) {
            this.host.logger().warning("Ignored unreadable machine contents in an item: " + e.getMessage());
            return null;
        }
    }

    static ItemStack withContents(ItemStack stack, CoreMachines.Packed packed) {
        var item = BukkitAdaptor.adapt(stack.clone());
        item.setSparrowTag(new ByteArrayTag(DataBinary.write(packed.state())), (Object[]) PATH);
        ItemStack result = (ItemStack) item.platformItem();
        result.editMeta(meta -> {
            List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            lore.addAll(describe(packed.contents()));
            meta.lore(lore);
        });
        return result;
    }

    /** Lore lines (translated, see {@link Messages}): a header, then one line per resource, merged by variant. */
    static List<Component> describe(List<ResourceStack> contents) {
        Map<ResourceVariant, Long> totals = new LinkedHashMap<>();
        for (ResourceStack stack : contents) totals.merge(stack.variant(), stack.amount(), Long::sum);
        List<Component> lines = new ArrayList<>();
        lines.add(Messages.contentsKept());
        int shown = 0;
        for (Map.Entry<ResourceVariant, Long> entry : totals.entrySet()) {
            if (shown++ == LORE_LINES) {
                lines.add(Messages.contentsMore(totals.size() - LORE_LINES));
                break;
            }
            lines.add(Messages.contentsEntry(entry.getValue(), name(entry.getKey())));
        }
        return lines;
    }

    private static Component name(ResourceVariant variant) {
        ItemStack item = PaperItems.toItemStack(variant, 1);
        return item != null ? Component.translatable(item) : Component.text(variant.type().key().asString());
    }

    private static @Nullable World world(WorldPosition position) {
        return PaperWorlds.world(position.world());
    }
}
