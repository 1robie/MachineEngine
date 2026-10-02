package fr.robie.machineengine.example;

import fr.robie.machineengine.paper.event.MachineCreateEvent;
import fr.robie.machineengine.paper.event.MachineRemoveEvent;
import fr.robie.machineengine.paper.event.PlayerMachineInteractEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.logging.Logger;

/**
 * Plain Bukkit listeners for MachineEngine's mirrored events: what a plugin that does not use the
 * MachineEngine event bus sees.
 */
final class ExampleBukkitListener implements Listener {
    private final Logger logger;

    ExampleBukkitListener(Logger logger) {
        this.logger = logger;
    }

    @EventHandler
    void onCreate(MachineCreateEvent event) {
        this.logger.info("Machine created: " + event.getMachineType().asString() + " at " + event.getMachine().position());
    }

    @EventHandler
    void onRemove(MachineRemoveEvent event) {
        this.logger.info("Machine removed: " + event.getMachineType().asString() + " at " + event.getMachine().position());
    }

    @EventHandler(ignoreCancelled = true)
    void onInteract(PlayerMachineInteractEvent event) {
        this.logger.info(event.getPlayer().getName() + " opened " + event.getMachineType().asString());
    }
}
