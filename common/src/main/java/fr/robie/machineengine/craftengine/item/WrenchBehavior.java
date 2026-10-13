package fr.robie.machineengine.craftengine.item;

import fr.robie.machineengine.api.MachineEngine;
import fr.robie.machineengine.api.item.MachineTool;
import fr.robie.machineengine.api.machine.MachineInstance;
import fr.robie.machineengine.api.paper.MachineBlocks;
import fr.robie.machineengine.api.paper.PaperItemBehavior;
import fr.robie.machineengine.api.world.Direction;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.paper.message.EngineMessage;
import fr.robie.machineengine.paper.message.EngineMessages;
import net.kyori.adventure.key.Key;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.item.behavior.ItemBehaviorFactory;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.plugin.config.KnownResourceException;
import org.bukkit.block.Block;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

/**
 * The CraftEngine item behavior {@code machineengine:wrench} (D32): what a right-click and a
 * sneak-right-click on a machine do. {@code rotate} turns it a quarter clockwise (it keeps what it holds;
 * a running operation restarts); {@code dismantle} breaks it as the player would, so a block with
 * {@code keep_contents} drops one item holding the machine; {@code none} leaves the click to the item's
 * next behavior (or the machine's menu).
 *
 * <pre>{@code
 * behaviors:
 *   - type: machineengine:wrench
 *     click: rotate            # optional, default none
 *     sneak-click: dismantle   # optional, default none
 * }</pre>
 */
public final class WrenchBehavior extends PaperItemBehavior implements MachineTool {
    public static final ItemBehaviorFactory<WrenchBehavior> FACTORY = (pack, path, id, section) ->
            new WrenchBehavior(action(section, "click"), action(section, "sneak-click"));

    private static final List<Direction> TURN = List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);

    enum Action { NONE, ROTATE, DISMANTLE }

    private final Action click;
    private final Action sneakClick;

    WrenchBehavior(Action click, Action sneakClick) {
        this.click = click;
        this.sneakClick = sneakClick;
    }

    @Override
    public boolean actsOnMachines() {
        return this.click != Action.NONE || this.sneakClick != Action.NONE;
    }

    @Override
    protected boolean use(@NotNull Use use) {
        Block block = use.block();
        Action action = use.sneaking() ? this.sneakClick : this.click;
        if (block == null || action == Action.NONE) return false;
        WorldPosition position = WorldPosition.of(block.getWorld().getKey(), block.getX(), block.getY(), block.getZ());
        MachineInstance machine = MachineEngine.get().machineAt(position).orElse(null);
        if (machine == null) return false;
        if (action == Action.DISMANTLE) {
            // As the player breaks it: protection plugins decide, and the loot (keep-contents) runs after the machine is packed.
            use.player().breakBlock(block);
            return true;
        }
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
        MachineBlocks blocks = MachineBlocks.current();
        int index = TURN.indexOf(machine.inspect().facing());
        Direction next = TURN.get((index + 1) % TURN.size());
        if (state == null || blocks == null || !blocks.replaceBlock(position, Key.key(state.owner().value().id().asString()), next)) {
            EngineMessages.send(use.player(), EngineMessage.WRENCH_BUSY);
        }
        return true;
    }

    private static Action action(ConfigSection section, String key) {
        String value = section.getString(key, "none");
        try {
            return Action.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new KnownResourceException("resource.argument.parser.enum", section.assemblePath(key), value, "none, rotate, dismantle");
        }
    }
}
