package fr.robie.machineengine.api.machine;

import fr.robie.machineengine.api.capability.CapabilityType;
import fr.robie.machineengine.api.error.MachineError;
import fr.robie.machineengine.api.process.ProcessControl;
import fr.robie.machineengine.api.recipe.RecipeLookup;
import fr.robie.machineengine.api.registry.RegistryAccess;
import fr.robie.machineengine.api.resource.ResourceVariant;
import fr.robie.machineengine.api.resource.tag.ResourceTags;
import fr.robie.machineengine.api.transfer.ResourceHandler;
import fr.robie.machineengine.api.transfer.TransactionManager;
import fr.robie.machineengine.api.upgrade.ModifierType;
import fr.robie.machineengine.api.world.BlockPosition;
import fr.robie.machineengine.api.world.RelativeSide;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;

/**
 * Everything a {@link MachineBehavior} may touch (ARCHITECTURE.md §12). Owner thread only.
 *
 * <p>Scheduling: after {@link MachineBehavior#tick} the machine stays {@link SchedulerState#ACTIVE}
 * unless the behavior called one of {@link #sleep()}, {@link #waitForInput()}, {@link #block},
 * {@link #wakeIn(long)} during that tick.</p>
 */
@ApiStatus.NonExtendable
public interface MachineContext {

    @NotNull MachineInstance machine();

    default @NotNull MachineType type() {
        return machine().type();
    }

    /** Full access to one of this machine's own storages, bypassing port rules. */
    @NotNull ResourceHandler storage(@NotNull String id);

    <T> @NotNull T get(@NotNull MachineDataKey<T> key);

    /** Stores a new (immutable) value; persisted with the machine. */
    <T> void set(@NotNull MachineDataKey<T> key, @NotNull T value);

    /** Current game time of the machine's world. */
    long gameTime();

    /**
     * Ticks since the machine last ticked, excluding time spent unloaded (D4). 1 for a machine that
     * ticks every tick.
     */
    long elapsedTicks();

    @NotNull TransactionManager transactions();

    @NotNull ResourceTags tags();

    @NotNull RegistryAccess registries();

    @NotNull RecipeLookup recipes();

    @NotNull ProcessControl process();

    /** @return {@code base} with this machine's upgrade modifiers of {@code type} applied (D16) */
    long modified(@NotNull ModifierType type, long base);

    /** Deterministic per-machine randomness (§19). */
    @NotNull RandomGenerator random();

    // -- scheduling ----------------------------------------------------------------------------

    /** Sleep until any signal. */
    void sleep();

    /** Sleep until something is inserted or inputs change. */
    void waitForInput();

    /**
     * Sleep until something is extracted or output space frees up.
     *
     * @param retryTicks also wake after this many ticks; 0 for no retry
     */
    void block(@NotNull MachineError reason, long retryTicks);

    /** Sleep for {@code ticks} ticks. */
    void wakeIn(long ticks);

    /** Records an error shown in inspection; {@code null} clears it. */
    void error(@Nullable MachineError error);

    /**
     * Whether the machine is visibly working (e.g. a lit furnace front). Hosts show it on the block
     * (a CraftEngine {@code active} property); saved with the machine.
     */
    void setActive(boolean active);

    /** What the machine's block sees of the world: sky, daytime, weather (E3). */
    @NotNull MachineEnvironment environment();

    /**
     * The fluid in the world block at {@code position}, or {@code null} if there is none, or the block
     * is not loaded or not owned by this thread (Folia: another region), E5.
     */
    default @Nullable fr.robie.machineengine.api.world.WorldFluid worldFluidAt(@NotNull BlockPosition position) {
        return null;
    }

    /** Removes the world's fluid source block at {@code position}; same rules as {@link #worldFluidAt}. */
    default boolean takeWorldFluid(@NotNull BlockPosition position) {
        return false;
    }

    /**
     * Changes how one of this machine's ports works, e.g. a machine whose mode turns an input into an
     * output. Not saved: a behavior sets it again from its own data in {@link MachineBehavior#onLoad}.
     *
     * @param port a {@link PortDefinition#id()} of this machine's type
     */
    void setPortDirection(@NotNull String port, @NotNull PortDirection direction);

    /** The port's current direction ({@link #setPortDirection}, or the type's). */
    @NotNull PortDirection portDirection(@NotNull String port);

    // -- neighbors -----------------------------------------------------------------------------

    /**
     * A capability of the neighboring machine on {@code side}, facing back at this machine. Empty
     * if there is none or if the neighbor is owned by another region (use {@link #push} then).
     */
    <T> @NotNull Optional<T> neighbor(@NotNull RelativeSide side, @NotNull CapabilityType<T> type);

    /**
     * Moves up to {@code maxAmount} matching {@code filter} from one of this machine's storages into
     * the port of the neighbor on {@code side}. Same region: a direct transfer. Another region: a
     * handoff through escrow (§16.3).
     */
    @NotNull PushResult push(@NotNull RelativeSide side, @NotNull String storage,
                             @NotNull Predicate<ResourceVariant> filter, long maxAmount);
}
