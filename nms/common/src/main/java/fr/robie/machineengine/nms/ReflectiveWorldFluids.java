package fr.robie.machineengine.nms;

import fr.robie.machineengine.api.platform.WorldFluidNms;
import fr.robie.machineengine.api.resource.fluid.Viscosity;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.LongToIntFunction;
import java.util.stream.IntStream;

/**
 * World fluids for versions without a module (1.21.x) through reflection on the server's Mojang names
 * (Paper runs Mojang-mapped since 1.20.5). Every handle is looked up when it is made, so a version it
 * doesn't fit fails at once ({@link #create}) instead of on a player's screen.
 */
public final class ReflectiveWorldFluids implements WorldFluidNms {
    private static final Key LISTENER = Key.key("machineengine", "world_fluids");
    private static final String HANDLER = "machineengine_world_fluids";
    private static final String WATER_FOG = "minecraft:visual/water_fog_color";
    private static final String WATER_COLOR = "minecraft:visual/water_color";
    private static final NamespacedKey MODIFIER = new NamespacedKey("machineengine", "fluid_viscosity");

    private final boolean lengthPrefix;
    // Registries and NBT.
    private final Object biomeRegistryKey;
    private final Method registryAccess;
    private final Method lookupRegistry;
    private final Method registryValue;
    private final Method registryEntrySet;
    private final Method registryGetId;
    private final Method resourceKeyLocation;
    private final Method parseLocation;
    private final Method createOps;
    private final Object nbtOps;
    private final Object networkCodec;
    private final Method encodeStart;
    private final Method getOrThrow;
    private final Method tagCopy;
    private final Method tagGet;
    private final Method tagPut;
    private final Method tagContains;
    private final Method getCompound;
    private final Constructor<?> newCompound;
    private final Class<?> intTag;
    private final Method intTagOf;
    private final Method stringTagOf;
    private final Constructor<?> newPackedEntry;
    private final Method packedId;
    private final Class<?> registryDataPacket;
    private final Constructor<?> newRegistryDataPacket;
    private final Method packetRegistry;
    private final Method packetEntries;
    private final Method addListener;
    private final Method hasListener;
    private final Method removeListener;
    private final Class<?> initializeListener;
    // Chunks and packets.
    private final Method worldHandle;
    private final Method chunkSource;
    private final Method chunkNow;
    private final Method sections;
    private final Method minSection;
    private final Method sectionBiomes;
    private final Method biomeAt;
    private final Method holderValue;
    private final Constructor<?> newChunkPos;
    private final Constructor<?> newBiomeData;
    private final Constructor<?> newBiomesPacket;
    private final Method playerHandle;
    private final Field connection;
    private final Method send;
    // The biome reader (startup check).
    private final Class<?> idMap;
    private final @Nullable Method createStrategy;
    private final @Nullable Object sectionBiomesStrategy;
    private final Constructor<?> newContainer;
    private final Method containerRead;
    private final Method containerGet;
    private final Constructor<?> newFriendlyByteBuf;
    // Attributes (1.21+: named modifiers).
    private final @Nullable Attribute speed;
    private final @Nullable Attribute gravity;
    private final @Nullable Attribute jump;
    private final @Nullable Attribute swim;
    /** Named modifiers ({@code null} before 1.21: then viscosity is damping only). */
    private final @Nullable Constructor<AttributeModifier> newModifier;
    private final @Nullable Object addition;
    private final @Nullable Object multiplication;
    private final @Nullable Object anySlot;
    private final @Nullable Method removeModifier;

