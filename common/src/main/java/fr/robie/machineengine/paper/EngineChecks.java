package fr.robie.machineengine.paper;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import fr.robie.machineengine.api.MachineEngine;
import fr.robie.machineengine.api.capability.Capabilities;
import fr.robie.machineengine.api.machine.MachineInstance;
import fr.robie.machineengine.api.paper.PaperItems;
import fr.robie.machineengine.api.paper.PositionArguments;
import fr.robie.machineengine.api.resource.ResourceStack;
import fr.robie.machineengine.api.transfer.ResourceHandler;
import fr.robie.machineengine.api.transfer.StorageView;
import fr.robie.machineengine.api.transfer.Transaction;
import fr.robie.machineengine.api.world.Direction;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.paper.message.EngineMessage;
import fr.robie.machineengine.paper.message.EngineMessages;
import fr.robie.messageflow.formatter.Placeholder;
import fr.robie.messageflow.logger.Logger;
import fr.robie.paperdispatch.command.CommandDispatch;
import fr.robie.paperdispatch.command.CommandResultType;
import fr.robie.paperdispatch.command.SubCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.Hopper;
import org.bukkit.block.data.type.RedstoneWire;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Item;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /machineengine check …}: server checks of engine features headless tests cannot reach (vanilla
 * blocks, drops, redstone). Admin only; results go to the console log, each with its expected value.
 * <ul>
 *   <li>{@code drop}: a bin with 100 iron ingots broken without a player drops them all (§20.4).</li>
 *   <li>{@code hopper}: hopper (10 iron) → bin → hopper → chest; all 10 end in the chest.</li>
 *   <li>{@code comparator}: a comparator reading a half-full bin gives 8, an empty one 0.</li>
 *   <li>{@code fill <size>}: a size×size grid of idle crushers, to measure the idle cost.</li>
 * </ul>
 * They use the blocks {@code devcheck:bin} and {@code devcheck:crusher}, which only MachineEngine's dev
 * servers install (plugin/src/dev); elsewhere a check says so and stops. Each runs on the thread owning
 * its position (its region on Folia), at {@code [<x y z>] [world]}: the block above the one looked at by
 * default.
 */
@SuppressWarnings("UnstableApiUsage")
final class EngineChecks {
    private static final String NAMESPACE = "devcheck";
    private static final String BIN = "bin";
    private static final String CRUSHER = "crusher";
    /** The bin's single slot (devcheck.yml). */
    private static final int BIN_CAPACITY = 4096;

    private final Plugin plugin;

    private EngineChecks(Plugin plugin) {
        this.plugin = plugin;
    }

    /** The {@code check} sub-command and its checks. */
    static SubCommand<Plugin> command(Plugin plugin) {
        EngineChecks checks = new EngineChecks(plugin);
        return SubCommand.builder(plugin, "check")
                .addSubCommand(new At(plugin, "drop", checks::drop))
                .addSubCommand(new At(plugin, "hopper", checks::hopper))
                .addSubCommand(new At(plugin, "comparator", checks::comparator))
                .addSubCommand(new At(plugin, "fill", checks::fill, Commands.argument("size", IntegerArgumentType.integer(1, 200))))
                .executes(dispatch -> {
                    EngineMessages.send(dispatch.getSender(), EngineMessage.ADMIN_USAGE,
                            Placeholder.of("usage", "Usage: /machineengine check <drop|hopper|comparator|fill> [<x y z>] [world]"));
                    return CommandResultType.FAILURE;
                })
                .build();
    }

    private void drop(CommandDispatch<Plugin> dispatch, World world, int x, int y, int z) {
        reset(world, x, y, z, 0);
        ResourceHandler port = placeBin(dispatch.getSender(), "drop", world, x, y, z);
        if (port == null) return;
        long inserted = move(port, PaperItems.toResource(new ItemStack(Material.IRON_INGOT, 100)), true);
        CraftEngineBlocks.remove(world.getBlockAt(x, y, z));
        Location at = new Location(world, x, y, z);
        MachineEngine.scheduler(this.plugin).runLater(at, () -> {
            int dropped = world.getNearbyEntitiesByType(Item.class, at.clone().add(0.5, 0.5, 0.5), 3).stream()
                    .filter(item -> item.getItemStack().getType() == Material.IRON_INGOT)
                    .mapToInt(item -> item.getItemStack().getAmount()).sum();
            log("check drop: inserted " + inserted + " iron ingots, " + dropped + " dropped after breaking the bin (expected 100)");
        }, 5);
    }

