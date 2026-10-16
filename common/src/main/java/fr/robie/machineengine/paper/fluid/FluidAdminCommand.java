package fr.robie.machineengine.paper.fluid;

import com.mojang.brigadier.arguments.StringArgumentType;
import fr.robie.machineengine.api.MachineEngine;
import fr.robie.machineengine.api.paper.event.WorldFluidPlaceEvent;
import fr.robie.machineengine.api.platform.Scheduler;
import fr.robie.machineengine.api.platform.Task;
import fr.robie.machineengine.api.resource.Fluids;
import fr.robie.machineengine.api.resource.fluid.FluidType;
import fr.robie.machineengine.paper.fluid.client.Cells;
import fr.robie.machineengine.paper.fluid.client.FluidCells;
import fr.robie.machineengine.paper.fluid.client.FluidKeys;
import fr.robie.machineengine.paper.message.EngineMessage;
import fr.robie.machineengine.paper.message.EngineMessages;
import fr.robie.messageflow.formatter.Placeholder;
import fr.robie.paperdispatch.command.CommandDispatch;
import fr.robie.paperdispatch.command.CommandResultType;
import fr.robie.paperdispatch.command.SubCommand;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * {@code /machineengine fluid [info | show [<fluid>] [radius] | fill <fluid|water> [max] | bucket <fluid> | clear]}
 * (D46): what world fluids are doing, where a fluid may go (a particle grid seen only by the player),
 * and test tools. Fill and clear fire the usual events with the {@code COMMAND} cause.
 */
@SuppressWarnings("UnstableApiUsage")
public final class FluidAdminCommand extends SubCommand<Plugin> {
    private static final String USAGE = "Usage: /machineengine fluid [info | show [<fluid>] [radius] | fill <fluid|water> [max] | bucket <fluid> | clear]";
    private static final BlockFace[] FACES = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
    private static final Particle.DustOptions CAN = new Particle.DustOptions(Color.fromRGB(0x30E030), 0.9f);
    private static final Particle.DustOptions CANNOT = new Particle.DustOptions(Color.fromRGB(0xE03030), 0.9f);
    private static final Particle.DustOptions ALREADY = new Particle.DustOptions(Color.fromRGB(0x30D0E0), 0.9f);

    private final Supplier<@Nullable WorldFluidRuntime> runtime;
    private final Map<UUID, Task> showing = new ConcurrentHashMap<>();

    public FluidAdminCommand(@NotNull Plugin plugin, @NotNull Supplier<@Nullable WorldFluidRuntime> runtime) {
        super(plugin, "fluid");
        this.runtime = runtime;
        addOptionalArgument(Commands.argument("arguments", StringArgumentType.greedyString())
                .suggests((context, builder) -> {
                    WorldFluidRuntime running = runtime.get();
                    String typed = builder.getRemaining();
                    String[] words = typed.split(" ", -1);
                    String before = typed.substring(0, typed.length() - words[words.length - 1].length());
                    String last = words[words.length - 1].toLowerCase(Locale.ROOT);
                    List<String> options = new ArrayList<>();
                    if (words.length == 1) {
                        options.addAll(List.of("info", "show", "fill", "bucket", "clear"));
                    } else if (words.length == 2 && running != null && List.of("show", "fill", "bucket").contains(words[0])) {
                        running.keys().fluids().forEach(fluid -> options.add(fluid.key().asString()));
                        if (words[0].equals("fill")) options.add("water");
                    }
                    for (String option : options) {
                        if (option.toLowerCase(Locale.ROOT).startsWith(last)) builder.suggest(before + option);
                    }
                    return builder.buildFuture();
                }));
    }

    @Override
    protected @NotNull CommandResultType perform(@NotNull CommandDispatch<Plugin> dispatch) {
        CommandSender sender = dispatch.getSender();
        WorldFluidRuntime running = this.runtime.get();
        if (running == null) {
            EngineMessages.send(sender, EngineMessage.ADMIN_FLUID_NOT_RUNNING);
            return CommandResultType.FAILURE;
        }
        String[] words = dispatch.getOptionalArgument("arguments", String.class).orElse("info").trim().split(" +");
        String action = words[0].toLowerCase(Locale.ROOT);
        if (action.equals("info")) return info(sender, running);
        if (!(dispatch.getExecutor() instanceof Player player)) {
            EngineMessages.send(sender, EngineMessage.ADMIN_FLUID_PLAYERS_ONLY);
            return CommandResultType.FAILURE;
        }
        return switch (action) {
            case "show" -> show(player, running, words);
            case "fill" -> fill(player, running, words);
            case "bucket" -> bucket(player, running, words);
            case "clear" -> {
                running.clear(player.getWorld());
                EngineMessages.send(player, EngineMessage.ADMIN_FLUID_CLEARED, Placeholder.of("world", player.getWorld().getName()));
                yield CommandResultType.SUCCESS;
            }
            default -> {
                EngineMessages.send(sender, EngineMessage.ADMIN_USAGE, Placeholder.of("usage", USAGE));
                yield CommandResultType.FAILURE;
            }
        };
    }

