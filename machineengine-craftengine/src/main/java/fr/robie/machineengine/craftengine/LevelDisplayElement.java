package fr.robie.machineengine.craftengine;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.entity.data.DisplayData;
import net.momirealms.craftengine.bukkit.util.EntityUtils;
import net.momirealms.craftengine.core.block.entity.render.element.BlockEntityElement;
import net.momirealms.craftengine.core.entity.display.ItemDisplayContext;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.item.ItemBuildContext;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundAddEntityPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundSetEntityDataPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.world.entity.EntityTypesProxy;
import net.momirealms.craftengine.proxy.minecraft.world.phys.Vec3Proxy;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A gauge drawn inside a machine block (e.g. an energy cube's core): an item display showing
 * {@code <prefix>/<level>}, level 1 to {@code steps} by how full a storage is, nothing when it is
 * empty. Client-side only, like CraftEngine's drawer.
 */
final class LevelDisplayElement implements BlockEntityElement {
    private final String prefix;
    private final int steps;
    private final BlockPos pos;
    private final int id = EntityUtils.ENTITY_COUNTER.incrementAndGet();
    private final UUID uuid = UUID.randomUUID();
    private final Object despawn;
    private volatile int level;
    private volatile @Nullable List<Object> packets;

    LevelDisplayElement(@NotNull String prefix, int steps, @NotNull BlockPos pos) {
        this.prefix = prefix;
        this.steps = Math.max(1, steps);
        this.pos = pos;
        IntArrayList ids = new IntArrayList();
        ids.add(this.id);
        this.despawn = ClientboundRemoveEntitiesPacketProxy.INSTANCE.newInstance(ids);
    }

    /** @return whether the level shown changed */
    boolean update(long amount, long capacity) {
        int level = amount <= 0 || capacity <= 0 ? 0
                : (int) Math.max(1, Math.min(this.steps, (long) Math.ceil(amount * (double) this.steps / capacity)));
        if (level == this.level) return false;
        this.level = level;
        this.packets = null;
        return true;
    }

    @Override
    public void show(@NotNull Player player) {
        List<Object> spawn = packets();
        if (spawn != null) player.sendPackets(spawn, false);
    }

    @Override
    public void hide(@NotNull Player player) {
        player.sendPacket(this.despawn, false);
    }

    void refresh(@NotNull Player player) {
        hide(player);
        show(player);
    }

    private @Nullable List<Object> packets() {
        if (this.level == 0) return null;
        List<Object> cached = this.packets;
        if (cached != null) return cached;
        var definition = CraftEngineItems.byId(net.momirealms.craftengine.core.util.Key.of(this.prefix + "/" + this.level));
        if (definition == null) return null;
        Object stack = definition.buildItem(ItemBuildContext.empty()).minecraftItem();
        Object add = ClientboundAddEntityPacketProxy.INSTANCE.newInstance(this.id, this.uuid,
                this.pos.x() + 0.5, this.pos.y() + 0.5, this.pos.z() + 0.5, 0f, 0f, EntityTypesProxy.ITEM_DISPLAY, 0, Vec3Proxy.ZERO, 0);
        Object data = ClientboundSetEntityDataPacketProxy.INSTANCE.newInstance(this.id, new ArrayList<>() {{
            DisplayData.ItemDisplayData.ItemStack.addEntityData(stack, this);
            DisplayData.ItemDisplayData.ItemTransform.addEntityData(ItemDisplayContext.NONE.id(), this);
        }});
        List<Object> built = List.of(add, data);
        this.packets = built;
        return built;
    }
}
