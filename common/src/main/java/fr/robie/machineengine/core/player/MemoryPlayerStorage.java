package fr.robie.machineengine.core.player;

import fr.robie.machineengine.api.data.DataMap;
import fr.robie.machineengine.api.player.PlayerDataStorage;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Player data kept in memory: headless engines (tests) and the engine before the platform installs its storage. */
public final class MemoryPlayerStorage implements PlayerDataStorage {
    private final Map<UUID, DataMap> saved = new ConcurrentHashMap<>();

    @Override
    public @NotNull CompletableFuture<DataMap> load(@NotNull UUID uuid, @Nullable Player online) {
        return CompletableFuture.completedFuture(this.saved.getOrDefault(uuid, DataMap.EMPTY));
    }

    @Override
    public @NotNull CompletableFuture<Void> save(@NotNull UUID uuid, @Nullable Player online, @NotNull DataMap data) {
        this.saved.put(uuid, data);
        return CompletableFuture.completedFuture(null);
    }
}