    /** Stops every placement grid (MachineEngine disables). */
    public void stopAll() {
        this.showing.values().forEach(Task::cancel);
        this.showing.clear();
    }

    private CommandResultType info(CommandSender sender, WorldFluidRuntime running) {
        FluidKeys keys = running.keys();
        long[] counters = running.counters();
        Player player = sender instanceof Player p ? p : null;
        EngineMessages.send(sender, EngineMessage.ADMIN_FLUID_STATUS, Placeholder.builder()
                .register("fluids", String.valueOf(keys.fluidCount()))
                .register("looks", String.valueOf(keys.keyCount()))
                .register("missing", String.valueOf(keys.withoutKey().size()))
                .register("uniform", String.valueOf(keys.uniform()))
                .register("base", counters[0] < 0 ? "?" : String.valueOf(counters[0]))
                .register("entries", String.valueOf(keys.entryCount()))
                .register("updates", String.valueOf(counters[1]))
                .register("tracked", player == null ? "-" : String.valueOf(running.cells().size(player.getWorld().getUID())))
                .build());
        if (player == null) return CommandResultType.SUCCESS;
        Block look = player.getTargetBlockExact(16, FluidCollisionMode.ALWAYS);
        if (look == null) return CommandResultType.SUCCESS;
        MachineEngine.scheduler(this.plugin).run(look.getLocation(), () -> {
            FluidCells cells = running.cells();
            UUID world = look.getWorld().getUID();
            long cell = cells.readCell(world, look.getX(), look.getY(), look.getZ());
            int entry = cells.key(world, cell);
            String area = entry == 0 ? "its own biome" : entry == keys.neutralEntry() ? "the neutral water"
                    : "key " + ((entry - 1) / FluidKeys.LEVELS + 1) + " at " + Math.round(100 * FluidKeys.LEVEL_SHARE[(entry - 1) % FluidKeys.LEVELS]) + "%";
            var at = running.at(look);
            EngineMessages.send(player, EngineMessage.ADMIN_FLUID_BLOCK, Placeholder.builder()
                    .register("x", String.valueOf(look.getX())).register("y", String.valueOf(look.getY())).register("z", String.valueOf(look.getZ()))
                    .register("fluid", at == null ? Component.text("-") : WorldFluidRuntime.name(at))
                    .register("cell", Cells.x(cell) + " " + Cells.y(cell) + " " + Cells.z(cell))
                    .register("area", area)
                    .build());
            if (WorldFluidRuntime.isWater(look)) {
                WorldFluidRuntime.Refusal refusal = running.refusal(look, Fluids.water());
                if (refusal != null) {
                    EngineMessages.send(player, EngineMessage.ADMIN_FLUID_BLOCK_REFUSED, Placeholder.builder()
                            .register("reason", EngineMessages.component(refusal.message(), player, refusal.placeholders())).build());
                }
            }
        });
        return CommandResultType.SUCCESS;
    }

    private @Nullable FluidType fluid(Player player, WorldFluidRuntime running, String name) {
        for (FluidType fluid : running.keys().fluids()) {
            if (fluid.key().asString().equalsIgnoreCase(name) || fluid.key().value().equalsIgnoreCase(name)
                    || fluid.key().value().endsWith("/" + name.toLowerCase(Locale.ROOT))) return fluid;
        }
        EngineMessages.send(player, EngineMessage.ADMIN_FLUID_UNKNOWN, Placeholder.of("fluid", name,
                "fluids", String.valueOf(running.keys().fluids().stream().map(fluid -> fluid.key().asString()).toList())));
        return null;
    }

    private CommandResultType show(Player player, WorldFluidRuntime running, String[] words) {
        Task previous = this.showing.remove(player.getUniqueId());
        if (previous != null) previous.cancel();
        if (words.length < 2 || words[1].equalsIgnoreCase("off")) {
            EngineMessages.send(player, EngineMessage.ADMIN_FLUID_SHOW_OFF);
            return CommandResultType.SUCCESS;
        }
        FluidType fluid = fluid(player, running, words[1]);
        if (fluid == null) return CommandResultType.FAILURE;
        int radius = words.length > 2 ? Math.max(2, Math.min(24, parse(words[2], 12))) : 12;
        Task task = MachineEngine.scheduler(this.plugin).runTimer(player, () -> showTick(player, running, fluid, radius),
                () -> this.showing.remove(player.getUniqueId()), 5L, 10L);
        if (task != null) this.showing.put(player.getUniqueId(), task);
        EngineMessages.send(player, EngineMessage.ADMIN_FLUID_SHOW_ON, Placeholder.builder().register("fluid", WorldFluidRuntime.name(fluid)).build());
        return CommandResultType.SUCCESS;
    }

