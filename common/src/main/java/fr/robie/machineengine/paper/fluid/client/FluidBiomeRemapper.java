package fr.robie.machineengine.paper.fluid.client;

import net.momirealms.craftengine.bukkit.plugin.network.listener.game.LevelChunkWithLightListener;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.world.SectionPos;
import net.momirealms.craftengine.core.world.chunk.PalettedContainer;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

import java.util.function.IntSupplier;

/**
 * Gives each keyed cell its key biome in every chunk packet CraftEngine sends (D46). Runs on Netty
 * threads. CraftEngine can't remove a remapper, so {@link #stop()} only makes it do nothing.
 */
public final class FluidBiomeRemapper implements LevelChunkWithLightListener.BiomeRemapper {
    private final FluidCells cells;
    private final IntSupplier base;
    private volatile boolean active = true;

    public FluidBiomeRemapper(@NotNull FluidCells cells, @NotNull IntSupplier base) {
        this.cells = cells;
        this.base = base;
    }

    public void stop() {
        this.active = false;
    }

    @Override
    public boolean remap(Player player, SectionPos pos, PalettedContainer<Integer> biomes) {
        if (!this.active) return false;
        int base = this.base.getAsInt();
        if (base < 0 || !(player.platformPlayer() instanceof org.bukkit.entity.Player bukkitPlayer)) return false;
        World world = bukkitPlayer.getWorld();
        if (!this.cells.hasSection(world.getUID(), pos.x(), pos.y(), pos.z())) return false;
        boolean changed = false;
        for (int cx = 0; cx < 4; cx++) for (int cy = 0; cy < 4; cy++) for (int cz = 0; cz < 4; cz++) {
            int entry = this.cells.key(world.getUID(), Cells.pack((pos.x() << 2) + cx, (pos.y() << 2) + cy, (pos.z() << 2) + cz));
            if (entry == 0) continue;
            biomes.set(cx, cy, cz, base + entry - 1);
            changed = true;
        }
        return changed;
    }
}
