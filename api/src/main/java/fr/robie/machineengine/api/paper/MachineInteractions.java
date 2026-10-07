package fr.robie.machineengine.api.paper;

import fr.robie.machineengine.api.machine.MachineInstance;
import net.kyori.adventure.key.Key;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What a machine's block does when a player clicks it or an entity steps on it, on Paper (D32). The
 * {@code machineengine:machine} CraftEngine block behavior calls these; machine behaviors stay free of
 * Bukkit. Registered per machine type, by the addon that owns it, in its {@code onEnable}.
 *
 * <p>A right-click goes first to the held item when it is a
 * {@link fr.robie.machineengine.api.item.MachineTool}, then to {@link Interaction#use}, then opens the
 * machine's menu. Everything runs on the thread that owns the block.</p>
 */
public final class MachineInteractions {
    private static final Map<Key, List<Interaction>> BY_TYPE = new ConcurrentHashMap<>();

    private MachineInteractions() {
    }

    /** A click on a machine's block with the main hand. */
    public record Click(@NotNull Player player, @NotNull ItemStack item, @NotNull MachineInstance machine,
                        @NotNull Block block, @Nullable BlockFace face, boolean sneaking) {
    }

    /** What a machine type's block does; every method's default does nothing. */
    public interface Interaction {
        /**
         * A right-click (the item may be air).
         *
         * @return whether it was handled: the click is cancelled and no menu opens
         */
        default boolean use(@NotNull Click click) {
            return false;
        }

        /**
         * A left-click.
         *
         * @return whether it was handled: the click is cancelled, so the block does not start breaking
         */
        default boolean attack(@NotNull Click click) {
            return false;
        }

        /** An entity standing on the block (each tick it moves on it). */
        default void steppedOn(@NotNull MachineInstance machine, @NotNull Entity entity) {
        }
    }

    /** Adds what blocks of the machine type {@code machineType} do. Several may be registered; each is asked in turn. */
    public static void register(@NotNull Key machineType, @NotNull Interaction interaction) {
        Objects.requireNonNull(interaction);
        BY_TYPE.computeIfAbsent(machineType, key -> new CopyOnWriteArrayList<>()).add(interaction);
    }

    public static void unregister(@NotNull Key machineType, @NotNull Interaction interaction) {
        List<Interaction> list = BY_TYPE.get(machineType);
        if (list != null) list.remove(interaction);
    }

    /** What is registered for {@code machineType}, in registration order (empty if nothing). */
    public static @NotNull List<Interaction> of(@NotNull Key machineType) {
        return BY_TYPE.getOrDefault(machineType, List.of());
    }
}