    private ReflectiveWorldFluids(String version) throws ReflectiveOperationException {
        this.lengthPrefix = older(version, 1, 21, 5);
        ClassLoader loader = Bukkit.getServer().getClass().getClassLoader();
        Class<?> server = Class.forName("net.minecraft.server.MinecraftServer", true, loader);
        Class<?> registries = Class.forName("net.minecraft.core.registries.Registries", true, loader);
        this.biomeRegistryKey = registries.getField("BIOME").get(null);
        Object access = server.getMethod("registryAccess").invoke(server.getMethod("getServer").invoke(null));
        this.registryAccess = server.getMethod("registryAccess");
        Class<?> resourceKey = Class.forName("net.minecraft.resources.ResourceKey", true, loader);
        // registryOrThrow first: before 1.21.2, lookupOrThrow exists too but returns a RegistryLookup, not a Registry.
        this.lookupRegistry = first(access.getClass(), new String[]{"registryOrThrow", "lookupOrThrow"}, resourceKey);
        if (!registry(loader).isAssignableFrom(this.lookupRegistry.getReturnType())) {
            throw new NoSuchMethodException(this.lookupRegistry + " does not return a Registry");
        }
        Class<?> location = classOf(loader, "net.minecraft.resources.Identifier", "net.minecraft.resources.ResourceLocation");
        this.parseLocation = location.getMethod("tryParse", String.class);
        Class<?> registry = Class.forName("net.minecraft.core.Registry", true, loader);
        Method value;
        try {
            value = registry.getMethod("getValue", location);
        } catch (NoSuchMethodException e) {
            value = registry.getMethod("get", location);
        }
        this.registryValue = value;
        this.registryEntrySet = registry.getMethod("entrySet");
        this.registryGetId = first(registry, new String[]{"getId"}, Object.class);
        this.resourceKeyLocation = first(resourceKey, new String[]{"identifier", "location"});
        Class<?> dynamicOps = Class.forName("com.mojang.serialization.DynamicOps", true, loader);
        this.createOps = first(access.getClass(), new String[]{"createSerializationContext"}, dynamicOps);
        this.nbtOps = Class.forName("net.minecraft.nbt.NbtOps", true, loader).getField("INSTANCE").get(null);
        this.networkCodec = Class.forName("net.minecraft.world.level.biome.Biome", true, loader).getField("NETWORK_CODEC").get(null);
        Class<?> encoder = Class.forName("com.mojang.serialization.Encoder", true, loader);
        this.encodeStart = encoder.getMethod("encodeStart", dynamicOps, Object.class);
        this.getOrThrow = Class.forName("com.mojang.serialization.DataResult", true, loader).getMethod("getOrThrow");
        Class<?> tag = Class.forName("net.minecraft.nbt.Tag", true, loader);
        Class<?> compound = Class.forName("net.minecraft.nbt.CompoundTag", true, loader);
        this.tagCopy = compound.getMethod("copy");
        this.tagGet = compound.getMethod("get", String.class);
        this.tagPut = compound.getMethod("put", String.class, tag);
        this.tagContains = compound.getMethod("contains", String.class);
        this.getCompound = first(compound, new String[]{"getCompoundOrEmpty", "getCompound"}, String.class);
        this.newCompound = compound.getConstructor();
        this.intTag = Class.forName("net.minecraft.nbt.IntTag", true, loader);
        this.intTagOf = this.intTag.getMethod("valueOf", int.class);
        this.stringTagOf = Class.forName("net.minecraft.nbt.StringTag", true, loader).getMethod("valueOf", String.class);
        Class<?> packed = Class.forName("net.minecraft.core.RegistrySynchronization$PackedRegistryEntry", true, loader);
        this.newPackedEntry = packed.getConstructor(location, Optional.class);
        this.packedId = packed.getMethod("id");
        this.registryDataPacket = Class.forName("net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket", true, loader);
        this.newRegistryDataPacket = this.registryDataPacket.getConstructor(resourceKey, List.class);
        this.packetRegistry = this.registryDataPacket.getMethod("registry");
        this.packetEntries = this.registryDataPacket.getMethod("entries");
        Class<?> holder = Class.forName("io.papermc.paper.network.ChannelInitializeListenerHolder", true, loader);
        this.initializeListener = Class.forName("io.papermc.paper.network.ChannelInitializeListener", true, loader);
        this.addListener = holder.getMethod("addListener", Key.class, this.initializeListener);
        this.hasListener = holder.getMethod("hasListener", Key.class);
        this.removeListener = holder.getMethod("removeListener", Key.class);

        World anyWorld = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().getFirst();
        Class<?> craftWorld = anyWorld != null ? anyWorld.getClass() : Class.forName(Bukkit.getServer().getClass().getPackageName() + ".CraftWorld", true, loader);
        this.worldHandle = craftWorld.getMethod("getHandle");
        Class<?> level = this.worldHandle.getReturnType();
        this.chunkSource = level.getMethod("getChunkSource");
        this.chunkNow = this.chunkSource.getReturnType().getMethod("getChunkNow", int.class, int.class);
        this.sections = Class.forName("net.minecraft.world.level.chunk.ChunkAccess", true, loader).getMethod("getSections");
        this.minSection = first(level, new String[]{"getMinSectionY", "getMinSection"});
        Class<?> section = Class.forName("net.minecraft.world.level.chunk.LevelChunkSection", true, loader);
        this.sectionBiomes = section.getMethod("getBiomes");
        this.biomeAt = this.sectionBiomes.getReturnType().getMethod("get", int.class, int.class, int.class);
        this.holderValue = Class.forName("net.minecraft.core.Holder", true, loader).getMethod("value");
        Class<?> chunkPos = Class.forName("net.minecraft.world.level.ChunkPos", true, loader);
        this.newChunkPos = chunkPos.getConstructor(int.class, int.class);
        Class<?> biomesPacket = Class.forName("net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket", true, loader);
        this.newBiomesPacket = biomesPacket.getConstructor(List.class);
        this.newBiomeData = Class.forName("net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket$ChunkBiomeData", true, loader)
                .getConstructor(chunkPos, byte[].class);
        Class<?> craftPlayer = Class.forName(Bukkit.getServer().getClass().getPackageName() + ".entity.CraftPlayer", true, loader);
        this.playerHandle = craftPlayer.getMethod("getHandle");
        this.connection = this.playerHandle.getReturnType().getField("connection");
        Class<?> packet = Class.forName("net.minecraft.network.protocol.Packet", true, loader);
        this.send = this.connection.getType().getMethod("send", packet);

        this.idMap = Class.forName("net.minecraft.core.IdMap", true, loader);
        Class<?> container = Class.forName("net.minecraft.world.level.chunk.PalettedContainer", true, loader);
        Method create = null;
        Object biomesStrategy = null;
        Constructor<?> containerConstructor;
        try {
            Class<?> strategy = Class.forName("net.minecraft.world.level.chunk.Strategy", true, loader);
            create = strategy.getMethod("createForBiomes", this.idMap);
            containerConstructor = container.getConstructor(Object.class, strategy);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            Class<?> strategy = Class.forName("net.minecraft.world.level.chunk.PalettedContainer$Strategy", true, loader);
            biomesStrategy = strategy.getField("SECTION_BIOMES").get(null);
            containerConstructor = container.getConstructor(this.idMap, Object.class, strategy);
        }
        this.createStrategy = create;
        this.sectionBiomesStrategy = biomesStrategy;
        this.newContainer = containerConstructor;
        Class<?> friendly = Class.forName("net.minecraft.network.FriendlyByteBuf", true, loader);
        this.newFriendlyByteBuf = friendly.getConstructor(io.netty.buffer.ByteBuf.class);
        this.containerRead = container.getMethod("read", friendly);
        this.containerGet = container.getMethod("get", int.class, int.class, int.class);

        this.speed = attribute("movement_speed");
        this.gravity = attribute("gravity");
        this.jump = attribute("jump_strength");
        this.swim = attribute("water_movement_efficiency");
        Class<?> operation = AttributeModifier.Operation.class;
        Constructor<AttributeModifier> modifier = null;
        Object add = null, multiply = null, any = null;
        Method remove = null;
        try {
            Class<?> slotGroup = Class.forName("org.bukkit.inventory.EquipmentSlotGroup", true, loader);
            modifier = AttributeModifier.class.getConstructor(NamespacedKey.class, double.class, operation, slotGroup);
            add = operation.getField("ADD_NUMBER").get(null);
            multiply = operation.getField("MULTIPLY_SCALAR_1").get(null);
            any = slotGroup.getField("ANY").get(null);
            remove = AttributeInstance.class.getMethod("removeModifier", NamespacedKey.class);
        } catch (ReflectiveOperationException e) {
            modifier = null;
        }
        this.newModifier = modifier;
        this.addition = add;
        this.multiplication = multiply;
        this.anySlot = any;
        this.removeModifier = remove;
    }

