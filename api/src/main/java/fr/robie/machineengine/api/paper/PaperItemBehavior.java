package fr.robie.machineengine.api.paper;

import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.item.behavior.ItemBehavior;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.World;
import net.momirealms.craftengine.core.world.context.InteractEntityContext;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A CraftEngine item behavior written with Bukkit types (D32): right-clicks with the item in the main
 * hand arrive as one {@link #use(Use)}, whether on a block or in the air, and {@link #useOnEntity} on an
 * entity. Return {@code true} when the click was handled: it is then cancelled, as a listener would.
 *
 * <p>CraftEngine calls these on the player's thread. A click on a vanilla block that opens something
 * (a chest, a furnace) reaches the item only while the player sneaks; a click on a machine reaches it
 * only when the behavior is also a {@link fr.robie.machineengine.api.item.MachineTool}.</p>
 */
public abstract class PaperItemBehavior extends ItemBehavior {

    /**
     * A right-click with the item.
     *
     * @param player   who clicked
     * @param item     the item in the main hand (the live stack: changing it changes the player's item)
     * @param block    the block clicked, {@code null} in the air
     * @param face     the face clicked, {@code null} in the air
     * @param sneaking whether the player sneaks
     */
    public record Use(@NotNull Player player, @NotNull ItemStack item, @Nullable Block block, @Nullable BlockFace face,
                      boolean sneaking) {
        public boolean onBlock() {
            return this.block != null;
        }
    }

    /** @return whether the click was handled (it is cancelled) */
    protected boolean use(@NotNull Use use) {
        return false;
    }

    /** @return whether the click was handled (it is cancelled) */
    protected boolean useOnEntity(@NotNull Player player, @NotNull ItemStack item, @NotNull Entity target) {
        return false;
    }

    @Override
    public final InteractionResult useOnBlock(UseOnContext context) {
        if (context.getPlayer() == null || context.getHand() != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        Player player = (Player) context.getPlayer().platformPlayer();
        BlockPos pos = context.getClickedPos();
        Block block = ((org.bukkit.World) context.getLevel().platformWorld()).getBlockAt(pos.x(), pos.y(), pos.z());
        BlockFace face = BlockFace.valueOf(context.getClickedFace().name());
        return result(use(new Use(player, player.getInventory().getItemInMainHand(), block, face, context.isSecondaryUseActive())));
    }

    @Override
    public final InteractionResult use(World world, net.momirealms.craftengine.core.entity.player.@Nullable Player player,
                                       InteractionHand hand) {
        if (player == null || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        Player bukkit = (Player) player.platformPlayer();
        return result(use(new Use(bukkit, bukkit.getInventory().getItemInMainHand(), null, null, player.isSecondaryUseActive())));
    }

    @Override
    public final InteractionResult useOnEntity(InteractEntityContext context) {
        if (context.getPlayer() == null || context.getTarget() == null || context.getHand() != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        Player player = (Player) context.getPlayer().platformPlayer();
        return result(useOnEntity(player, player.getInventory().getItemInMainHand(), (Entity) context.getTarget().platformEntity()));
    }

    private static InteractionResult result(boolean handled) {
        return handled ? InteractionResult.SUCCESS_AND_CANCEL : InteractionResult.PASS;
    }
}
