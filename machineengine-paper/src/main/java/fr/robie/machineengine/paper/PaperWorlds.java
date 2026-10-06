package fr.robie.machineengine.paper;

import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Worlds by the keys the engine uses for them. */
public final class PaperWorlds {
    private PaperWorlds() {
    }

    /** The loaded world with this key, or {@code null}. */
    public static @Nullable World world(@NotNull Key key) {
        return Bukkit.getWorld(new NamespacedKey(key.namespace(), key.value()));
    }
}
