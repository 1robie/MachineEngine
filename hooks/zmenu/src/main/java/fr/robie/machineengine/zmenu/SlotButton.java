package fr.robie.machineengine.zmenu;

import fr.maxlego08.menu.api.button.Button;
import fr.maxlego08.menu.api.button.DefaultButtonValue;
import fr.maxlego08.menu.api.engine.InventoryEngine;
import fr.maxlego08.menu.api.loader.ButtonLoader;
import fr.maxlego08.menu.api.utils.Placeholders;
import fr.robie.machineengine.api.resource.ResourceStack;
import fr.robie.machineengine.api.resource.ResourceVariant;
import fr.robie.machineengine.api.transfer.StorageView;
import fr.robie.machineengine.api.transfer.Transaction;
import fr.robie.machineengine.core.machine.LoadedMachine;
import fr.robie.machineengine.core.machine.MachineInstanceImpl;
import fr.robie.machineengine.core.machine.MachineSlot;
import fr.robie.machineengine.core.machine.MachineStorage;
import fr.robie.machineengine.api.paper.PaperItems;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * {@code type: machineengine_slot}: shows one storage slot of the open machine. Item resources are
 * shown as the real item; empty slots and other resources use the button's configured {@code item}
 * (with the machine placeholders). Clicking puts the cursor item in (right-click: one item), or takes
 * the contents out, in one transaction (nothing is lost if the player's inventory is full).
 * Shift-clicking an item in the player's inventory puts it into the menu's slots in order.
 *
 * <p>zMenu ignores clicks in the player's inventory unless a button asks for them; slots that accept
 * items do (so players can pick items up), and the clicks that could reach the menu's display items
 * (shift-click into it, double-click collect) are cancelled.</p>
 *
 * <pre>{@code
 * input-slot:
 *   type: machineengine_slot
 *   slot: 20
 *   storage: input          # storage id
 *   storage-slot: 1         # 1-based slot of that storage (default 1)
 *   allow-insert: true
 *   allow-take: true
 *   item: { material: LIGHT_GRAY_STAINED_GLASS_PANE, name: "&7Empty" }
 * }</pre>
 */
final class SlotButton extends Button {
    private final ZMenuMachineMenus menus;
    private final String storage;
    private final int storageSlot;
    private final boolean allowInsert;
    private final boolean allowTake;

    private SlotButton(ZMenuMachineMenus menus, String storage, int storageSlot, boolean allowInsert, boolean allowTake) {
        this.menus = menus;
        this.storage = storage;
        this.storageSlot = storageSlot;
        this.allowInsert = allowInsert;
        this.allowTake = allowTake;
    }

    @Override
    public ItemStack getCustomItemStack(@NotNull Player player, boolean useCache, @NotNull Placeholders placeholders) {
        StorageView view = view(player);
        if (view != null && !view.isEmpty()) {
            ItemStack item = PaperItems.toItemStack(view.variant(), (int) Math.min(64, view.amount()));
            if (item != null) return item;
        }
        return super.getCustomItemStack(player, useCache, placeholders);
    }

    @Override
    public boolean isClickable() {
        return true;
    }

    /**
     * zMenu's periodic update only rewrites the name and lore of the item already shown, which would
     * put this button's "empty slot" name on the real item and keep showing an item taken out: always
     * rebuild the slot from the machine instead.
     */
    @Override
    public boolean isUpdatedMasterButton() {
        return true;
    }

    /** zMenu enables clicks in the player's inventory when a button is "draggable". */
    @Override
    public boolean isDraggable() {
        return this.allowInsert;
    }

    /** Called for every click while the menu is open, once per button. */
    @Override
    public void onInventoryClick(@NotNull InventoryClickEvent event, @NotNull Player player, @NotNull InventoryEngine inventory) {
        InventoryAction action = event.getAction();
        if (action == InventoryAction.COLLECT_TO_CURSOR) {
            // Would gather matching display items out of the menu.
            event.setCancelled(true);
            return;
        }
        if (event.getClickedInventory() == null || event.getClickedInventory().getType() != InventoryType.PLAYER
                || action != InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            return;
        }
        // Shift-click from the player's inventory: never let it reach the menu's own slots.
        event.setCancelled(true);
        ItemStack current = event.getCurrentItem();
        if (!this.allowInsert || current == null || current.getType().isAir()) return;
        MachineInstanceImpl machine = this.menus.ownedMachineOf(player);
        MachineSlot target = slot(machine);
        if (target == null) return;
        long inserted = insert(machine, target, current, current.getAmount());
        if (inserted <= 0) return;
        event.setCurrentItem(current.getAmount() == inserted ? null : current.asQuantity((int) (current.getAmount() - inserted)));
        this.menus.changed(player, machine.position());
    }

    @Override
    public void onClick(@NotNull Player player, @NotNull InventoryClickEvent event, @NotNull InventoryEngine inventory, int slot,
                        @NotNull Placeholders placeholders) {
        super.onClick(player, event, inventory, slot, placeholders);
        MachineInstanceImpl machine = this.menus.ownedMachineOf(player);
        MachineSlot target = slot(machine);
        if (target == null) return;
        ItemStack cursor = player.getItemOnCursor();
        if (this.allowInsert && !cursor.getType().isAir()) {
            long inserted = insert(machine, target, cursor, event.isRightClick() ? 1 : cursor.getAmount());
            if (inserted > 0) player.setItemOnCursor(cursor.getAmount() == inserted ? null : cursor.asQuantity((int) (cursor.getAmount() - inserted)));
        } else if (this.allowTake && !target.isEmpty()) {
            ResourceVariant variant = target.variant();
            ItemStack taken = PaperItems.toItemStack(variant, (int) Math.min(64, target.amount()));
            if (taken != null) {
                try (Transaction transaction = machine.services().transactions().openOuter()) {
                    long extracted = target.extract(variant, taken.getAmount(), transaction);
                    Map<Integer, ItemStack> leftover = player.getInventory().addItem(taken.asQuantity((int) extracted));
                    long kept = leftover.values().stream().mapToLong(ItemStack::getAmount).sum();
                    // What did not fit goes straight back into the slot, in the same transaction.
                    if (kept > 0) target.insert(variant, kept, transaction);
                    transaction.commit();
                }
            }
        }
        this.menus.changed(player, machine.position());
    }

    /** Puts up to {@code amount} of {@code stack} into {@code target}; returns how much went in. */
    private static long insert(MachineInstanceImpl machine, MachineSlot target, ItemStack stack, int amount) {
        ResourceStack offered = PaperItems.toResource(stack);
        try (Transaction transaction = machine.services().transactions().openOuter()) {
            long inserted = target.insert(offered.variant(), Math.min(amount, offered.amount()), transaction);
            transaction.commit();
            return inserted;
        }
    }

    private @Nullable MachineSlot slot(@Nullable MachineInstanceImpl machine) {
        if (machine == null) return null;
        if (!(machine.context().storage(this.storage) instanceof MachineStorage storage)) return null;
        int index = this.storageSlot - 1;
        return index >= 0 && index < storage.slots().size() ? storage.slots().get(index) : null;
    }

    private @Nullable StorageView view(Player player) {
        LoadedMachine loaded = this.menus.machineOf(player);
        MachineSlot slot = slot(loaded == null ? null : loaded.instance());
        return slot == null ? null : slot.view();
    }

    static final class Loader extends ButtonLoader {
        private final ZMenuMachineMenus menus;

        Loader(Plugin plugin, ZMenuMachineMenus menus) {
            super(plugin, "machineengine_slot");
            this.menus = menus;
        }

        @Override
        public @Nullable Button load(@NotNull YamlConfiguration configuration, @NotNull String path, @NotNull DefaultButtonValue defaultButtonValue) {
            return new SlotButton(this.menus, configuration.getString(path + "storage", "input"),
                    configuration.getInt(path + "storage-slot", 1),
                    configuration.getBoolean(path + "allow-insert", true),
                    configuration.getBoolean(path + "allow-take", true));
        }
    }
}
