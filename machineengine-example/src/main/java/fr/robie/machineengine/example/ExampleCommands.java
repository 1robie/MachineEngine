package fr.robie.machineengine.example;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import fr.robie.machineengine.api.MachineEngine;
import fr.robie.machineengine.api.capability.Capabilities;
import fr.robie.machineengine.api.machine.MachineInstance;
import fr.robie.machineengine.api.resource.ResourceStack;
import fr.robie.machineengine.api.transfer.ResourceHandler;
import fr.robie.machineengine.api.transfer.Transaction;
import fr.robie.machineengine.api.world.Direction;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.paper.PaperItems;
import fr.robie.machineengine.paper.PositionArguments;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Commands for trying the example addon from the console.
 * Positions are {@code [<x y z>] [<world>]}, defaulting for players to the block above the one they
 * look at.
 * <ul>
 *   <li>{@code /mexample place}: a generator with a consumer north of it</li>
 *   <li>{@code /mexample network-line}: generator, three cables, consumer</li>
 *   <li>{@code /mexample crate-test}: fill a crate with 100 iron ingots, break it, count the drops</li>
 *   <li>{@code /mexample fill <size>}: a size×size grid of idle consumers (idle-cost benchmark)</li>
 * </ul>
 */
@SuppressWarnings("UnstableApiUsage")
final class ExampleCommands {
    private final Plugin plugin;

    ExampleCommands(Plugin plugin) {
        this.plugin = plugin;
    }

    LiteralCommandNode<CommandSourceStack> build() {
        // Positions are optional: a player defaults to the block above the one they look at; "~" works.
        return Commands.literal("mexample")
                .requires(source -> source.getSender().hasPermission("machineengine.admin"))
                .then(PositionArguments.optionalPosition(Commands.literal("place"), PositionArguments.Default.PLACEMENT, onRegion(this::place)))
                .then(PositionArguments.optionalPosition(Commands.literal("crate-test"), PositionArguments.Default.PLACEMENT, onRegion(this::crateTest)))
                .then(PositionArguments.optionalPosition(Commands.literal("hopper-test"), PositionArguments.Default.PLACEMENT, onRegion(this::hopperTest)))
                .then(PositionArguments.optionalPosition(Commands.literal("network-line"), PositionArguments.Default.PLACEMENT, onRegion(this::networkLine)))
                .then(Commands.literal("fill")
                        .then(PositionArguments.optionalPosition(Commands.argument("size", IntegerArgumentType.integer(1, 200)),
                                PositionArguments.Default.PLACEMENT, onRegion(this::fill))))
                .build();
    }

