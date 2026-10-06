package fr.robie.machineengine.api.paper;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.BlockPositionResolver;
import io.papermc.paper.math.BlockPosition;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Optional {@code [<x y z>] [<world>]} arguments that default to the player running the command.
 * Coordinates accept {@code ~} (relative to the executor); the world is a name ({@code world}) or a
 * key ({@code minecraft:overworld}) and defaults to the executor's world. Without coordinates, a
 * player uses the {@link Default} position; the console must give them.
 */
@SuppressWarnings("UnstableApiUsage")
public final class PositionArguments {
    private static final int REACH = 8;

    /** What a player's position defaults to when no coordinates are given. */
    public enum Default {
        /** The block the player is looking at (inspection). */
        TARGET_BLOCK,
        /** The block above the one the player is looking at, else the player's feet (placement). */
        PLACEMENT
    }

    @FunctionalInterface
    public interface PositionCommand {
        int run(@NotNull CommandContext<CommandSourceStack> context, @NotNull World world, int x, int y, int z)
                throws CommandSyntaxException;
    }

    private PositionArguments() {
    }

    /** Adds {@code [<x y z>] [<world>]} to {@code node}, all variants running {@code command}. */
    public static <T extends ArgumentBuilder<CommandSourceStack, T>> @NotNull T optionalPosition(
            @NotNull T node, @NotNull Default fallback, @NotNull PositionCommand command) {
        return node
                .executes(context -> fromExecutor(context, fallback, command))
                .then(Commands.argument("position", ArgumentTypes.blockPosition())
                        .executes(context -> fromArguments(context, null, command))
                        .then(Commands.argument("world", StringArgumentType.greedyString())
                                .suggests((context, builder) -> {
                                    Bukkit.getWorlds().forEach(world -> builder.suggest(world.getName()));
                                    return builder.buildFuture();
                                })
                                .executes(context -> {
                                    String name = StringArgumentType.getString(context, "world").trim();
                                    World world = world(name);
                                    return world == null ? fail(context, "Unknown world '" + name + "'.") : fromArguments(context, world, command);
                                })));
    }

    /** A world by name ({@code world}) or key ({@code minecraft:overworld}). */
    public static @Nullable World world(@NotNull String name) {
        World world = Bukkit.getWorld(name);
        if (world != null) return world;
        NamespacedKey key = NamespacedKey.fromString(name);
        return key == null ? null : Bukkit.getWorld(key);
    }

    private static int fromArguments(CommandContext<CommandSourceStack> context, @Nullable World world,
                                     PositionCommand command) throws CommandSyntaxException {
        BlockPosition position = context.getArgument("position", BlockPositionResolver.class).resolve(context.getSource());
        World target = world != null ? world : context.getSource().getLocation().getWorld();
        if (target == null) return fail(context, "Give a world.");
        return command.run(context, target, position.blockX(), position.blockY(), position.blockZ());
    }

    private static int fromExecutor(CommandContext<CommandSourceStack> context, Default fallback,
                                    PositionCommand command) throws CommandSyntaxException {
        Entity executor = context.getSource().getExecutor();
        if (!(executor instanceof Player player)) return fail(context, "Give a position: <x> <y> <z> [world].");
        Block target = player.getTargetBlockExact(REACH);
        Location location;
        if (fallback == Default.TARGET_BLOCK) {
            if (target == null) return fail(context, "Look at a block, or give a position.");
            location = target.getLocation();
        } else {
            location = target != null ? target.getLocation().add(0, 1, 0) : player.getLocation();
        }
        return command.run(context, location.getWorld(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    private static int fail(CommandContext<CommandSourceStack> context, String message) {
        context.getSource().getSender().sendMessage(Component.text(message, NamedTextColor.RED));
        return 0;
    }
}
