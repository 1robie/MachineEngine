package fr.robie.machineengine.api.platform;

import fr.robie.machineengine.api.resource.fluid.Viscosity;
import net.kyori.adventure.key.Key;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.LongToIntFunction;

/**
 * What world fluids (D46) need from the server's own classes: client-only key biomes in the registry
 * data, biome-only chunk updates, and the attribute modifiers of viscosity. MachineEngine's own; addons
 * use {@link fr.robie.machineengine.api.paper.WorldFluids}.
 */
@ApiStatus.Internal
public interface WorldFluidNms {
    /** A biome sent only to clients: a copy of the base biome with these colors (0xRRGGBB). */
    record KeyBiome(@NotNull Key id, int waterColor, int fogColor) {
    }

    /**
     * Rewrites the biome registry data sent to each joining player: {@code keyBiomes} are appended after
     * the server's biomes (key biome {@code i} gets client id {@code base + i}), and with
     * {@code uniformWater} every server biome's water color becomes that color.
     *
     * @param onBaseCount receives the number of server biomes ({@code base}) at each join
     */
    void install(@NotNull Key baseBiome, @NotNull List<KeyBiome> keyBiomes, @Nullable Integer uniformWater,
                 @NotNull IntConsumer onBaseCount, @NotNull Consumer<String> log);

    void uninstall();

    /**
     * Sends only a chunk's biomes (no blocks, no light) to {@code viewers}. Runs on the chunk's thread.
     *
     * @param cellIds    the client biome id of a cell (packed like {@code BlockPos.asLong}, in cell
     *                   coordinates), or -1 to keep the chunk's own biome there
     * @param clientSize the number of biomes the client knows (server biomes and key biomes)
     * @return false when the chunk is not loaded
     */
    boolean sendBiomes(@NotNull Collection<? extends Player> viewers, @NotNull World world, int chunkX, int chunkZ,
                       @NotNull LongToIntFunction cellIds, int clientSize);

    /**
     * Writes random biome sections as {@link #sendBiomes} does and reads them back with the server's own
     * reader, as the client does.
     *
     * @return how many of how many sections read back the same ({@code "200/200"} when the format is right)
     */
    @NotNull String checkBiomeWriter(int clientSize);

    /** Applies a fluid's viscosity to a player as attribute modifiers, or removes them ({@code null}). */
    void viscosity(@NotNull Player player, @Nullable Viscosity viscosity);
}
