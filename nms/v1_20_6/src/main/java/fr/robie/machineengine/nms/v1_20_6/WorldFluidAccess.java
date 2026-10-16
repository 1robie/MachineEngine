package fr.robie.machineengine.nms.v1_20_6;

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
import net.minecraft.core.BlockPos;
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
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
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
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.LongToIntFunction;
import java.util.stream.IntStream;

/**
 * World fluids on 1.20.6: key biomes appended to the registry data by a Netty handler, biome-only chunk
 * updates (long arrays carry their length), attribute modifiers without water movement efficiency.
 */
public final class WorldFluidAccess implements WorldFluidNms {
    private static final Key LISTENER = Key.key("machineengine", "world_fluids");
    private static final String HANDLER = "machineengine_world_fluids";
    private static final String MODIFIER = "machineengine:fluid_viscosity";
    private static final UUID MODIFIER_ID = UUID.nameUUIDFromBytes(MODIFIER.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private static final List<Attribute> ATTRIBUTES = List.of(Attribute.GENERIC_MOVEMENT_SPEED, Attribute.GENERIC_GRAVITY,
            Attribute.GENERIC_JUMP_STRENGTH);

    @Override
    public void install(@NotNull Key baseBiome, @NotNull List<KeyBiome> keyBiomes, @Nullable Integer uniformWater,
                        @NotNull IntConsumer onBaseCount, @NotNull Consumer<String> log) {
        RegistryAccess access = MinecraftServer.getServer().registryAccess();
        Registry<Biome> biomes = access.registryOrThrow(Registries.BIOME);
        RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, access);
        Biome base = biomes.get(ResourceLocation.tryParse(baseBiome.asString()));
        if (base == null) throw new IllegalArgumentException("unknown biome " + baseBiome.asString());
        CompoundTag encoded = (CompoundTag) Biome.NETWORK_CODEC.encodeStart(ops, base).getOrThrow();
        if (uniformWater != null) setColor(encoded, "water_color", uniformWater);
        List<RegistrySynchronization.PackedRegistryEntry> extra = new ArrayList<>();
        for (KeyBiome key : keyBiomes) {
            CompoundTag copy = encoded.copy();
            setColor(copy, "water_color", key.waterColor());
            setColor(copy, "water_fog_color", key.fogColor());
            extra.add(new RegistrySynchronization.PackedRegistryEntry(ResourceLocation.tryParse(key.id().asString()), Optional.of(copy)));
        }
        Map<ResourceLocation, CompoundTag> replaced = new HashMap<>();
        if (uniformWater != null) {
            for (var entry : biomes.entrySet()) {
                CompoundTag biome = (CompoundTag) Biome.NETWORK_CODEC.encodeStart(ops, entry.getValue()).getOrThrow();
                setColor(biome, "water_color", uniformWater);
                replaced.put(entry.getKey().location(), biome);
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
        Registry<Biome> registry = level.registryAccess().registryOrThrow(Registries.BIOME);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LevelChunkSection[] sections = chunk.getSections();
        int minSection = level.getMinSection();
        int[] ids = new int[64];
        for (int s = 0; s < sections.length; s++) {
            var biomes = sections[s].getBiomes();
            int sectionY = minSection + s;
            for (int y = 0; y < 4; y++) for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++) {
                int id = cellIds.applyAsInt(BlockPos.asLong(chunkX * 4 + x, sectionY * 4 + y, chunkZ * 4 + z));
                ids[(y << 4) | (z << 2) | x] = id >= 0 ? id : registry.getId(biomes.get(x, y, z).value());
            }
            BiomeSections.write(out, ids, clientSize, true);
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
        Random random = new Random(3);
        int good = 0, total = 0;
        for (int distinct : new int[]{1, 2, 3, 4, 5, 8, 9, 17, 33, 64}) {
            for (int round = 0; round < 20; round++) {
                int[] pool = random.ints(0, clientSize).distinct().limit(Math.min(distinct, clientSize)).toArray();
                int[] section = new int[64];
                for (int i = 0; i < 64; i++) section[i] = pool[i < pool.length ? i : random.nextInt(pool.length)];
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                BiomeSections.write(out, section, clientSize, true);
                FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(out.toByteArray()));
                total++;
                try {
                    PalettedContainer<Integer> container = new PalettedContainer<>(ids, 0, PalettedContainer.Strategy.SECTION_BIOMES);
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
            if (instance == null) continue;
            for (AttributeModifier modifier : List.copyOf(instance.getModifiers())) {
                if (modifier.getUniqueId().equals(MODIFIER_ID)) instance.removeModifier(modifier);
            }
        }
        if (viscosity == null) return;
        // No water movement efficiency before 1.21: swimming speed is left to damping.
        multiply(player, Attribute.GENERIC_MOVEMENT_SPEED, viscosity.speed());
        multiply(player, Attribute.GENERIC_GRAVITY, viscosity.gravity());
        multiply(player, Attribute.GENERIC_JUMP_STRENGTH, viscosity.jump());
    }

    private static void multiply(Player player, Attribute attribute, double factor) {
        if (factor == 1) return;
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance != null) instance.addTransientModifier(new AttributeModifier(MODIFIER_ID, MODIFIER, factor - 1,
                AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
    }

    private static void setColor(CompoundTag biome, String field, int rgb) {
        CompoundTag effects = biome.getCompound("effects");
        effects.put(field, colorLike(effects.get(field), rgb));
        biome.put("effects", effects);
    }

    private static Tag colorLike(@Nullable Tag sample, int rgb) {
        if (sample instanceof StringTag) return StringTag.valueOf(String.format("#%06x", rgb & 0xFFFFFF));
        return IntTag.valueOf(rgb);
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
        private final Map<ResourceLocation, CompoundTag> replaced;
        private final IntConsumer onBaseCount;

        Handler(List<RegistrySynchronization.PackedRegistryEntry> extra, Map<ResourceLocation, CompoundTag> replaced, IntConsumer onBaseCount) {
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
