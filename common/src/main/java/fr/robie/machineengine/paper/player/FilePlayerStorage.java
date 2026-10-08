package fr.robie.machineengine.paper.player;

import fr.robie.machineengine.api.MachineEngineKeys;
import fr.robie.machineengine.api.data.DataBinary;
import fr.robie.machineengine.api.data.DataMap;
import fr.robie.machineengine.api.player.PlayerDataStorage;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code machineengine:files} (D37): one file per player, {@code plugins/MachineEngine/players/<ab>/<uuid>.dat}
 * ({@code <ab>}: the UUID's first two characters), in {@link DataBinary} form. Files are read and written on
 * a thread of their own; a write goes to a temporary file that then replaces the old one, so a crash leaves
 * either the old or the new data. Loads and saves of one player run in the order they were asked, so a
 * player who leaves and comes back at once reads what was saved when they left.
 */
public final class FilePlayerStorage implements PlayerDataStorage {
    public static final Type TYPE = new Type(MachineEngineKeys.key("files"), (plugin, config) ->
            new FilePlayerStorage(plugin.getDataFolder().toPath().resolve("players"), plugin.getLogger()));

    private final Path folder;
    private final Logger logger;
    private final ExecutorService io;
    /** The last load or save asked for each player; the next one waits for it. */
    private final Map<UUID, CompletableFuture<?>> last = new ConcurrentHashMap<>();

    public FilePlayerStorage(@NotNull Path folder, @NotNull Logger logger) {
        this.folder = folder;
        this.logger = logger;
        this.io = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "MachineEngine player data");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Where {@code uuid}'s data is kept. */
    public @NotNull Path file(@NotNull UUID uuid) {
        String id = uuid.toString();
        return this.folder.resolve(id.substring(0, 2)).resolve(id + ".dat");
    }

    @Override
    public @NotNull CompletableFuture<DataMap> load(@NotNull UUID uuid, @Nullable Player online) {
        return after(uuid, () -> read(uuid));
    }

    @Override
    public @NotNull CompletableFuture<Void> save(@NotNull UUID uuid, @Nullable Player online, @NotNull DataMap data) {
        byte[] bytes = DataBinary.write(data);
        return after(uuid, () -> {
            write(uuid, bytes);
            return null;
        });
    }

    /** Runs {@code task} for {@code uuid} once the previous one for the same player has finished. */
    private <T> CompletableFuture<T> after(UUID uuid, Supplier<T> task) {
        CompletableFuture<T> next = new CompletableFuture<>();
        CompletableFuture<?> previous = this.last.put(uuid, next);
        CompletableFuture<?> before = previous == null ? CompletableFuture.completedFuture(null) : previous;
        before.handle((ignored, error) -> null).thenRunAsync(() -> {
            try {
                next.complete(task.get());
            } catch (RuntimeException e) {
                next.completeExceptionally(e);
            }
        }, this.io);
        next.whenComplete((ignored, error) -> this.last.remove(uuid, next));
        return next;
    }

    private DataMap read(UUID uuid) {
        Path file = file(uuid);
        byte[] bytes;
        try {
            if (!Files.exists(file)) return DataMap.EMPTY;
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
        try {
            return DataBinary.read(bytes);
        } catch (IOException e) {
            // Damaged: keep the file aside so nothing is destroyed, and start empty.
            Path aside = file.resolveSibling(file.getFileName() + ".damaged-" + System.currentTimeMillis());
            try {
                Files.move(file, aside);
            } catch (IOException moveError) {
                throw new UncheckedIOException("could not move the damaged " + file + " aside", moveError);
            }
            this.logger.severe("The MachineEngine data of " + uuid + " was damaged (" + e.getMessage() + "); kept as "
                    + aside.getFileName() + ", starting empty");
            return DataMap.EMPTY;
        }
    }

    private void write(UUID uuid, byte[] bytes) {
        Path file = file(uuid);
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not write " + file, e);
        }
    }

    @Override
    public void close() {
        try {
            // Failures were already reported to whoever asked; only wait.
            CompletableFuture.allOf(this.last.values().stream().map(pending -> pending.handle((ignored, error) -> null))
                    .toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            this.logger.log(Level.SEVERE, "Player data was still being saved after 30 seconds", e);
        }
        this.io.shutdown();
    }
}
