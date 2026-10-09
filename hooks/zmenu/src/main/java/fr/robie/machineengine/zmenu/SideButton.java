package fr.robie.machineengine.zmenu;

import fr.maxlego08.menu.api.button.Button;
import fr.maxlego08.menu.api.button.DefaultButtonValue;
import fr.maxlego08.menu.api.engine.InventoryEngine;
import fr.maxlego08.menu.api.loader.ButtonLoader;
import fr.maxlego08.menu.api.utils.Placeholders;
import fr.robie.machineengine.api.machine.MachineType;
import fr.robie.machineengine.api.machine.PortDefinition;
import fr.robie.machineengine.api.machine.PortDirection;
import fr.robie.machineengine.api.world.RelativeSide;
import fr.robie.machineengine.core.machine.LoadedMachine;
import fr.robie.machineengine.core.machine.MachineInstanceImpl;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@code type: machineengine_side} (side configuration, E2): what one side of the open machine does
 * for a port group ({@code side: front}, {@code group: items}): input, output, both or nothing. The
 * group's input ports (all of them, e.g. every factory lane) and output ports follow together. A click
 * goes to the next state the machine can have. Hidden when the machine has no port in the group. The
 * placeholder {@code %zmenu_machineengine_side_<group>_<side>%} names the state.
 */
final class SideButton extends Button {
    /** What a side does for a group, in click order. */
    enum State {
        INPUT("Input"), OUTPUT("Output"), BOTH("Input & output"), NONE("None");

        private final String label;

        State(String label) {
            this.label = label;
        }

        boolean inputs() {
            return this == INPUT || this == BOTH;
        }

        boolean outputs() {
            return this == OUTPUT || this == BOTH;
        }
    }

    private final ZMenuMachineMenus menus;
    private final RelativeSide side;
    private final String group;

    private SideButton(ZMenuMachineMenus menus, RelativeSide side, String group) {
        this.menus = menus;
        this.side = side;
        this.group = group;
    }

    @Override
    public boolean checkPermission(@NotNull Player player, @NotNull InventoryEngine inventory, @NotNull Placeholders placeholders) {
        LoadedMachine machine = this.menus.machineOf(player);
        return super.checkPermission(player, inventory, placeholders)
                && machine != null && machine.instance() != null && hasGroup(machine.instance().type(), this.group);
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
        if (machine == null || !hasGroup(machine.type(), this.group)) return;
        boolean canIn = false;
        boolean canOut = false;
        for (PortDefinition port : ports(machine.type(), this.group)) {
            PortDirection direction = machine.context().portDirection(port.id());
            canIn |= direction.allowsInsertion();
            canOut |= direction.allowsExtraction();
        }
        State next = state(machine, this.group, this.side);
        State[] order = State.values();
        do {
            next = order[(next.ordinal() + 1) % order.length];
        } while ((next.inputs() && !canIn) || (next.outputs() && !canOut));
        for (PortDefinition port : ports(machine.type(), this.group)) {
            PortDirection direction = machine.context().portDirection(port.id());
            boolean wanted = (direction.allowsInsertion() && next.inputs()) || (direction.allowsExtraction() && next.outputs());
            Set<RelativeSide> sides = EnumSet.noneOf(RelativeSide.class);
            sides.addAll(machine.context().portSides(port.id()));
            if (wanted) sides.add(this.side);
            else sides.remove(this.side);
            machine.context().setPortSides(port.id(), sides);
        }
        this.menus.changed(player, machine.position());
    }

    static List<PortDefinition> ports(MachineType type, String group) {
        return type.ports().stream().filter(port -> group.equals(port.group())).toList();
    }

    static boolean hasGroup(MachineType type, String group) {
        return type.ports().stream().anyMatch(port -> group.equals(port.group()));
    }

    /** What {@code side} does for {@code group}: whether an input port, an output port, or both are on it. */
    static State state(MachineInstanceImpl machine, String group, RelativeSide side) {
        boolean in = false;
        boolean out = false;
        for (PortDefinition port : ports(machine.type(), group)) {
            if (!machine.context().portSides(port.id()).contains(side)) continue;
            PortDirection direction = machine.context().portDirection(port.id());
            in |= direction.allowsInsertion();
            out |= direction.allowsExtraction();
        }
        return in && out ? State.BOTH : in ? State.INPUT : out ? State.OUTPUT : State.NONE;
    }

    /**
     * {@code side_<group>_<side>}: the state's name, or {@code null} if not such a key;
     * {@code side_<group>_<side>_mode}: the state as an id ({@code input}, {@code output}, {@code both},
     * {@code none}), e.g. to name an icon item.
     */
    static @Nullable String placeholder(MachineInstanceImpl machine, String key) {
        if (!key.startsWith("side_")) return null;
        String rest = key.substring("side_".length());
        boolean mode = rest.endsWith("_mode");
        if (mode) rest = rest.substring(0, rest.length() - "_mode".length());
        int split = rest.lastIndexOf('_');
        if (split <= 0) return null;
        RelativeSide side = parseSide(rest.substring(split + 1));
        if (side == null) return null;
        String group = rest.substring(0, split);
        if (mode) return (hasGroup(machine.type(), group) ? state(machine, group, side) : State.NONE).name().toLowerCase(Locale.ROOT);
        return hasGroup(machine.type(), group) ? state(machine, group, side).label : "-";
    }

    static @Nullable RelativeSide parseSide(String name) {
        try {
            return RelativeSide.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static final class Loader extends ButtonLoader {
        private final Plugin owner;
        private final ZMenuMachineMenus menus;

        Loader(Plugin plugin, ZMenuMachineMenus menus) {
            super(plugin, "machineengine_side");
            this.owner = plugin;
            this.menus = menus;
        }

        @Override
        public @Nullable Button load(@NotNull YamlConfiguration configuration, @NotNull String path, @NotNull DefaultButtonValue defaultButtonValue) {
            RelativeSide side = parseSide(configuration.getString(path + "side", ""));
            String group = configuration.getString(path + "group");
            if (side == null || group == null) {
                this.owner.getLogger().warning("The machineengine_side button at " + path
                        + " needs 'side: front|back|left|right|top|bottom' and 'group: <port group>'");
                return null;
            }
            return new SideButton(this.menus, side, group);
        }
    }
}