    /** @return world fluids for this server, or {@code null} when the reflection does not fit it */
    public static @Nullable WorldFluidNms create(@NotNull Consumer<String> log) {
        try {
            return new ReflectiveWorldFluids(Bukkit.getMinecraftVersion());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            log.accept("world fluids need server classes this version does not have: " + e);
            return null;
        }
    }

    @Override
    public void install(@NotNull Key baseBiome, @NotNull List<KeyBiome> keyBiomes, @Nullable Integer uniformWater,
                        @NotNull IntConsumer onBaseCount, @NotNull Consumer<String> log) {
        try {
            Object access = this.registryAccess.invoke(serverInstance());
            Object biomes = this.lookupRegistry.invoke(access, this.biomeRegistryKey);
            Object ops = this.createOps.invoke(access, this.nbtOps);
            Object base = this.registryValue.invoke(biomes, location(baseBiome.asString()));
            if (base instanceof Optional<?> optional) base = optional.map(this::unholder).orElse(null);
            if (base == null) throw new IllegalArgumentException("unknown biome " + baseBiome.asString());
            Object encoded = encode(ops, base);
            if (uniformWater != null) setWater(encoded, uniformWater);
            List<Object> extra = new ArrayList<>();
            for (KeyBiome key : keyBiomes) {
                Object copy = this.tagCopy.invoke(encoded);
                setWater(copy, key.waterColor());
                setFog(copy, key.fogColor());
                extra.add(this.newPackedEntry.newInstance(location(key.id().asString()), Optional.of(copy)));
            }
            Map<Object, Object> replaced = new HashMap<>();
            if (uniformWater != null) {
                for (Object entry : (Iterable<?>) this.registryEntrySet.invoke(biomes)) {
                    Map.Entry<?, ?> pair = (Map.Entry<?, ?>) entry;
                    Object biome = encode(ops, pair.getValue());
                    setWater(biome, uniformWater);
                    replaced.put(this.resourceKeyLocation.invoke(pair.getKey()), biome);
                }
            }
            Handler handler = new Handler(this, extra, replaced, onBaseCount);
            Object listener = Proxy.newProxyInstance(this.initializeListener.getClassLoader(), new Class<?>[]{this.initializeListener},
                    (proxy, method, args) -> {
                        if (method.getName().equals("afterInitChannel")) {
                            addHandler((Channel) args[0], handler);
                            return null;
                        }
                        return objectMethod(proxy, method, args);
                    });
            this.addListener.invoke(null, LISTENER, listener);
            log.accept(extra.size() + " key biomes" + (uniformWater == null ? "" : ", " + replaced.size() + " biomes with uniform water") + " (reflection)");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not install world fluids", e);
        }
    }

