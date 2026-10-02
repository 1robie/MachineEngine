package fr.robie.machineengine.api.network;

import fr.robie.machineengine.api.transfer.ResourceHandler;
import fr.robie.machineengine.api.world.Direction;
import fr.robie.machineengine.api.world.WorldPosition;
import org.jetbrains.annotations.NotNull;

/**
 * A machine port touching a network.
 *
 * @param machine the machine's position
 * @param side    the machine's face that touches the network
 * @param handler the port's resource handler (direction and filters already apply)
 */
public record NetworkEndpoint(@NotNull WorldPosition machine, @NotNull Direction side, @NotNull ResourceHandler handler) {
}
