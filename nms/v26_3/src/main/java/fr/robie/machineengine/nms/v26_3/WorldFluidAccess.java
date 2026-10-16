package fr.robie.machineengine.nms.v26_3;

import fr.robie.machineengine.api.platform.WorldFluidNms;
import fr.robie.machineengine.api.resource.fluid.Viscosity;
import fr.robie.machineengine.nms.BiomeSections;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.papermc.paper.network.ChannelInitializeListenerHolder;
import net.kyori.adventure.key.Key;
import net.minecraft.core.IdMap;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrySynchronization;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.LongToIntFunction;
import java.util.stream.IntStream;

/**
 * World fluids on 26.x (the classes used here are the same in 26.1, 26.2 and 26.3, checked on 26.2 by
 * the spike): key biomes appended to the registry data by a Netty handler, biome-only chunk updates.
 */
public final class WorldFluidAccess implements WorldFluidNms {
    private static final Key LISTENER = Key.key("machineengine", "world_fluids");
    private static final String HANDLER = "machineengine_world_fluids";
    private static final String WATER_FOG = "minecraft:visual/water_fog_color";
    private static final String WATER_COLOR = "minecraft:visual/water_color";
    private static final NamespacedKey MODIFIER = new NamespacedKey("machineengine", "fluid_viscosity");
    private static final List<Attribute> ATTRIBUTES = List.of(Attribute.MOVEMENT_SPEED, Attribute.GRAVITY,
            Attribute.JUMP_STRENGTH, Attribute.WATER_MOVEMENT_EFFICIENCY);

    @Override
    public void install(@NotNull Key baseBiome, @NotNull List<KeyBiome> keyBiomes, @Nullable Integer uniformWater,
                        @NotNull IntConsumer onBaseCount, @NotNull Consumer<String> log) {
        RegistryAccess access = MinecraftServer.getServer().registryAccess();
        Registry<Biome> biomes = access.lookupOrThrow(Registries.BIOME);
        RegistryOps<Tag> ops = access.createSerializationContext(NbtOps.INSTANCE);
        Biome base = biomes.getValue(Identifier.parse(baseBiome.asString()));
        if (base == null) throw new IllegalArgumentException("unknown biome " + baseBiome.asString());
        CompoundTag encoded = (CompoundTag) Biome.NETWORK_CODEC.encodeStart(ops, base).getOrThrow();
        if (uniformWater != null) setWater(encoded, uniformWater);
        List<RegistrySynchronization.PackedRegistryEntry> extra = new ArrayList<>();
        for (KeyBiome key : keyBiomes) {
            CompoundTag copy = encoded.copy();
            setWater(copy, key.waterColor());
            setFog(copy, key.fogColor());
            extra.add(new RegistrySynchronization.PackedRegistryEntry(Identifier.parse(key.id().asString()), Optional.of(copy)));
        }
        // Uniform water: every server biome sent in full, with the neutral water (its own fog kept).
        Map<Identifier, CompoundTag> replaced = new HashMap<>();
        if (uniformWater != null) {
            for (var entry : biomes.entrySet()) {
                CompoundTag biome = (CompoundTag) Biome.NETWORK_CODEC.encodeStart(ops, entry.getValue()).getOrThrow();
                setWater(biome, uniformWater);
                replaced.put(entry.getKey().identifier(), biome);
            }
        }
        ChannelInitializeListenerHolder.addListener(LISTENER, channel -> addHandler(channel, new Handler(extra, replaced, onBaseCount)));
        log.accept(extra.size() + " key biomes" + (uniformWater == null ? "" : ", " + replaced.size() + " biomes with uniform water"));
    }

    @Override
    public void uninstall() {
        if (ChannelInitializeListenerHolder.hasListener(LISTENER)) ChannelInitializeListenerHolder.removeListener(LISTENER);
    }