    @Override
    public void uninstall() {
        try {
            if ((boolean) this.hasListener.invoke(null, LISTENER)) this.removeListener.invoke(null, LISTENER);
        } catch (ReflectiveOperationException ignored) {
            // the server is stopping
        }
    }

    @Override
    public boolean sendBiomes(@NotNull Collection<? extends Player> viewers, @NotNull World world, int chunkX, int chunkZ,
                              @NotNull LongToIntFunction cellIds, int clientSize) {
        try {
            Object level = this.worldHandle.invoke(world);
            Object chunk = this.chunkNow.invoke(this.chunkSource.invoke(level), chunkX, chunkZ);
            if (chunk == null) return false;
            Object registry = this.lookupRegistry.invoke(this.registryAccess.invoke(serverInstance()), this.biomeRegistryKey);
            Object[] all = (Object[]) this.sections.invoke(chunk);
            int min = (int) this.minSection.invoke(level);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int[] ids = new int[64];
            for (int s = 0; s < all.length; s++) {
                Object biomes = this.sectionBiomes.invoke(all[s]);
                int sectionY = min + s;
                for (int y = 0; y < 4; y++) for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++) {
                    int id = cellIds.applyAsInt(pack(chunkX * 4 + x, sectionY * 4 + y, chunkZ * 4 + z));
                    ids[(y << 4) | (z << 2) | x] = id >= 0 ? id
                            : (int) this.registryGetId.invoke(registry, this.holderValue.invoke(this.biomeAt.invoke(biomes, x, y, z)));
                }
                BiomeSections.write(out, ids, clientSize, this.lengthPrefix);
            }
            Object data = this.newBiomeData.newInstance(this.newChunkPos.newInstance(chunkX, chunkZ), out.toByteArray());
            Object packet = this.newBiomesPacket.newInstance(List.of(data));
            for (Player viewer : viewers) this.send.invoke(this.connection.get(this.playerHandle.invoke(viewer)), packet);
            return true;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not send biomes", e);
        }
    }

    @Override
    public @NotNull String checkBiomeWriter(int clientSize) {
        Object ids = Proxy.newProxyInstance(this.idMap.getClassLoader(), new Class<?>[]{this.idMap}, (proxy, method, args) -> switch (method.getName()) {
            case "getId", "getIdOrThrow" -> args[0];
            case "byId", "byIdOrThrow" -> (int) args[0] >= 0 && (int) args[0] < clientSize ? args[0] : null;
            case "size" -> clientSize;
            case "iterator" -> IntStream.range(0, clientSize).boxed().iterator();
            default -> method.isDefault() ? InvocationHandler.invokeDefault(proxy, method, args) : objectMethod(proxy, method, args);
        });
        Random random = new Random(3);
        int good = 0, total = 0;
        for (int distinct : new int[]{1, 2, 3, 4, 5, 8, 9, 17, 33, 64}) {
            for (int round = 0; round < 20; round++) {
                int[] pool = random.ints(0, clientSize).distinct().limit(Math.min(distinct, clientSize)).toArray();
                int[] section = new int[64];
                for (int i = 0; i < 64; i++) section[i] = pool[i < pool.length ? i : random.nextInt(pool.length)];
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                BiomeSections.write(out, section, clientSize, this.lengthPrefix);
                total++;
                try {
                    io.netty.buffer.ByteBuf raw = Unpooled.wrappedBuffer(out.toByteArray());
                    Object buf = this.newFriendlyByteBuf.newInstance(raw);
                    Object container = this.createStrategy != null
                            ? this.newContainer.newInstance(0, this.createStrategy.invoke(null, ids))
                            : this.newContainer.newInstance(ids, 0, this.sectionBiomesStrategy);
                    this.containerRead.invoke(container, buf);
                    boolean same = raw.readableBytes() == 0;
                    for (int i = 0; i < 64 && same; i++) same = (int) this.containerGet.invoke(container, i & 3, i >> 4, (i >> 2) & 3) == section[i];
                    if (same) good++;
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // counted as a failure
                }
            }
        }
        return good + "/" + total;
    }

    @Override
    public void viscosity(@NotNull Player player, @Nullable Viscosity viscosity) {
        if (this.newModifier == null || this.removeModifier == null) return;
        try {
            for (Attribute attribute : new Attribute[]{this.speed, this.gravity, this.jump, this.swim}) {
                AttributeInstance instance = attribute == null ? null : player.getAttribute(attribute);
                if (instance != null) this.removeModifier.invoke(instance, MODIFIER);
            }
            if (viscosity == null) return;
            add(player, this.speed, viscosity.speed() - 1, this.multiplication, viscosity.speed() != 1);
            add(player, this.gravity, viscosity.gravity() - 1, this.multiplication, viscosity.gravity() != 1);
            add(player, this.jump, viscosity.jump() - 1, this.multiplication, viscosity.jump() != 1);
            add(player, this.swim, viscosity.swim(), this.addition, viscosity.swim() != 0);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not change attributes", e);
        }
    }

    private void add(Player player, @Nullable Attribute attribute, double amount, Object operation, boolean needed) throws ReflectiveOperationException {
        if (!needed || attribute == null) return;
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance != null && this.newModifier != null) {
            instance.addTransientModifier(this.newModifier.newInstance(MODIFIER, amount, operation, this.anySlot));
        }
    }

    /** An attribute by its 1.21.2+ name, or the older {@code generic.} one. */
    private static @Nullable Attribute attribute(String name) {
        Attribute found = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(name));
        return found != null ? found : Registry.ATTRIBUTE.get(NamespacedKey.minecraft("generic." + name));
    }

    // ---------------------------------------------------------------- NBT

    private Object encode(Object ops, Object biome) throws ReflectiveOperationException {
        return this.getOrThrow.invoke(this.encodeStart.invoke(this.networkCodec, ops, biome));
    }

    private Object compound(Object parent, String key) throws ReflectiveOperationException {
        Object value = this.getCompound.invoke(parent, key);
        if (value instanceof Optional<?> optional) value = optional.orElse(null);
        return value != null ? value : this.newCompound.newInstance();
    }

    private void setWater(Object biome, int rgb) throws ReflectiveOperationException {
        Object effects = compound(biome, "effects");
        this.tagPut.invoke(effects, "water_color", colorLike(this.tagGet.invoke(effects, "water_color"), rgb));
        this.tagPut.invoke(biome, "effects", effects);
        Object attributes = compound(biome, "attributes");
        if ((boolean) this.tagContains.invoke(attributes, WATER_COLOR)) {
            this.tagPut.invoke(attributes, WATER_COLOR, colorLike(this.tagGet.invoke(attributes, WATER_COLOR), rgb));
            this.tagPut.invoke(biome, "attributes", attributes);
        }
    }

    private void setFog(Object biome, int rgb) throws ReflectiveOperationException {
        Object effects = compound(biome, "effects");
        if ((boolean) this.tagContains.invoke(effects, "water_fog_color")) {
            this.tagPut.invoke(effects, "water_fog_color", colorLike(this.tagGet.invoke(effects, "water_fog_color"), rgb));
            this.tagPut.invoke(biome, "effects", effects);
            return;
        }
        Object attributes = compound(biome, "attributes");
        this.tagPut.invoke(attributes, WATER_FOG, colorLike(this.tagGet.invoke(attributes, "minecraft:visual/sky_color"), rgb));
        this.tagPut.invoke(biome, "attributes", attributes);
    }

    /** A color in the NBT shape of {@code sample}: an int tag, or a "#rrggbb" string. */
    private Object colorLike(@Nullable Object sample, int rgb) throws ReflectiveOperationException {
        if (sample == null || this.intTag.isInstance(sample)) return this.intTagOf.invoke(null, rgb);
        return this.stringTagOf.invoke(null, String.format("#%06x", rgb & 0xFFFFFF));
    }

    // ---------------------------------------------------------------- helpers

    private Object serverInstance() throws ReflectiveOperationException {
        return this.registryAccess.getDeclaringClass().getMethod("getServer").invoke(null);
    }

    private Object location(String key) throws ReflectiveOperationException {
        return this.parseLocation.invoke(null, key);
    }

    private Object unholder(Object holder) {
        try {
            return this.holderValue.invoke(holder);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private static Class<?> registry(ClassLoader loader) throws ClassNotFoundException {
        return Class.forName("net.minecraft.core.Registry", true, loader);
    }

    private static Method first(Class<?> type, String[] names, Class<?>... parameters) throws NoSuchMethodException {
        for (String name : names) {
            try {
                return type.getMethod(name, parameters);
            } catch (NoSuchMethodException ignored) {
                // try the next name
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + String.join("|", names));
    }

    private static Class<?> classOf(ClassLoader loader, String... names) throws ClassNotFoundException {
        for (String name : names) {
            try {
                return Class.forName(name, true, loader);
            } catch (ClassNotFoundException ignored) {
                // try the next name
            }
        }
        throw new ClassNotFoundException(String.join("|", names));
    }

    /** Whether {@code version} ("1.21.4") is older than major.minor.patch. */
    static boolean older(String version, int major, int minor, int patch) {
        String[] parts = version.split("\\.");
        int[] numbers = new int[3];
        for (int i = 0; i < 3 && i < parts.length; i++) {
            try {
                numbers[i] = Integer.parseInt(parts[i].replaceAll("[^0-9].*", ""));
            } catch (NumberFormatException e) {
                numbers[i] = 0;
            }
        }
        if (numbers[0] != major) return numbers[0] < major;
        if (numbers[1] != minor) return numbers[1] < minor;
        return numbers[2] < patch;
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "machineengine world fluids";
            default -> throw new UnsupportedOperationException(method.toString());
        };
    }

    private static void addHandler(Channel channel, Handler handler) {
        if (channel.pipeline().get("packet_handler") != null) {
            channel.pipeline().addBefore("packet_handler", HANDLER, handler);
        } else {
            channel.pipeline().addLast(HANDLER, handler);
        }
    }

    private static final class Handler extends ChannelOutboundHandlerAdapter {
        private final ReflectiveWorldFluids nms;
        private final List<Object> extra;
        private final Map<Object, Object> replaced;
        private final IntConsumer onBaseCount;

        Handler(ReflectiveWorldFluids nms, List<Object> extra, Map<Object, Object> replaced, IntConsumer onBaseCount) {
            this.nms = nms;
            this.extra = extra;
            this.replaced = replaced;
            this.onBaseCount = onBaseCount;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (this.nms.registryDataPacket.isInstance(msg) && this.nms.packetRegistry.invoke(msg).equals(this.nms.biomeRegistryKey)) {
                try {
                    List<?> entries = (List<?>) this.nms.packetEntries.invoke(msg);
                    List<Object> all = new ArrayList<>(entries.size() + this.extra.size());
                    for (Object entry : entries) {
                        Object id = this.nms.packedId.invoke(entry);
                        Object replacement = this.replaced.get(id);
                        all.add(replacement == null ? entry : this.nms.newPackedEntry.newInstance(id, Optional.of(this.nms.tagCopy.invoke(replacement))));
                    }
                    this.onBaseCount.accept(all.size());
                    for (Object entry : this.extra) all.add(entry);
                    msg = this.nms.newRegistryDataPacket.newInstance(this.nms.packetRegistry.invoke(msg), all);
                } catch (InvocationTargetException e) {
                    throw new IllegalStateException("could not add the key biomes", e.getCause());
                }
            }
            super.write(ctx, msg, promise);
        }
    }
}