    /** One particle per air or water block at the player's feet; the answer is the same for a whole color area. */
    private void showTick(Player player, WorldFluidRuntime running, FluidType fluid, int radius) {
        World world = player.getWorld();
        Location feet = player.getLocation();
        int y = feet.getBlockY();
        int number = running.keys().number(fluid);
        FluidCells cells = running.cells();
        Map<Long, Boolean> byCell = new HashMap<>();
        int allowed = 0, refused = 0;
        Scheduler scheduler = MachineEngine.scheduler(this.plugin);
        for (int x = feet.getBlockX() - radius; x <= feet.getBlockX() + radius; x++) {
            for (int z = feet.getBlockZ() - radius; z <= feet.getBlockZ() + radius; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4) || !scheduler.isOwnedByCurrentThread(world, x >> 4, z >> 4)) continue;
                Block block = world.getBlockAt(x, y, z);
                if (!block.getType().isAir() && !WorldFluidRuntime.isWater(block)) continue;
                Particle.DustOptions dust;
                if (running.number(block) == number) {
                    dust = ALREADY;
                } else {
                    boolean can = byCell.computeIfAbsent(cells.readCell(world.getUID(), x, y, z), ignored -> running.refusal(block, fluid) == null);
                    dust = can ? CAN : CANNOT;
                    if (can) allowed++;
                    else refused++;
                }
                player.spawnParticle(Particle.DUST, x + 0.5, y + 0.15, z + 0.5, 1, 0, 0, 0, 0, dust);
            }
        }
        EngineMessages.send(player, EngineMessage.FLUID_SHOW_BAR, Placeholder.builder()
                .register("fluid", WorldFluidRuntime.name(fluid))
                .register("allowed", String.valueOf(allowed))
                .register("refused", String.valueOf(refused))
                .build());
    }

    /** The water looked at, and the water connected to it (in this region), becomes the fluid. */
    private CommandResultType fill(Player player, WorldFluidRuntime running, String[] words) {
        if (words.length < 2) {
            EngineMessages.send(player, EngineMessage.ADMIN_USAGE, Placeholder.of("usage", USAGE));
            return CommandResultType.FAILURE;
        }
        FluidType fluid = words[1].equalsIgnoreCase("water") ? null : fluid(player, running, words[1]);
        if (fluid == null && !words[1].equalsIgnoreCase("water")) return CommandResultType.FAILURE;
        int max = words.length > 2 ? Math.max(1, parse(words[2], 4000)) : 4000;
        Block start = player.getTargetBlockExact(16, FluidCollisionMode.ALWAYS);
        if (start == null || !WorldFluidRuntime.isWater(start)) {
            EngineMessages.send(player, EngineMessage.ADMIN_FLUID_NOT_WATER);
            return CommandResultType.FAILURE;
        }
        int number = running.keys().number(fluid);
        Scheduler scheduler = MachineEngine.scheduler(this.plugin);
        scheduler.run(start.getLocation(), () -> {
            World world = start.getWorld();
            ArrayDeque<Block> queue = new ArrayDeque<>();
            Set<Long> seen = new HashSet<>();
            queue.add(start);
            seen.add(Cells.pack(start.getX(), start.getY(), start.getZ()));
            int count = 0;
            while (!queue.isEmpty() && count < max) {
                Block block = queue.poll();
                if (fluid == null || new WorldFluidPlaceEvent(block, fluid, WorldFluidPlaceEvent.Cause.COMMAND).callEvent()) {
                    running.setFluid(block, number);
                    count++;
                }
                for (BlockFace face : FACES) {
                    Block next = block.getRelative(face);
                    if (!scheduler.isOwnedByCurrentThread(world, next.getX() >> 4, next.getZ() >> 4)) continue;
                    if (!seen.add(Cells.pack(next.getX(), next.getY(), next.getZ()))) continue;
                    if (WorldFluidRuntime.isWater(next)) queue.add(next);
                }
            }
            EngineMessages.send(player, EngineMessage.ADMIN_FLUID_FILLED, Placeholder.builder()
                    .register("count", String.valueOf(count))
                    .register("fluid", fluid == null ? WorldFluidRuntime.name(Fluids.water()) : WorldFluidRuntime.name(fluid))
                    .build());
        });
        return CommandResultType.SUCCESS;
    }

    private CommandResultType bucket(Player player, WorldFluidRuntime running, String[] words) {
        if (words.length < 2) {
            EngineMessages.send(player, EngineMessage.ADMIN_USAGE, Placeholder.of("usage", USAGE));
            return CommandResultType.FAILURE;
        }
        FluidType fluid = fluid(player, running, words[1]);
        if (fluid == null) return CommandResultType.FAILURE;
        var bucket = running.bucket(fluid);
        if (bucket == null) {
            EngineMessages.send(player, EngineMessage.FLUID_NOT_PLACEABLE, Placeholder.builder().register("fluid", WorldFluidRuntime.name(fluid)).build());
            return CommandResultType.FAILURE;
        }
        player.getInventory().addItem(bucket);
        return CommandResultType.SUCCESS;
    }

    private static int parse(String text, int fallback) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