    @Override
    public boolean sendBiomes(@NotNull Collection<? extends Player> viewers, @NotNull World world, int chunkX, int chunkZ,
                              @NotNull LongToIntFunction cellIds, int clientSize) {
        ServerLevel level = ((CraftWorld) world).getHandle();
        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) return false;
        Registry<Biome> registry = level.registryAccess().lookupOrThrow(Registries.BIOME);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LevelChunkSection[] sections = chunk.getSections();
        int minSection = level.getMinSectionY();
        int[] ids = new int[64];
        for (int s = 0; s < sections.length; s++) {
            var biomes = sections[s].getBiomes();
            int sectionY = minSection + s;
            for (int y = 0; y < 4; y++) for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++) {
                int id = cellIds.applyAsInt(net.minecraft.core.BlockPos.asLong(chunkX * 4 + x, sectionY * 4 + y, chunkZ * 4 + z));
                ids[(y << 4) | (z << 2) | x] = id >= 0 ? id : registry.getId(biomes.get(x, y, z).value());
            }
            BiomeSections.write(out, ids, clientSize, false);
        }
        var packet = new ClientboundChunksBiomesPacket(List.of(
                new ClientboundChunksBiomesPacket.ChunkBiomeData(new ChunkPos(chunkX, chunkZ), out.toByteArray())));
        for (Player viewer : viewers) ((CraftPlayer) viewer).getHandle().connection.send(packet);
        return true;
    }

    @Override
    @SuppressWarnings("deprecation") // the reader the client uses; no other constructor builds an empty container
    public @NotNull String checkBiomeWriter(int clientSize) {
        IdMap<Integer> ids = new IdMap<>() {
            @Override
            public int getId(Integer value) {
                return value;
            }

            @Override
            public Integer byId(int id) {
                return id >= 0 && id < clientSize ? id : null;
            }

            @Override
            public int size() {
                return clientSize;
            }

            @Override
            public @NotNull Iterator<Integer> iterator() {
                return IntStream.range(0, clientSize).boxed().iterator();
            }
        };
        Strategy<Integer> strategy = Strategy.createForBiomes(ids);
        Random random = new Random(3);
        int good = 0, total = 0;
        for (int distinct : new int[]{1, 2, 3, 4, 5, 8, 9, 17, 33, 64}) {
            for (int round = 0; round < 20; round++) {
                int[] section = randomSection(random, clientSize, distinct);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                BiomeSections.write(out, section, clientSize, false);
                FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(out.toByteArray()));
                total++;
                try {
                    PalettedContainer<Integer> container = new PalettedContainer<>(0, strategy);
                    container.read(buf);
                    boolean same = buf.readableBytes() == 0;
                    for (int i = 0; i < 64 && same; i++) same = container.get(i & 3, i >> 4, (i >> 2) & 3) == section[i];
                    if (same) good++;
                } catch (RuntimeException e) {
                    // counted as a failure
                }
            }
        }
        return good + "/" + total;
    }

    @Override
    public void viscosity(@NotNull Player player, @Nullable Viscosity viscosity) {
        for (Attribute attribute : ATTRIBUTES) {
            AttributeInstance instance = player.getAttribute(attribute);
            if (instance != null) instance.removeModifier(MODIFIER);
        }
        if (viscosity == null) return;
        multiply(player, Attribute.MOVEMENT_SPEED, viscosity.speed());
        multiply(player, Attribute.GRAVITY, viscosity.gravity());
        multiply(player, Attribute.JUMP_STRENGTH, viscosity.jump());
        if (viscosity.swim() != 0) add(player, Attribute.WATER_MOVEMENT_EFFICIENCY, viscosity.swim(), AttributeModifier.Operation.ADD_NUMBER);
    }

    static int[] randomSection(Random random, int clientSize, int distinct) {
        int[] pool = random.ints(0, clientSize).distinct().limit(Math.min(distinct, clientSize)).toArray();
        int[] section = new int[64];
        for (int i = 0; i < 64; i++) section[i] = pool[i < pool.length ? i : random.nextInt(pool.length)];
        return section;
    }

    private static void multiply(Player player, Attribute attribute, double factor) {
        if (factor != 1) add(player, attribute, factor - 1, AttributeModifier.Operation.MULTIPLY_SCALAR_1);
    }

    private static void add(Player player, Attribute attribute, double amount, AttributeModifier.Operation operation) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance != null) instance.addTransientModifier(new AttributeModifier(MODIFIER, amount, operation, EquipmentSlotGroup.ANY));
    }

    private static void setWater(CompoundTag biome, int rgb) {
        CompoundTag effects = biome.getCompoundOrEmpty("effects");
        effects.put("water_color", colorLike(effects.get("water_color"), rgb));
        biome.put("effects", effects);
        CompoundTag attributes = biome.getCompoundOrEmpty("attributes");
        if (attributes.contains(WATER_COLOR)) {
            attributes.put(WATER_COLOR, colorLike(attributes.get(WATER_COLOR), rgb));
            biome.put("attributes", attributes);
        }
    }

    private static void setFog(CompoundTag biome, int rgb) {
        CompoundTag effects = biome.getCompoundOrEmpty("effects");
        if (effects.contains("water_fog_color")) {
            effects.put("water_fog_color", colorLike(effects.get("water_fog_color"), rgb));
            biome.put("effects", effects);
            return;
        }
        CompoundTag attributes = biome.getCompoundOrEmpty("attributes");
        attributes.put(WATER_FOG, colorLike(attributes.get("minecraft:visual/sky_color"), rgb));
        biome.put("attributes", attributes);
    }

    /** A color in the NBT shape the codec used for {@code sample} (a "#rrggbb" string or an int). */
    private static Tag colorLike(@Nullable Tag sample, int rgb) {
        if (sample instanceof IntTag) return IntTag.valueOf(rgb);
        return StringTag.valueOf(String.format("#%06x", rgb & 0xFFFFFF));
    }

    private static void addHandler(Channel channel, Handler handler) {
        if (channel.pipeline().get("packet_handler") != null) {
            channel.pipeline().addBefore("packet_handler", HANDLER, handler);
        } else {
            channel.pipeline().addLast(HANDLER, handler);
        }
    }

    private static final class Handler extends ChannelOutboundHandlerAdapter {
        private final List<RegistrySynchronization.PackedRegistryEntry> extra;
        private final Map<Identifier, CompoundTag> replaced;
        private final IntConsumer onBaseCount;

        Handler(List<RegistrySynchronization.PackedRegistryEntry> extra, Map<Identifier, CompoundTag> replaced, IntConsumer onBaseCount) {
            this.extra = extra;
            this.replaced = replaced;
            this.onBaseCount = onBaseCount;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (msg instanceof ClientboundRegistryDataPacket packet && packet.registry().equals(Registries.BIOME)) {
                List<RegistrySynchronization.PackedRegistryEntry> all = new ArrayList<>(packet.entries().size() + this.extra.size());
                for (RegistrySynchronization.PackedRegistryEntry entry : packet.entries()) {
                    CompoundTag replacement = this.replaced.get(entry.id());
                    all.add(replacement == null ? entry : new RegistrySynchronization.PackedRegistryEntry(entry.id(), Optional.of(replacement.copy())));
                }
                this.onBaseCount.accept(all.size());
                all.addAll(this.extra);
                msg = new ClientboundRegistryDataPacket(packet.registry(), all);
            }
            super.write(ctx, msg, promise);
        }
    }
}
