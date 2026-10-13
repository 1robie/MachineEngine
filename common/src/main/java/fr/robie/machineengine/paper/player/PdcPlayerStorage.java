package fr.robie.machineengine.paper.player;

import fr.robie.machineengine.api.MachineEngineKeys;
import fr.robie.machineengine.api.data.DataBinary;
import fr.robie.machineengine.api.data.DataMap;
import fr.robie.machineengine.api.player.PlayerDataStorage;
import fr.robie.messageflow.formatter.Placeholder;
import fr.robie.messageflow.logger.Logger;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * {@code machineengine:pdc} (D37), the default: a player's data is one {@code BYTE_ARRAY} entry,
 * {@code machineengine:player_data}, of their {@code PersistentDataContainer}, saved by the server with
 * the rest of the player (world/playerdata). Needs the online player: 1.20.6 Paper cannot read an
 * offline player's PDC.
 */
public final class PdcPlayerStorage implements PlayerDataStorage {
    public static final Type TYPE = new Type(MachineEngineKeys.key("pdc"), (plugin, config) -> new PdcPlayerStorage());
    static final NamespacedKey KEY = new NamespacedKey("machineengine", "player_data");

    @Override
    public @NotNull CompletableFuture<DataMap> load(@NotNull UUID uuid, @Nullable Player online) {
        if (online == null) return CompletableFuture.failedFuture(new IllegalStateException("machineengine:pdc needs the online player"));
        byte[] bytes = online.getPersistentDataContainer().get(KEY, PersistentDataType.BYTE_ARRAY);
        if (bytes == null) return CompletableFuture.completedFuture(DataMap.EMPTY);
        try {
            return CompletableFuture.completedFuture(DataBinary.read(bytes));
        } catch (IOException e) {
            // Unreadable: start empty, but keep the bytes under another key so nothing is destroyed.
            Logger.error("[players]", "The MachineEngine data of %player% could not be read (%error%); kept as machineengine:player_data_unreadable, starting empty",
                    Placeholder.of("player", online.getName(), "error", e.getMessage()));
            online.getPersistentDataContainer().set(new NamespacedKey("machineengine", "player_data_unreadable"), PersistentDataType.BYTE_ARRAY, bytes);
            return CompletableFuture.completedFuture(DataMap.EMPTY);
        }
    }

    @Override
    public @NotNull CompletableFuture<Void> save(@NotNull UUID uuid, @Nullable Player online, @NotNull DataMap data) {
        if (online == null) return CompletableFuture.failedFuture(new IllegalStateException("machineengine:pdc needs the online player"));
        online.getPersistentDataContainer().set(KEY, PersistentDataType.BYTE_ARRAY, DataBinary.write(data));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public boolean needsOnlinePlayer() {
        return true;
    }
}