    /**
     * Runs {@code action} on the thread owning the target position (Folia: its region; Paper: the main
     * thread), because it touches the world there.
     */
    private PositionArguments.PositionCommand onRegion(PositionArguments.PositionCommand action) {
        return (context, world, x, y, z) -> {
            Bukkit.getRegionScheduler().execute(this.plugin, new Location(world, x, y, z), () -> {
                try {
                    action.run(context, world, x, y, z);
                } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
                    reply(context, e.getMessage());
                }
            });
            return Command.SINGLE_SUCCESS;
        };
    }

    private int place(CommandContext<CommandSourceStack> context, World world, int x, int y, int z) {
        boolean placed = place(world, x, y, z, "generator") && place(world, x, y, z - 1, "consumer");
        reply(context, placed ? "Placed a generator at " + x + " " + y + " " + z + " and a consumer north of it."
                : "Placement failed (are the example blocks loaded in CraftEngine?)");
        return placed ? Command.SINGLE_SUCCESS : 0;
    }

    /** A generator, three cables and a consumer, along north (the generator's front faces the cables). */
    private int networkLine(CommandContext<CommandSourceStack> context, World world, int x, int y, int z) {
        boolean placed = place(world, x, y, z, "generator");
        for (int i = 1; i <= 3; i++) placed &= place(world, x, y, z - i, "spark_cable");
        placed &= place(world, x, y, z - 4, "consumer");
        reply(context, placed ? "Placed generator, 3 cables and a consumer from z=" + z + " to z=" + (z - 4) : "Placement failed");
        return placed ? Command.SINGLE_SUCCESS : 0;
    }

    private int crateTest(CommandContext<CommandSourceStack> context, World world, int x, int y, int z) {
        if (!place(world, x, y, z, "crate")) {
            reply(context, "Crate placement failed");
            return 0;
        }
        MachineInstance crate = MachineEngine.get().machineAt(WorldPosition.of(world.getKey(), x, y, z)).orElse(null);
        if (crate == null) {
            reply(context, "No crate machine at the position");
            return 0;
        }
        ResourceHandler port = crate.capability(Capabilities.RESOURCE_HANDLER, Direction.UP).orElseThrow();
        ResourceStack iron = PaperItems.toResource(new ItemStack(Material.IRON_INGOT, 100));
        long inserted;
        try (Transaction transaction = MachineEngine.get().transactions().openOuter()) {
            inserted = port.insert(iron.variant(), iron.amount(), transaction);
            transaction.commit();
        }
        CraftEngineBlocks.remove(world.getBlockAt(x, y, z));
        Bukkit.getRegionScheduler().runDelayed(this.plugin, new Location(world, x, y, z), task -> {
            int dropped = world.getNearbyEntitiesByType(Item.class, new Location(world, x + 0.5, y + 0.5, z + 0.5), 3).stream()
                    .filter(item -> item.getItemStack().getType() == Material.IRON_INGOT)
                    .mapToInt(item -> item.getItemStack().getAmount()).sum();
            this.plugin.getLogger().info("crate-test: inserted " + inserted + " iron ingots, " + dropped + " dropped after breaking the crate");
        }, 5);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Hopper interop: a hopper holding 10 iron ingots feeds a crate, a hopper under the crate empties
     * it into a chest. After 15 seconds every ingot must be somewhere, and all should be in the chest.
     */
    private int hopperTest(CommandContext<CommandSourceStack> context, World world, int x, int y, int z) {
        world.getBlockAt(x, y - 2, z).setType(Material.CHEST);
        world.getBlockAt(x, y - 1, z).setBlockData(Bukkit.createBlockData("minecraft:hopper[facing=down]"));
        if (!place(world, x, y, z, "crate")) {
            reply(context, "Crate placement failed");
            return 0;
        }
        world.getBlockAt(x, y + 1, z).setBlockData(Bukkit.createBlockData("minecraft:hopper[facing=down]"));
        ((org.bukkit.block.Hopper) world.getBlockAt(x, y + 1, z).getState(false)).getInventory().addItem(new ItemStack(Material.IRON_INGOT, 10));
        reply(context, "hopper-test: hopper (10 iron) > crate > hopper > chest; counting in 15 s");
        Bukkit.getRegionScheduler().runDelayed(this.plugin, new Location(world, x, y, z), task -> {
            int top = count(((org.bukkit.block.Hopper) world.getBlockAt(x, y + 1, z).getState(false)).getInventory());
            int bottom = count(((org.bukkit.block.Hopper) world.getBlockAt(x, y - 1, z).getState(false)).getInventory());
            int chest = count(((org.bukkit.block.Chest) world.getBlockAt(x, y - 2, z).getState(false)).getInventory());
            MachineInstance crate = MachineEngine.get().machineAt(WorldPosition.of(world.getKey(), x, y, z)).orElse(null);
            long inCrate = crate == null ? -1 : crate.inspect().storages().get("items").stream()
                    .mapToLong(fr.robie.machineengine.api.transfer.StorageView::amount).sum();
            this.plugin.getLogger().info("hopper-test: top hopper " + top + ", crate " + inCrate + ", bottom hopper " + bottom
                    + ", chest " + chest + ", total " + (top + inCrate + bottom + chest) + " (expected 10)");
        }, 300);
        return Command.SINGLE_SUCCESS;
    }

    private static int count(org.bukkit.inventory.Inventory inventory) {
        int total = 0;
        for (ItemStack stack : inventory.getContents()) {
            if (stack != null && stack.getType() == Material.IRON_INGOT) total += stack.getAmount();
        }
        return total;
    }

    private int fill(CommandContext<CommandSourceStack> context, World world, int x, int y, int z) {
        int size = IntegerArgumentType.getInteger(context, "size");
        int placed = 0;
        for (int dx = 0; dx < size; dx++) {
            for (int dz = 0; dz < size; dz++) {
                if (place(world, x + dx, y, z + dz, "consumer")) placed++;
            }
        }
        reply(context, "Placed " + placed + " idle consumers.");
        return Command.SINGLE_SUCCESS;
    }

    private static boolean place(World world, int x, int y, int z, String name) {
        return CraftEngineBlocks.place(new Location(world, x, y, z), net.momirealms.craftengine.core.util.Key.of("example", name), false);
    }

    private static void reply(CommandContext<CommandSourceStack> context, String message) {
        context.getSource().getSender().sendMessage(message);
    }
}
