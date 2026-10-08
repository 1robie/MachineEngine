package fr.robie.machineengine.paper;

import com.tcoded.folialib.FoliaLib;
import com.tcoded.folialib.impl.PlatformScheduler;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import fr.robie.machineengine.api.platform.Scheduler;
import fr.robie.machineengine.api.platform.Task;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * {@link Scheduler} on FoliaLib (D35; relocated in the plugin jar). Entity tasks use FoliaLib's
 * {@code Consumer} variants and an {@link EntityTask} handle: the {@code Runnable} variants throw on
 * Folia when the entity is already retired, and the {@code Consumer} ones only hand out their task
 * when it first runs.
 *
 * <p>Nothing is scheduled once the server is stopping: such a task would never run, and Folia throws
 * from its shutdown thread (FoliaLib uses {@code RegionScheduler#run}, which needs the current region's
 * scheduler; machines unloading during shutdown still post). Paper's {@code execute}, used before D35,
 * queued without throwing.
 */
public final class FoliaLibScheduler implements Scheduler {
    private static final Runnable NOTHING = () -> {
    };
    /** Returned instead of a task when nothing was scheduled. */
    private static final Task NOT_SCHEDULED = new Task() {
        @Override
        public void cancel() {
        }

        @Override
        public boolean isCancelled() {
            return true;
        }
    };

    private final FoliaLib foliaLib;
    private final PlatformScheduler scheduler;

    public FoliaLibScheduler(@NotNull Plugin plugin) {
        this.foliaLib = new FoliaLib(plugin);
        this.scheduler = this.foliaLib.getScheduler();
    }

    /** One scheduler per plugin, for {@code MachineEngine.scheduler(plugin)}. */
    public static @NotNull Function<Plugin, Scheduler> perPlugin() {
        Map<Plugin, Scheduler> schedulers = new ConcurrentHashMap<>();
        return plugin -> schedulers.computeIfAbsent(plugin, FoliaLibScheduler::new);
    }

    @Override
    public boolean isFolia() {
        return this.foliaLib.isFolia();
    }

    @Override
    public void run(@NotNull Runnable task) {
        if (stopping()) return;
        this.scheduler.runNextTick(ignored -> task.run());
    }

    @Override
    public @NotNull Task runLater(@NotNull Runnable task, long delay) {
        if (stopping()) return NOT_SCHEDULED;
        return new Wrapped(this.scheduler.runLater(task, ticks(delay)));
    }

    @Override
    public @NotNull Task runTimer(@NotNull Runnable task, long delay, long period) {
        if (stopping()) return NOT_SCHEDULED;
        return new Wrapped(this.scheduler.runTimer(task, ticks(delay), ticks(period)));
    }

    @Override
    public void run(@NotNull Location location, @NotNull Runnable task) {
        if (stopping()) return;
        this.scheduler.runAtLocation(location, ignored -> task.run());
    }

    @Override
    public void run(@NotNull World world, int chunkX, int chunkZ, @NotNull Runnable task) {
        run(chunk(world, chunkX, chunkZ), task);
    }

    @Override
    public @NotNull Task runLater(@NotNull Location location, @NotNull Runnable task, long delay) {
        if (stopping()) return NOT_SCHEDULED;
        return new Wrapped(this.scheduler.runAtLocationLater(location, task, ticks(delay)));
    }

    @Override
    public @NotNull Task runLater(@NotNull World world, int chunkX, int chunkZ, @NotNull Runnable task, long delay) {
        return runLater(chunk(world, chunkX, chunkZ), task, delay);
    }

    @Override
    public @NotNull Task runTimer(@NotNull Location location, @NotNull Runnable task, long delay, long period) {
        if (stopping()) return NOT_SCHEDULED;
        return new Wrapped(this.scheduler.runAtLocationTimer(location, task, ticks(delay), ticks(period)));
    }

    @Override
    public boolean run(@NotNull Entity entity, @NotNull Runnable task, @Nullable Runnable retired) {
        if (stopping() || !entity.isValid()) return false;
        this.scheduler.runAtEntityWithFallback(entity, ignored -> task.run(), retired != null ? retired : NOTHING);
        return true;
    }

    @Override
    public @Nullable Task runLater(@NotNull Entity entity, @NotNull Runnable task, @Nullable Runnable retired, long delay) {
        if (stopping() || !entity.isValid()) return null;
        EntityTask handle = new EntityTask(task, retired);
        this.scheduler.runAtEntityLater(entity, handle::run, handle::retire, ticks(delay));
        return handle;
    }

    @Override
    public @Nullable Task runTimer(@NotNull Entity entity, @NotNull Runnable task, @Nullable Runnable retired,
                                   long delay, long period) {
        if (stopping() || !entity.isValid()) return null;
        EntityTask handle = new EntityTask(task, retired);
        this.scheduler.runAtEntityTimer(entity, handle::run, handle::retire, ticks(delay), ticks(period));
        return handle;
    }

    @Override
    public void runAsync(@NotNull Runnable task) {
        if (stopping()) return;
        this.scheduler.runAsync(ignored -> task.run());
    }

    @Override
    public boolean isOwnedByCurrentThread(@NotNull Location location) {
        return this.scheduler.isOwnedByCurrentRegion(location);
    }

    @Override
    public boolean isOwnedByCurrentThread(@NotNull World world, int chunkX, int chunkZ) {
        return this.scheduler.isOwnedByCurrentRegion(world, chunkX, chunkZ);
    }

    @Override
    public boolean isOwnedByCurrentThread(@NotNull Entity entity) {
        return this.scheduler.isOwnedByCurrentRegion(entity);
    }

    private static boolean stopping() {
        return Bukkit.isStopping();
    }

    /** Folia needs at least one tick; FoliaLib would round up too, with a warning. */
    static long ticks(long ticks) {
        return Math.max(1, ticks);
    }

    private static Location chunk(World world, int chunkX, int chunkZ) {
        return new Location(world, chunkX << 4, 0, chunkZ << 4);
    }

    private record Wrapped(WrappedTask task) implements Task {
        @Override
        public void cancel() {
            this.task.cancel();
        }

        @Override
        public boolean isCancelled() {
            return this.task.isCancelled();
        }
    }

    /**
     * An entity task's handle, usable before FoliaLib hands out its own: a cancelled task skips its
     * next run and cancels FoliaLib's then, and its {@code retired} callback no longer runs.
     */
    static final class EntityTask implements Task {
        private final Runnable task;
        private final @Nullable Runnable retired;
        private volatile boolean cancelled;
        private volatile @Nullable WrappedTask scheduled;

        EntityTask(@NotNull Runnable task, @Nullable Runnable retired) {
            this.task = task;
            this.retired = retired;
        }

        void run(@NotNull WrappedTask scheduled) {
            this.scheduled = scheduled;
            if (this.cancelled) {
                scheduled.cancel();
                return;
            }
            this.task.run();
        }

        void retire() {
            if (!this.cancelled && this.retired != null) this.retired.run();
        }

        @Override
        public void cancel() {
            this.cancelled = true;
            WrappedTask current = this.scheduled;
            if (current != null) current.cancel();
        }

        @Override
        public boolean isCancelled() {
            return this.cancelled;
        }
    }
}