    private void hopper(CommandDispatch<Plugin> dispatch, World world, int x, int y, int z) {
        for (int dy = -2; dy <= 1; dy++) reset(world, x, y + dy, z, 0);
        world.getBlockAt(x, y - 2, z).setType(Material.CHEST);
        world.getBlockAt(x, y - 1, z).setBlockData(Bukkit.createBlockData("minecraft:hopper[facing=down]"));
        if (placeBin(dispatch.getSender(), "hopper", world, x, y, z) == null) return;
        world.getBlockAt(x, y + 1, z).setBlockData(Bukkit.createBlockData("minecraft:hopper[facing=down]"));
        ((Hopper) world.getBlockAt(x, y + 1, z).getState(false)).getInventory().addItem(new ItemStack(Material.IRON_INGOT, 10));
        reply(dispatch.getSender(), "check hopper: hopper (10 iron) > bin > hopper > chest; counting in 15 s");
        MachineEngine.scheduler(this.plugin).runLater(new Location(world, x, y, z), () -> {
            int top = ironIn(((Hopper) world.getBlockAt(x, y + 1, z).getState(false)).getInventory());
            int bottom = ironIn(((Hopper) world.getBlockAt(x, y - 1, z).getState(false)).getInventory());
            int chest = ironIn(((Chest) world.getBlockAt(x, y - 2, z).getState(false)).getInventory());
            MachineInstance bin = MachineEngine.get().machineAt(WorldPosition.of(world.getKey(), x, y, z)).orElse(null);
            long inBin = bin == null ? -1 : bin.inspect().storages().get("items").stream().mapToLong(StorageView::amount).sum();
            log("check hopper: top hopper " + top + ", bin " + inBin + ", bottom hopper " + bottom + ", chest " + chest
                    + ", total " + (top + inBin + bottom + chest) + " (expected 10, all in the chest)");
        }, 300);
    }

    /** Bin, then a comparator reading it (east of it), then redstone dust. */
    private void comparator(CommandDispatch<Plugin> dispatch, World world, int x, int y, int z) {
        reset(world, x, y, z, 2);
        for (int dx = 0; dx <= 2; dx++) world.getBlockAt(x + dx, y - 1, z).setType(Material.STONE);
        ResourceHandler port = placeBin(dispatch.getSender(), "comparator", world, x, y, z);
        if (port == null) return;
        world.getBlockAt(x + 1, y, z).setBlockData(Bukkit.createBlockData("minecraft:comparator[facing=west]"));
        world.getBlockAt(x + 2, y, z).setType(Material.REDSTONE_WIRE);
        ResourceStack iron = PaperItems.toResource(new ItemStack(Material.IRON_INGOT, BIN_CAPACITY / 2));
        move(port, iron, true);
        Location at = new Location(world, x, y, z);
        MachineEngine.scheduler(this.plugin).runLater(at, () -> {
            int halfPower = ((RedstoneWire) world.getBlockAt(x + 2, y, z).getBlockData()).getPower();
            move(port, iron, false);
            MachineEngine.scheduler(this.plugin).runLater(at, () -> {
                int emptyPower = ((RedstoneWire) world.getBlockAt(x + 2, y, z).getBlockData()).getPower();
                log("check comparator: half full " + halfPower + " (expected 8), empty " + emptyPower + " (expected 0)");
            }, 10);
        }, 10);
    }

    private void fill(CommandDispatch<Plugin> dispatch, World world, int x, int y, int z) {
        int size = dispatch.getArgument("size", Integer.class);
        int placed = 0;
        for (int dx = 0; dx < size; dx++) {
            for (int dz = 0; dz < size; dz++) {
                if (place(world, x + dx, y, z + dz, CRUSHER)) placed++;
            }
        }
        if (placed == 0 && size > 0) {
            reply(dispatch.getSender(), "check fill: could not place " + NAMESPACE + ":" + CRUSHER + missingBlocks());
            return;
        }
        reply(dispatch.getSender(), "check fill: placed " + placed + " idle crushers");
    }

