package fr.robie.machineengine.craftengine;

import fr.robie.machineengine.api.world.Direction;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.entity.data.DisplayData;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import net.momirealms.craftengine.bukkit.util.EntityUtils;
import net.momirealms.craftengine.core.block.entity.render.element.BlockEntityElement;
import net.momirealms.craftengine.core.entity.display.ItemDisplayContext;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.item.ItemBuildContext;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundAddEntityPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundSetEntityDataPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.world.entity.EntityTypesProxy;
import net.momirealms.craftengine.proxy.minecraft.world.phys.Vec3Proxy;
import org.jetbrains.annotations.NotNull;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The arms of a cable or pipe toward its connected sides: one item display entity per side, sent to
 * the players who see the block (client-side only, like CraftEngine's drawer), plus a center piece
 * {@code <prefix>/center}. Arms are the item {@code <prefix>/arm} turned to each side, or else one item
 * per side {@code <prefix>/<direction>} (e.g.
 * {@code mekanized:cable_part/basic_universal_cable/north}); {@code _pull} / {@code _push} variants
 * are used on sides set to pull or push, if those items exist.
 */
final class CableArmsElement implements BlockEntityElement {
    private static final Direction[] DIRECTIONS = Direction.values();

    private final String itemPrefix;
    private final BlockPos pos;
    private final int[] ids = new int[DIRECTIONS.length];
    private final Object[] spawn = new Object[DIRECTIONS.length];
    /** Entity data per direction and arm kind ("", "_pull", "_push"). */
    private final Map<String, Object> data = new ConcurrentHashMap<>();
    private volatile Map<Direction, String> kinds = Map.of();
    private final Object despawnAll;
    private final int centerId = EntityUtils.ENTITY_COUNTER.incrementAndGet();
    private final Object centerSpawn;
    private Object centerData;
    private volatile Set<Direction> connected = EnumSet.noneOf(Direction.class);

    CableArmsElement(@NotNull String itemPrefix, @NotNull BlockPos pos) {
        this.itemPrefix = itemPrefix;
        this.pos = pos;
        IntArrayList all = new IntArrayList();
        all.add(this.centerId);
        this.centerSpawn = ClientboundAddEntityPacketProxy.INSTANCE.newInstance(this.centerId, UUID.randomUUID(),
                pos.x() + 0.5, pos.y() + 0.5, pos.z() + 0.5, 0.0f, 0.0f, EntityTypesProxy.ITEM_DISPLAY, 0, Vec3Proxy.ZERO, 0);
        for (Direction direction : DIRECTIONS) {
            int id = EntityUtils.ENTITY_COUNTER.incrementAndGet();
            this.ids[direction.ordinal()] = id;
            all.add(id);
            this.spawn[direction.ordinal()] = ClientboundAddEntityPacketProxy.INSTANCE.newInstance(id, UUID.randomUUID(),
                    pos.x() + 0.5, pos.y() + 0.5, pos.z() + 0.5, 0.0f, 0.0f, EntityTypesProxy.ITEM_DISPLAY, 0, Vec3Proxy.ZERO, 0);
        }
        this.despawnAll = ClientboundRemoveEntitiesPacketProxy.INSTANCE.newInstance(all);
    }

    Set<Direction> connected() {
        return this.connected;
    }

    void setConnected(Set<Direction> connected) {
        this.connected = connected.isEmpty() ? EnumSet.noneOf(Direction.class) : EnumSet.copyOf(connected);
    }

    /** The arm kind of each side: "_pull", "_push", or absent for a plain arm. */
    Map<Direction, String> kinds() {
        return this.kinds;
    }

    void setKinds(Map<Direction, String> kinds) {
        this.kinds = Map.copyOf(kinds);
    }

    @Override
    public void show(@NotNull Player player) {
        List<Object> packets = new ArrayList<>();
        Object center = centerData();
        if (center != null) {
            packets.add(this.centerSpawn);
            packets.add(center);
        }
        for (Direction direction : this.connected) {
            Object item = data(direction);
            if (item == null) continue;
            packets.add(this.spawn[direction.ordinal()]);
            packets.add(item);
        }
        if (!packets.isEmpty()) player.sendPackets(packets, false);
    }

    @Override
    public void hide(@NotNull Player player) {
        player.sendPacket(this.despawnAll, false);
    }

    /** Redraws the arms for a player who already sees the block. */
    void refresh(@NotNull Player player) {
        hide(player);
        show(player);
    }

    /** The entity data showing the arm item; built once CraftEngine has the item (null before). */
    private Object data(Direction direction) {
        String kind = this.kinds.getOrDefault(direction, "");
        Object cached = this.data.get(direction.name() + kind);
        if (cached != null) return cached;
        // One arm model pointing north, turned to the side; else one model per side (older packs, addons).
        Quaternionf turn = null;
        var definition = item(this.itemPrefix + "/arm", kind);
        if (definition != null) {
            turn = turn(direction);
        } else {
            // Item displays draw their item turned 180 degrees around Y: the north arm model points south.
            Direction model = direction.isHorizontal() ? direction.opposite() : direction;
            definition = item(this.itemPrefix + "/" + model.name().toLowerCase(Locale.ROOT), kind);
        }
        if (definition == null) return null;
        Object stack = definition.buildItem(ItemBuildContext.empty()).minecraftItem();
        Quaternionf finalTurn = turn;
        Object packet = ClientboundSetEntityDataPacketProxy.INSTANCE.newInstance(this.ids[direction.ordinal()], new ArrayList<>() {{
            DisplayData.ItemDisplayData.ItemStack.addEntityData(stack, this);
            DisplayData.ItemDisplayData.ItemTransform.addEntityData(ItemDisplayContext.NONE.id(), this);
            if (finalTurn != null) DisplayData.LeftRotation.addEntityData(finalTurn, this);
        }});
        this.data.put(direction.name() + kind, packet);
        return packet;
    }

    /** The item {@code <base><kind>}, or {@code <base>} when there is no pull/push model. */
    private static BukkitItemDefinition item(String base, String kind) {
        var definition = CraftEngineItems.byId(Key.of(base + kind));
        return definition != null ? definition : CraftEngineItems.byId(Key.of(base));
    }

    /** The turn taking the arm model to each side, by {@link Direction#ordinal()}: see {@link #turn}. */
    private static final Quaternionf[] TURNS = new Quaternionf[DIRECTIONS.length];

    static {
        for (Direction direction : DIRECTIONS) {
            TURNS[direction.ordinal()] = switch (direction) {
                case SOUTH -> new Quaternionf();
                case NORTH -> new Quaternionf().rotateY((float) Math.PI);
                case EAST -> new Quaternionf().rotateY((float) (Math.PI / 2));
                case WEST -> new Quaternionf().rotateY((float) (-Math.PI / 2));
                case UP -> new Quaternionf().rotateX((float) (-Math.PI / 2));
                case DOWN -> new Quaternionf().rotateX((float) (Math.PI / 2));
            };
        }
    }

    /**
     * The turn taking the arm model to a side. The display draws the north arm pointing south (+Z),
     * so this turns +Z to the side. Shared: never change the returned quaternion.
     */
    static Quaternionf turn(Direction direction) {
        return TURNS[direction.ordinal()];
    }

    /** The center piece: the item {@code <prefix>/center}. */
    private Object centerData() {
        if (this.centerData != null) return this.centerData;
        var definition = CraftEngineItems.byId(Key.of(this.itemPrefix + "/center"));
        if (definition == null) return null;
        Object stack = definition.buildItem(ItemBuildContext.empty()).minecraftItem();
        this.centerData = ClientboundSetEntityDataPacketProxy.INSTANCE.newInstance(this.centerId, new ArrayList<>() {{
            DisplayData.ItemDisplayData.ItemStack.addEntityData(stack, this);
            DisplayData.ItemDisplayData.ItemTransform.addEntityData(ItemDisplayContext.NONE.id(), this);
        }});
        return this.centerData;
    }

    BlockPos pos() {
        return this.pos;
    }
}
