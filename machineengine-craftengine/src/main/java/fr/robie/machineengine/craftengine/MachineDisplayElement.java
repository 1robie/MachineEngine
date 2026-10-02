package fr.robie.machineengine.craftengine;

import fr.robie.machineengine.api.world.Direction;
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
 * Draws a machine whose model is not a full cube with one item display entity, so its block can be a
 * single empty, non-occluding state however many facings it has (such states are scarce). The entity
 * is turned to the machine's facing and shows the item {@code <displayItem>/active} while the machine
 * works, if that item exists. Client-side only, like CraftEngine's drawer.
 */
final class MachineDisplayElement implements BlockEntityElement {
    private final String displayItem;
    private final BlockPos pos;
    private final int id = EntityUtils.ENTITY_COUNTER.incrementAndGet();
    private final UUID uuid = UUID.randomUUID();
    private final Object despawn;
    private volatile Direction facing = Direction.NORTH;
    private volatile boolean active;
    private volatile @Nullable List<Object> packets;

    MachineDisplayElement(@NotNull String displayItem, @NotNull BlockPos pos) {
        this.displayItem = displayItem;
        this.pos = pos;
        IntArrayList ids = new IntArrayList();
        ids.add(this.id);
        this.despawn = ClientboundRemoveEntitiesPacketProxy.INSTANCE.newInstance(ids);
    }

    /** @return whether what is shown changed */
    boolean update(@NotNull Direction facing, boolean active) {
        if (facing == this.facing && active == this.active && this.packets != null) return false;
        this.facing = facing;
        this.active = active;
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
        List<Object> cached = this.packets;
        if (cached != null) return cached;
        Object stack = stack(this.active ? this.displayItem + "/active" : this.displayItem);
        if (stack == null && this.active) stack = stack(this.displayItem);
        if (stack == null) return null;
        // The model's north side faces the entity's facing: yaw south 0, west 90, north 180, east 270.
        float yaw = switch (this.facing) {
            case SOUTH -> 0f;
            case WEST -> 90f;
            case EAST -> 270f;
            default -> 180f;
        };
        float pitch = this.facing == Direction.UP ? -90f : this.facing == Direction.DOWN ? 90f : 0f;
        Object add = ClientboundAddEntityPacketProxy.INSTANCE.newInstance(this.id, this.uuid,
                this.pos.x() + 0.5, this.pos.y() + 0.5, this.pos.z() + 0.5, pitch, yaw, EntityTypesProxy.ITEM_DISPLAY, 0, Vec3Proxy.ZERO, yaw);
        Object finalStack = stack;
        Object data = ClientboundSetEntityDataPacketProxy.INSTANCE.newInstance(this.id, new ArrayList<>() {{
            DisplayData.ItemDisplayData.ItemStack.addEntityData(finalStack, this);
            DisplayData.ItemDisplayData.ItemTransform.addEntityData(ItemDisplayContext.NONE.id(), this);
        }});
        List<Object> built = List.of(add, data);
        this.packets = built;
        return built;
    }

    private static @Nullable Object stack(String itemId) {
        var definition = CraftEngineItems.byId(net.momirealms.craftengine.core.util.Key.of(itemId));
        return definition == null ? null : definition.buildItem(ItemBuildContext.empty()).minecraftItem();
    }
}
