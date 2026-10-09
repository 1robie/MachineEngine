package fr.robie.machineengine.api.machine;

import fr.robie.machineengine.api.resource.ResourceStack;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Something a player asks a machine to do from a menu (D40): take an upgrade out, switch a module off,
 * … A machine type declares its actions by id ({@link MachineType.Builder#action}); a menu button names
 * one ({@code machineengine_action}, {@code action: <id>}) and runs it with the click. Runs on the
 * machine's owner thread; the action changes the machine through its context and wakes it if needed.
 *
 * <pre>{@code
 * MachineType.builder(key("station"))
 *     .action("take", (context, click) -> {
 *         ResourceStack taken = takeOut(context, click.index());
 *         return taken == null ? Outcome.refused(Component.text("Nothing there")) : Outcome.give(taken);
 *     })
 * }</pre>
 */
@FunctionalInterface
public interface MachineAction {

    @NotNull Outcome run(@NotNull MachineContext context, @NotNull Click click);

    /** How the player clicked. */
    enum ClickKind {
        LEFT,
        RIGHT,
        /** Shift and left or right click. */
        SHIFT,
        /** The drop key over the button. */
        DROP,
        /** A shift-click on a stack in the player's own inventory, sent to the menu's input action. */
        INSERT
    }

    /**
     * @param player who clicked
     * @param index  the button's index ({@code index:}; the row of a {@link MachineList}), 0 by default
     * @param held   the stack on the cursor, or the stack shift-clicked for {@link ClickKind#INSERT}; null if none
     */
    record Click(@NotNull UUID player, @NotNull ClickKind kind, int index, @Nullable ResourceStack held) {
        public Click {
            Objects.requireNonNull(player);
            Objects.requireNonNull(kind);
        }
    }

    /**
     * What came of an action.
     *
     * @param done    whether it did anything (the menu redraws)
     * @param used    how much of {@link Click#held} the machine took
     * @param give    what goes to the player's inventory (dropped at their feet if full)
     * @param message shown to the player (action bar), or null
     */
    record Outcome(boolean done, long used, @NotNull List<ResourceStack> give, @Nullable Component message) {
        public Outcome {
            if (used < 0) throw new IllegalArgumentException("used must not be negative");
            give = List.copyOf(give);
        }

        public static @NotNull Outcome ok() {
            return new Outcome(true, 0, List.of(), null);
        }

        /** Took {@code used} of the held stack. */
        public static @NotNull Outcome used(long used) {
            return new Outcome(true, used, List.of(), null);
        }

        /** Gives {@code stacks} to the player. */
        public static @NotNull Outcome give(@NotNull ResourceStack... stacks) {
            return new Outcome(true, 0, List.of(stacks), null);
        }

        /** Did nothing, and tells the player why. */
        public static @NotNull Outcome refused(@NotNull Component message) {
            return new Outcome(false, 0, List.of(), Objects.requireNonNull(message));
        }

        /** Did nothing, silently (e.g. a click on an empty row). */
        public static @NotNull Outcome nothing() {
            return new Outcome(false, 0, List.of(), null);
        }

        public @NotNull Outcome withMessage(@Nullable Component message) {
            return new Outcome(this.done, this.used, this.give, message);
        }
    }
}
