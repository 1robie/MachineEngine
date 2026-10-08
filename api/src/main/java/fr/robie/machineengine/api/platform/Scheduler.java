package fr.robie.machineengine.api.platform;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Runs code on the thread that owns what it touches (D35), on Paper and Folia alike: on Paper every
 * thread below is the main thread, on Folia it is the global region, the region owning a location, or
 * the region owning an entity. Get one with {@code MachineEngine.scheduler(plugin)}: its tasks belong to
 * that plugin and stop when it is disabled.
 *
 * <p>Delays and periods are in ticks; anything below 1 counts as 1.
 */
@ApiStatus.NonExtendable
public interface Scheduler {

    /** @return whether the server is Folia (regions on several threads) */
    boolean isFolia();

    // -- Global: world-wide state (Folia: the global region thread) --

    /** Runs {@code task} on the next tick. */
    void run(@NotNull Runnable task);

    @NotNull Task runLater(@NotNull Runnable task, long delay);

    @NotNull Task runTimer(@NotNull Runnable task, long delay, long period);

    // -- Region: blocks and chunks at a location --

    /** Runs {@code task} on the next tick of the region owning {@code location}. */
    void run(@NotNull Location location, @NotNull Runnable task);

    /** Runs {@code task} on the next tick of the region owning the chunk. */
    void run(@NotNull World world, int chunkX, int chunkZ, @NotNull Runnable task);

    @NotNull Task runLater(@NotNull Location location, @NotNull Runnable task, long delay);

    @NotNull Task runLater(@NotNull World world, int chunkX, int chunkZ, @NotNull Runnable task, long delay);

    @NotNull Task runTimer(@NotNull Location location, @NotNull Runnable task, long delay, long period);

    // -- Entity: follows the entity across regions --

    /**
     * Runs {@code task} on the next tick of the region owning {@code entity}. If the entity is removed
     * first, {@code retired} runs instead (on the entity's last thread, or at once).
     *
     * @return false, and nothing is scheduled, if the entity is no longer valid (a player who left)
     */
    boolean run(@NotNull Entity entity, @NotNull Runnable task, @Nullable Runnable retired);

    /**
     * Like {@link #run(Entity, Runnable, Runnable)}, after {@code delay}.
     *
     * @return the task, or null, and nothing is scheduled, if the entity is no longer valid
     */
    @Nullable Task runLater(@NotNull Entity entity, @NotNull Runnable task, @Nullable Runnable retired, long delay);

    /**
     * Runs {@code task} every {@code period} until cancelled or the entity is removed; then {@code retired} runs.
     *
     * @return the task, or null, and nothing is scheduled, if the entity is no longer valid
     */
    @Nullable Task runTimer(@NotNull Entity entity, @NotNull Runnable task, @Nullable Runnable retired, long delay, long period);

    // -- Async: no world access --

    /** Runs {@code task} on a pooled thread. It must not touch worlds, entities or players. */
    void runAsync(@NotNull Runnable task);

    // -- Ownership --

    /** @return whether the current thread may touch {@code location} (Paper: the main thread) */
    boolean isOwnedByCurrentThread(@NotNull Location location);

    /** @return whether the current thread may touch the chunk (Paper: the main thread) */
    boolean isOwnedByCurrentThread(@NotNull World world, int chunkX, int chunkZ);

    /** @return whether the current thread may touch {@code entity} (Paper: the main thread) */
    boolean isOwnedByCurrentThread(@NotNull Entity entity);
}
