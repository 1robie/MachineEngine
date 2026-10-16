package fr.robie.machineengine.paper.fluid.client;

import fr.robie.machineengine.api.platform.WorldFluidNms;
import fr.robie.machineengine.api.resource.fluid.FluidLook;
import fr.robie.machineengine.api.resource.fluid.FluidType;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The placeable fluids numbered 1..n, and the key each one's look gets (§4.3). Fluids that look the same
 * share a key, so keys count distinct looks. Each key has three entries (client biomes): the key, half
 * key and half neutral water, a quarter key (dispersion). Without uniform water, one more entry is the
 * neutral water. Built once at enable, sorted by fluid key so the same fluids always get the same keys.
 */
public final class FluidKeys {
    /** Key strengths: the fluid, then its dispersion rings. */
    public static final double[] LEVEL_SHARE = {1.0, 0.5, 0.25};
    public static final int LEVELS = LEVEL_SHARE.length;
    /** Rings of cells computed around owned cells for padding and dispersion. */
    public static final int MAX_RINGS = 2;
    /** Vanilla's default underwater fog (plains), mixed into diluted keys. */
    public static final int NEUTRAL_FOG = 0x050533;

    private final List<FluidType> fluids;
    private final Map<FluidType, Integer> numbers = new IdentityHashMap<>();
    /** Key index per fluid number (index 0 unused); -1 when the fluid got no key. */
    private final int[] keyOf;
    private final List<Integer> keyColors;
    private final List<Integer> keyFogs;
    private final List<FluidType> withoutKey;
    private final int neutral;
    private final boolean uniform;

    private FluidKeys(List<FluidType> fluids, int[] keyOf, List<Integer> keyColors, List<Integer> keyFogs,
                      List<FluidType> withoutKey, int neutral, boolean uniform) {
        this.fluids = fluids;
        for (int i = 0; i < fluids.size(); i++) this.numbers.put(fluids.get(i), i + 1);
        this.keyOf = keyOf;
        this.keyColors = keyColors;
        this.keyFogs = keyFogs;
        this.withoutKey = withoutKey;
        this.neutral = neutral;
        this.uniform = uniform;
    }

    /**
     * @param placeable every placeable fluid
     * @param look      the look in effect for a fluid (its definition with {@code fluids.yml})
     * @param neutral   the neutral water color
     * @param uniform   whether every biome has the neutral water ({@code world-fluids.uniform-water})
     */
    public static @NotNull FluidKeys of(@NotNull List<FluidType> placeable, @NotNull Function<FluidType, FluidLook> look,
                                        int neutral, boolean uniform) {
        List<FluidType> fluids = new ArrayList<>(placeable);
        fluids.sort(Comparator.comparing(fluid -> fluid.key().asString()));
        Map<FluidLook, Integer> looks = new LinkedHashMap<>();
        int[] keyOf = new int[fluids.size() + 1];
        List<Integer> fogs = new ArrayList<>();
        List<FluidType> withoutKey = new ArrayList<>();
        int limit = uniform ? KeyPalette.MAX_UNIFORM : KeyPalette.VANILLA_WATER_KEYS.length;
        for (int i = 0; i < fluids.size(); i++) {
            FluidType fluid = fluids.get(i);
            FluidLook resolved = resolved(look.apply(fluid), fluid.color());
            Integer index = looks.get(resolved);
            if (index == null && looks.size() < limit) {
                index = looks.size();
                looks.put(resolved, index);
                fogs.add(resolved.fog());
            }
            if (index == null) withoutKey.add(fluid);
            keyOf[i + 1] = index == null ? -1 : index;
        }
        List<Integer> colors = new ArrayList<>();
        if (uniform) {
            colors.addAll(KeyPalette.choose(looks.size(), neutral));
        } else {
            for (int i = 0; i < looks.size(); i++) colors.add(KeyPalette.VANILLA_WATER_KEYS[i]);
        }
        return new FluidKeys(List.copyOf(fluids), keyOf, List.copyOf(colors), List.copyOf(fogs), List.copyOf(withoutKey), neutral, uniform);
    }

    /** What the client draws for a fluid: its look with the tint and fog worked out from its color. */
    public static @NotNull FluidLook resolved(@NotNull FluidLook look, @Nullable Integer color) {
        return new FluidLook(look.still(), look.flow(), look.tintFor(color), look.opacity(), look.glow(), look.fogFor(color), look.frameTime());
    }

    public int fluidCount() {
        return this.fluids.size();
    }

    public @NotNull List<FluidType> fluids() {
        return this.fluids;
    }

    /** A fluid's number (1..n), 0 when it is not placeable. */
    public int number(@Nullable FluidType fluid) {
        return fluid == null ? 0 : this.numbers.getOrDefault(fluid, 0);
    }

    public @Nullable FluidType fluid(int number) {
        return number >= 1 && number <= this.fluids.size() ? this.fluids.get(number - 1) : null;
    }

    /** Whether a fluid got a key (too many different looks leave the last ones without, and unplaceable). */
    public boolean hasKey(int number) {
        return number >= 1 && number < this.keyOf.length && this.keyOf[number] >= 0;
    }

    public @NotNull List<FluidType> withoutKey() {
        return this.withoutKey;
    }

    public int keyCount() {
        return this.keyColors.size();
    }

    public int keyColor(int keyIndex) {
        return this.keyColors.get(keyIndex);
    }

    public int keyIndex(int number) {
        return this.keyOf[number];
    }

    public boolean uniform() {
        return this.uniform;
    }

    public int neutral() {
        return this.neutral;
    }

    /** The key entry (1..) of a fluid at a strength level; 0 when the fluid has no key. */
    public int entry(int number, int level) {
        if (!hasKey(number)) return 0;
        return this.keyOf[number] * LEVELS + level + 1;
    }

    /** The neutral water's entry (after every key's), or 0 with uniform water (every biome already is). */
    public int neutralEntry() {
        return this.uniform ? 0 : keyCount() * LEVELS + 1;
    }

    public int entryCount() {
        return keyCount() * LEVELS + (this.uniform ? 0 : 1);
    }

    /** The client biomes to send, entry {@code e} at index {@code e - 1}. */
    public @NotNull List<WorldFluidNms.KeyBiome> keyBiomes() {
        List<WorldFluidNms.KeyBiome> biomes = new ArrayList<>();
        for (int key = 0; key < keyCount(); key++) {
            for (int level = 0; level < LEVELS; level++) {
                double share = LEVEL_SHARE[level];
                biomes.add(new WorldFluidNms.KeyBiome(Key.key("machineengine", "fluid_key_" + (biomes.size() + 1)),
                        KeyPalette.mix(this.keyColors.get(key), this.neutral, share),
                        KeyPalette.mix(this.keyFogs.get(key), NEUTRAL_FOG, share)));
            }
        }
        if (!this.uniform) {
            biomes.add(new WorldFluidNms.KeyBiome(Key.key("machineengine", "fluid_neutral"), this.neutral, NEUTRAL_FOG));
        }
        return biomes;
    }
}
