package fr.robie.machineengine.api.paper;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
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
 *
 * <p>Brigadier types only, so any command library can use it (D35): add {@link #positionArgument()}
 * then {@link #worldArgument()} as optional arguments and call {@link #resolve} when the command runs.
 */
@SuppressWarnings("UnstableApiUsage")
public final class PositionArguments {
    private static final int REACH = 8;
    private static final String POSITION = "position";
    private static final String WORLD = "world";

    /** What a player's position defaults to when no coordinates are given. */
    public enum Default {
        /** The block the player is looking at (inspection). */
        TARGET_BLOCK,
        /** The block above the one the player is looking at, else the player's feet (placement). */
        PLACEMENT
    }

    /** A command position: a world and block coordinates. */
    public record Position(@NotNull World world, int x, int y, int z) {
    }

    private PositionArguments() {
    }

    /** The {@code <x y z>} argument; {@link #resolve} reads it. */
    public static @NotNull RequiredArgumentBuilder<CommandSourceStack, BlockPositionResolver> positionArgument() {
        return Commands.argument(POSITION, ArgumentTypes.blockPosition());
    }

    /** The {@code <world>} argument (rest of the line, world names suggested), after {@link #positionArgument()}. */
    public static @NotNull RequiredArgumentBuilder<CommandSourceStack, String> worldArgument() {
        return Commands.argument(WORLD, StringArgumentType.greedyString())
                .suggests((context, builder) -> {
                    Bukkit.getWorlds().forEach(world -> builder.suggest(world.getName()));
                    return builder.buildFuture();
                });
    }

    /**
     * The position a command was run for: its {@link #positionArgument()} and {@link #worldArgument()},
     * else {@code fallback} for a player.
     *
     * @return the position, or null after telling the sender what is wrong (console without
     *         coordinates, unknown world, nothing looked at, invalid coordinates)
     */
    public static @Nullable Position resolve(@NotNull CommandContext<CommandSourceStack> context, @NotNull Default fallback) {
        try {
            if (!has(context, POSITION)) return fromExecutor(context, fallback);
            World world = null;
            if (has(context, WORLD)) {
                String name = StringArgumentType.getString(context, WORLD).trim();
                world = world(name);
                if (world == null) return fail(context, "Unknown world '" + name + "'.");
            }
            return fromArguments(context, world);
        } catch (CommandSyntaxException e) {
            return fail(context, e.getRawMessage().getString());
        }
    }

    /** A world by name ({@code world}) or key ({@code minecraft:overworld}). */
    public static @Nullable World world(@NotNull String name) {
        World world = Bukkit.getWorld(name);
        if (world != null) return world;
        NamespacedKey key = NamespacedKey.fromString(name);
        return key == null ? null : Bukkit.getWorld(key);
    }

    private static boolean has(CommandContext<CommandSourceStack> context, String argument) {
        try {
            context.getArgument(argument, Object.class);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static @Nullable Position fromArguments(CommandContext<CommandSourceStack> context, @Nullable World world)
            throws CommandSyntaxException {
        BlockPosition position = context.getArgument(POSITION, BlockPositionResolver.class).resolve(context.getSource());
        World target = world != null ? world : context.getSource().getLocation().getWorld();
        if (target == null) return fail(context, "Give a world.");
        return new Position(target, position.blockX(), position.blockY(), position.blockZ());
    }

    private static @Nullable Position fromExecutor(CommandContext<CommandSourceStack> context, Default fallback) {
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
        return new Position(location.getWorld(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    private static @Nullable Position fail(CommandContext<CommandSourceStack> context, String message) {
        context.getSource().getSender().sendMessage(Component.text(message, NamedTextColor.RED));
        return null;
    }
}