    /**
     * Clears the blocks from {@code (x, y, z)} to {@code (x + dx, y, z)} and the items lying around, so a
     * check can run again at the same place: CraftEngine does not place a block over the same block, and
     * leftover hoppers, chests or drops would skew the counts.
     */
    private static void reset(World world, int x, int y, int z, int dx) {
        for (int i = 0; i <= dx; i++) {
            Block block = world.getBlockAt(x + i, y, z);
            if (CraftEngineBlocks.isCustomBlock(block)) CraftEngineBlocks.remove(block);
            if (block.getState(false) instanceof Container container) container.getInventory().clear();
            block.setType(Material.AIR);
        }
        world.getNearbyEntitiesByType(Item.class, new Location(world, x + 0.5, y + 0.5, z + 0.5), 4).forEach(Item::remove);
    }

    /** Places a bin and returns its item port, or null (and says why) if it failed. */
    private static @Nullable ResourceHandler placeBin(CommandSender sender, String check, World world, int x, int y, int z) {
        if (!place(world, x, y, z, BIN)) {
            reply(sender, "check " + check + ": could not place " + NAMESPACE + ":" + BIN + missingBlocks());
            return null;
        }
        MachineInstance bin = MachineEngine.get().machineAt(WorldPosition.of(world.getKey(), x, y, z)).orElse(null);
        if (bin == null) {
            reply(sender, "check " + check + ": the " + NAMESPACE + ":" + BIN + " was placed but holds no machine");
            return null;
        }
        return bin.capability(Capabilities.RESOURCE_HANDLER, Direction.UP).orElseThrow();
    }

    private static boolean place(World world, int x, int y, int z, String block) {
        return CraftEngineBlocks.place(new Location(world, x, y, z), Key.of(NAMESPACE, block), false);
    }

    private static String missingBlocks() {
        return " (the check blocks are installed on MachineEngine's dev servers only)";
    }

    /** Inserts or extracts {@code stack} through {@code port} in one transaction; returns the amount moved. */
    private static long move(ResourceHandler port, ResourceStack stack, boolean insert) {
        try (Transaction transaction = MachineEngine.get().transactions().openOuter()) {
            long moved = insert ? port.insert(stack.variant(), stack.amount(), transaction)
                    : port.extract(stack.variant(), stack.amount(), transaction);
            transaction.commit();
            return moved;
        }
    }

    private static int ironIn(Inventory inventory) {
        int total = 0;
        for (ItemStack stack : inventory.getContents()) {
            if (stack != null && stack.getType() == Material.IRON_INGOT) total += stack.getAmount();
        }
        return total;
    }

    private static void reply(CommandSender sender, String message) {
        EngineMessages.send(sender, EngineMessage.ADMIN_CHECK, Placeholder.of("result", message));
    }

    private void log(String message) {
        Logger.info("[checks]", "%message%", Placeholder.of("message", message));
    }

    /** What a check does at its position, on the thread owning it. */
    @FunctionalInterface
    private interface Check {
        void run(@NotNull CommandDispatch<Plugin> dispatch, @NotNull World world, int x, int y, int z);
    }

    /** A check at {@code [<x y z>] [world]}; arguments given to the constructor come before the position. */
    private static final class At extends SubCommand<Plugin> {
        private final Check check;

        @SafeVarargs
        At(Plugin plugin, String name, Check check, ArgumentBuilder<CommandSourceStack, ?>... before) {
            super(plugin, name);
            this.check = check;
            for (ArgumentBuilder<CommandSourceStack, ?> argument : before) addRequiredArgument(argument);
            addOptionalArgument(PositionArguments.positionArgument());
            addOptionalArgument(PositionArguments.worldArgument());
        }

        @Override
        protected @NotNull CommandResultType perform(@NotNull CommandDispatch<Plugin> dispatch) {
            PositionArguments.Position at = PositionArguments.resolve(dispatch.getContext(), PositionArguments.Default.PLACEMENT);
            if (at == null) return CommandResultType.FAILURE;
            MachineEngine.scheduler(this.plugin).run(new Location(at.world(), at.x(), at.y(), at.z()),
                    () -> this.check.run(dispatch, at.world(), at.x(), at.y(), at.z()));
            return CommandResultType.SUCCESS;
        }
    }
}
