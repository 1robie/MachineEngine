package fr.robie.machineengine.api.network;

import fr.robie.machineengine.api.transfer.ResourceHandler;
import fr.robie.machineengine.api.world.Direction;
import fr.robie.machineengine.api.world.WorldPosition;
import org.jetbrains.annotations.NotNull;

/**
 * A machine port, or a container block, touching a network.
 *
 * @param machine   the machine's (or container's) position
 * @param side      its face that touches the network
 * @param handler   the port's resource handler (direction and filters already apply)
 * @param container a container block (e.g. a vanilla chest), not a machine: its changes wake no
 *                  network, so networks pulling from it poll
 */
public record NetworkEndpoint(@NotNull WorldPosition machine, @NotNull Direction side, @NotNull ResourceHandler handler,
                              boolean container) {

    public NetworkEndpoint(@NotNull WorldPosition machine, @NotNull Direction side, @NotNull ResourceHandler handler) {
        this(machine, side, handler, false);
    }
}
