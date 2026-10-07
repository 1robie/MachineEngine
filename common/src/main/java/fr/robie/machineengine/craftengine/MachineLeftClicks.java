package fr.robie.machineengine.craftengine;

import fr.robie.machineengine.api.machine.MachineInstance;
import fr.robie.machineengine.api.paper.MachineInteractions;
import fr.robie.machineengine.api.world.WorldPosition;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockInteractEvent;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.List;

/**
 * Left-clicks on machine blocks, for {@link MachineInteractions.Interaction#attack}: CraftEngine's
 * block {@code attack} hook cannot stop the block from breaking, its interact event can.
 */
final class MachineLeftClicks implements Listener {
    private final CraftEngineMachineHost host;

    MachineLeftClicks(CraftEngineMachineHost host) {
        this.host = host;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    void onClick(CustomBlockInteractEvent event) {
        if (event.action() != CustomBlockInteractEvent.Action.LEFT_CLICK || event.hand() != InteractionHand.MAIN_HAND) return;
        Block block = event.bukkitBlock();
        MachineInstance machine = this.host.machineAt(WorldPosition.of(block.getWorld().getKey(), block.getX(), block.getY(), block.getZ()));
        if (machine == null) return;
        List<MachineInteractions.Interaction> interactions = MachineInteractions.of(machine.inspect().type());
        if (interactions.isEmpty()) return;
        Player player = event.player();
        MachineInteractions.Click click = new MachineInteractions.Click(player, player.getInventory().getItemInMainHand(), machine,
                block, event.clickedFace(), player.isSneaking());
        for (MachineInteractions.Interaction interaction : interactions) {
            if (interaction.attack(click)) {
                event.setCancelled(true);
                return;
            }
        }
    }
}
