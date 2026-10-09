package fr.robie.machineengine.zmenu;

import fr.maxlego08.menu.api.button.Button;
import fr.maxlego08.menu.api.button.DefaultButtonValue;
import fr.maxlego08.menu.api.engine.InventoryEngine;
import fr.maxlego08.menu.api.loader.ButtonLoader;
import fr.maxlego08.menu.api.utils.Placeholders;
import fr.robie.machineengine.api.machine.MachineAction;
import fr.robie.machineengine.api.paper.PaperItems;
import fr.robie.machineengine.api.resource.ResourceStack;
import fr.robie.machineengine.core.machine.LoadedMachine;
import fr.robie.machineengine.core.machine.MachineInstanceImpl;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * {@code type: machineengine_action}: runs one of the open machine's actions (D40, {@code MachineAction})
 * with the click: left, right, shift, drop, and the stack on the cursor. What the action takes comes off
 * the cursor, what it gives goes to the player's inventory (dropped if full), and its message shows on the
 * action bar. Hidden on machines whose type has no such action.
 * <ul>
 *   <li>{@code index}: passed to the action (the row of a list), 0 by default;</li>
 *   <li>{@code list: <id>}: hidden when {@code index} is past the end of that list (one button per row,
 *       showing the row with {@code %zmenu_machineengine_list_<id>_<index>_<field>%});</li>
 *   <li>{@code input: true}: a shift-click on a stack in the player's inventory runs the action too, as
 *       an {@code INSERT} click with that stack (an upgrade input, a module slot). Use it on one button
 *       per menu, and not next to {@code machineengine_slot} buttons that accept shift-clicks.</li>
 * </ul>
 *
 * <pre>{@code
 * module-0:
 *   type: machineengine_action
 *   slot: 19
 *   action: module_take
 *   list: modules
 *   index: 0
 *   item: { material: PAPER, name: "%zmenu_machineengine_list_modules_0_name%" }
 * }</pre>
 */
final class ActionButton extends Button {
    private final ZMenuMachineMenus menus;
    private final String action;
    private final int index;
    private final @Nullable String list;
    private final boolean input;

    private ActionButton(ZMenuMachineMenus menus, String action, int index, @Nullable String list, boolean input) {
        this.menus = menus;
        this.action = action;
        this.index = index;
        this.list = list;
        this.input = input;
    }

    @Override
    public boolean checkPermission(@NotNull Player player, @NotNull InventoryEngine inventory, @NotNull Placeholders placeholders) {
        if (!super.checkPermission(player, inventory, placeholders)) return false;
        LoadedMachine machine = this.menus.machineOf(player);
        MachineInstanceImpl instance = machine == null ? null : machine.instance();
        if (instance == null || instance.type().action(this.action).isEmpty()) return false;
        return this.list == null || this.index < instance.list(this.list).size();
    }

    @Override
    public boolean isClickable() {
        return true;
    }

    /** zMenu enables clicks in the player's inventory when a button is "draggable". */
    @Override
    public boolean isDraggable() {
        return this.input;
    }

    @Override
    public void onInventoryClick(@NotNull InventoryClickEvent event, @NotNull Player player, @NotNull InventoryEngine inventory) {
        if (!this.input || event.getClickedInventory() == null || event.getClickedInventory().getType() != InventoryType.PLAYER
                || event.getAction() != InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            return;
        }
        // Shift-click from the player's inventory: never let it reach the menu's own slots.
        event.setCancelled(true);
        ItemStack current = event.getCurrentItem();
        if (current == null || current.getType().isAir()) return;
        MachineAction.Outcome outcome = run(player, MachineAction.ClickKind.INSERT, current);
        if (outcome != null && outcome.used() > 0) event.setCurrentItem(remove(current, outcome.used()));
    }

    @Override
    public void onClick(@NotNull Player player, @NotNull InventoryClickEvent event, @NotNull InventoryEngine inventory, int slot,
                        @NotNull Placeholders placeholders) {
        super.onClick(player, event, inventory, slot, placeholders);
        ItemStack cursor = player.getItemOnCursor();
        MachineAction.Outcome outcome = run(player, kind(event.getClick()), cursor);
        if (outcome != null && outcome.used() > 0) player.setItemOnCursor(remove(cursor, outcome.used()));
    }

    /** Runs the action with {@code held}; gives what it returns and shows its message. Null if the machine is not here. */
    private @Nullable MachineAction.Outcome run(Player player, MachineAction.ClickKind kind, @Nullable ItemStack held) {
        MachineInstanceImpl machine = this.menus.ownedMachineOf(player);
        if (machine == null) return null;
        ResourceStack stack = held == null || held.getType().isAir() ? null : PaperItems.toResource(held);
        MachineAction.Outcome outcome = machine.runAction(this.action,
                new MachineAction.Click(player.getUniqueId(), kind, this.index, stack));
        finish(this.menus, player, machine, outcome);
        return outcome;
    }

    /** Gives the player what {@code outcome} gives, shows its message, and redraws the menus if it did something. */
    static void finish(ZMenuMachineMenus menus, Player player, MachineInstanceImpl machine, MachineAction.Outcome outcome) {
        for (ResourceStack given : outcome.give()) {
            for (ItemStack item : PaperItems.toItemStacks(given)) {
                player.getInventory().addItem(item).values()
                        .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
            }
        }
        if (outcome.message() != null) player.sendActionBar(outcome.message());
        if (outcome.done()) menus.changed(player, machine.position());
    }

    static @Nullable ItemStack remove(ItemStack stack, long used) {
        return used >= stack.getAmount() ? null : stack.asQuantity((int) (stack.getAmount() - used));
    }

    static MachineAction.ClickKind kind(ClickType click) {
        return switch (click) {
            case SHIFT_LEFT, SHIFT_RIGHT -> MachineAction.ClickKind.SHIFT;
            case RIGHT -> MachineAction.ClickKind.RIGHT;
            case DROP, CONTROL_DROP -> MachineAction.ClickKind.DROP;
            default -> MachineAction.ClickKind.LEFT;
        };
    }

    static final class Loader extends ButtonLoader {
        private final Plugin owner;
        private final ZMenuMachineMenus menus;

        Loader(Plugin plugin, ZMenuMachineMenus menus) {
            super(plugin, "machineengine_action");
            this.owner = plugin;
            this.menus = menus;
        }

        @Override
        public @Nullable Button load(@NotNull YamlConfiguration configuration, @NotNull String path, @NotNull DefaultButtonValue defaultButtonValue) {
            String action = configuration.getString(path + "action");
            if (action == null) {
                this.owner.getLogger().warning("The machineengine_action button at " + path + " needs 'action: <id>'");
                return null;
            }
            return new ActionButton(this.menus, action, Math.max(0, configuration.getInt(path + "index", 0)),
                    configuration.getString(path + "list"), configuration.getBoolean(path + "input", false));
        }
    }
}
