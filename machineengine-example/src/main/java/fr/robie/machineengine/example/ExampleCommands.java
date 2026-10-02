package fr.robie.machineengine.example;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
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
 * <ul>
 *   <li>{@code /mexample place <x> <y> <z> <world>}: a generator with a consumer north of it</li>
 *   <li>{@code /mexample crate-test <x> <y> <z> <world>}: fill a crate with 100 iron ingots, break
 *       it, and count the dropped items</li>
 *   <li>{@code /mexample fill <x> <y> <z> <size> <world>}: a size×size grid of idle consumers, for
 *       measuring the cost of sleeping machines</li>
 * </ul>
 */
@SuppressWarnings("UnstableApiUsage")
final class ExampleCommands {
    private final Plugin plugin;

    ExampleCommands(Plugin plugin) {
        this.plugin = plugin;
    }

    LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("mexample")
                .requires(source -> source.getSender().hasPermission("machineengine.admin"))
                .then(Commands.literal("place").then(position(onRegion(this::place))))
                .then(Commands.literal("crate-test").then(position(onRegion(this::crateTest))))
                .then(Commands.literal("network-line").then(position(onRegion(this::networkLine))))
                .then(Commands.literal("fill")
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("y", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .then(Commands.argument("size", IntegerArgumentType.integer(1, 200))
                                                        .then(Commands.argument("world", StringArgumentType.word()).executes(onRegion(this::fill))))))))
                .build();
    }

    /**
     * Runs {@code action} on the thread owning the target position (Folia: its region; Paper: the main
     * thread), because it touches the world there.
     */
    private Command<CommandSourceStack> onRegion(Command<CommandSourceStack> action) {
        return context -> {
            World world = world(context);
            if (world == null) return 0;
            Location location = new Location(world, IntegerArgumentType.getInteger(context, "x"), 0,
                    IntegerArgumentType.getInteger(context, "z"));
            Bukkit.getRegionScheduler().execute(this.plugin, location, () -> {
                try {
                    action.run(context);
                } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
                    reply(context, e.getMessage());
                }
            });
            return Command.SINGLE_SUCCESS;
        };
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, Integer> position(
            Command<CommandSourceStack> command) {
        return Commands.argument("x", IntegerArgumentType.integer())
                .then(Commands.argument("y", IntegerArgumentType.integer())
                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                .then(Commands.argument("world", StringArgumentType.word()).executes(command))));
    }

    private int place(CommandContext<CommandSourceStack> context) {
        World world = world(context);
        if (world == null) return 0;
        int x = IntegerArgumentType.getInteger(context, "x");
        int y = IntegerArgumentType.getInteger(context, "y");
        int z = IntegerArgumentType.getInteger(context, "z");
        boolean placed = place(world, x, y, z, "generator") && place(world, x, y, z - 1, "consumer");
        reply(context, placed ? "Placed a generator at " + x + " " + y + " " + z + " and a consumer north of it."
                : "Placement failed (are the example blocks loaded in CraftEngine?)");
        return placed ? Command.SINGLE_SUCCESS : 0;
    }

    /** A generator, three cables and a consumer, along north (the generator's front faces the cables). */
    private int networkLine(CommandContext<CommandSourceStack> context) {
        World world = world(context);
        if (world == null) return 0;
        int x = IntegerArgumentType.getInteger(context, "x");
        int y = IntegerArgumentType.getInteger(context, "y");
        int z = IntegerArgumentType.getInteger(context, "z");
        boolean placed = place(world, x, y, z, "generator");
        for (int i = 1; i <= 3; i++) placed &= place(world, x, y, z - i, "spark_cable");
        placed &= place(world, x, y, z - 4, "consumer");
        reply(context, placed ? "Placed generator, 3 cables and a consumer from z=" + z + " to z=" + (z - 4) : "Placement failed");
        return placed ? Command.SINGLE_SUCCESS : 0;
    }

    private int crateTest(CommandContext<CommandSourceStack> context) {
        World world = world(context);
        if (world == null) return 0;
        int x = IntegerArgumentType.getInteger(context, "x");
        int y = IntegerArgumentType.getInteger(context, "y");
        int z = IntegerArgumentType.getInteger(context, "z");
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

    private int fill(CommandContext<CommandSourceStack> context) {
        World world = world(context);
        if (world == null) return 0;
        int x = IntegerArgumentType.getInteger(context, "x");
        int y = IntegerArgumentType.getInteger(context, "y");
        int z = IntegerArgumentType.getInteger(context, "z");
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

    private static World world(CommandContext<CommandSourceStack> context) {
        World world = Bukkit.getWorld(StringArgumentType.getString(context, "world"));
        if (world == null) reply(context, "Unknown world");
        return world;
    }

    private static void reply(CommandContext<CommandSourceStack> context, String message) {
        context.getSource().getSender().sendMessage(message);
    }
}
