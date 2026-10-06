package fr.robie.machineengine.zmenu;

import fr.maxlego08.menu.api.button.Button;
import fr.maxlego08.menu.api.button.DefaultButtonValue;
import fr.maxlego08.menu.api.engine.InventoryEngine;
import fr.maxlego08.menu.api.loader.ButtonLoader;
import fr.maxlego08.menu.api.utils.Placeholders;
import fr.robie.machineengine.api.machine.MachineDataKey;
import fr.robie.machineengine.api.machine.WakeReason;
import fr.robie.machineengine.core.machine.MachineInstanceImpl;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * {@code type: machineengine_data}: changes a setting of the open machine, one of the data its type
 * declares ({@code data: <id>}), e.g. a mode. A boolean is switched; a text goes to the next of
 * {@code values: [...]}. The machine wakes up to apply it. Show the value with the placeholder
 * {@code %zmenu_machineengine_data_<id>%}.
 */
final class DataButton extends Button {
    private final ZMenuMachineMenus menus;
    private final String data;
    private final List<String> values;

    private DataButton(ZMenuMachineMenus menus, String data, List<String> values) {
        this.menus = menus;
        this.data = data;
        this.values = List.copyOf(values);
    }

    /** Hidden on machines whose type does not declare the setting (one menu can serve several types). */
    @Override
    public boolean checkPermission(@NotNull Player player, @NotNull InventoryEngine inventory,
                                   @NotNull Placeholders placeholders) {
        var machine = this.menus.machineOf(player);
        return super.checkPermission(player, inventory, placeholders)
                && machine != null && machine.instance() != null && key(machine.instance(), this.data) != null;
    }

    @Override
    public boolean isClickable() {
        return true;
    }

    @Override
    public void onClick(@NotNull Player player, @NotNull InventoryClickEvent event, @NotNull InventoryEngine inventory, int slot,
                        @NotNull Placeholders placeholders) {
        super.onClick(player, event, inventory, slot, placeholders);
        MachineInstanceImpl machine = this.menus.ownedMachineOf(player);
        if (machine == null) return;
        MachineDataKey<?> key = key(machine, this.data);
        if (key == null || !next(machine, key)) return;
        machine.wake(WakeReason.CONFIG_CHANGED);
        this.menus.changed(player, machine.position());
    }

    @SuppressWarnings("unchecked")
    private boolean next(MachineInstanceImpl machine, MachineDataKey<?> key) {
        Object current = machine.context().get(key);
        if (current instanceof Boolean value) {
            machine.context().set((MachineDataKey<Boolean>) key, !value);
            return true;
        }
        if (current instanceof String value && !this.values.isEmpty()) {
            int index = this.values.indexOf(value);
            machine.context().set((MachineDataKey<String>) key, this.values.get((index + 1) % this.values.size()));
            return true;
        }
        return false;
    }

    static @Nullable MachineDataKey<?> key(MachineInstanceImpl machine, String id) {
        for (MachineDataKey<?> key : machine.type().data()) {
            if (key.id().equals(id)) return key;
        }
        return null;
    }

    static final class Loader extends ButtonLoader {
        private final Plugin owner;
        private final ZMenuMachineMenus menus;

        Loader(Plugin plugin, ZMenuMachineMenus menus) {
            super(plugin, "machineengine_data");
            this.owner = plugin;
            this.menus = menus;
        }

        @Override
        public @Nullable Button load(@NotNull YamlConfiguration configuration, @NotNull String path, @NotNull DefaultButtonValue defaultButtonValue) {
            String data = configuration.getString(path + "data");
            if (data == null) {
                this.owner.getLogger().warning("The machineengine_data button at " + path + " needs 'data: <id>'");
                return null;
            }
            return new DataButton(this.menus, data, configuration.getStringList(path + "values"));
        }
    }
}
