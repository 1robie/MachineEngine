package fr.robie.machineengine.craftengine;

import fr.robie.machineengine.api.world.WorldPosition;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.loot.LootContext;
import net.momirealms.craftengine.core.loot.function.LootFunction;
import net.momirealms.craftengine.core.loot.function.LootFunctionFactory;
import net.momirealms.craftengine.core.plugin.context.parameter.DirectContextParameters;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;

/**
 * The CraftEngine loot function {@code machineengine:keep_contents} (D32, §20.5): the block item dropped
 * where a machine with {@code keep_contents: true} was broken gets the machine's state. Used by the
 * loot table template {@code machineengine:loot_table/keep_contents}.
 */
final class KeepContentsFunction implements LootFunction {
    static final LootFunctionFactory<KeepContentsFunction> FACTORY = section -> new KeepContentsFunction();

    @Override
    public Item apply(Item item, LootContext context) {
        CraftEngineMachineHost host = CraftEngineMachineHost.current();
        var at = context.getOptionalParameter(DirectContextParameters.POSITION).orElse(null);
        if (host == null || at == null || item.isEmpty()) return item;
        World world = (World) at.world().platformWorld();
        WorldPosition position = WorldPosition.of(world.getKey(), (int) Math.floor(at.x()), (int) Math.floor(at.y()), (int) Math.floor(at.z()));
        ItemStack kept = host.keptContents().claim(position, (ItemStack) item.platformItem());
        return kept == null ? item : BukkitAdaptor.adapt(kept);
    }
}
