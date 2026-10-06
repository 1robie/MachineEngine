package fr.robie.machineengine.api.network;

/**
 * How one side of a transport connects to the block there (Mekanism's connection types), set per
 * transport and side (e.g. with a configurator).
 */
public enum TransportSideMode {
    /** Machines: the network takes from their outputs and fills their inputs. Containers: only filled. */
    DEFAULT,
    /** The network pulls from the block (a container or a machine's outputs) and never fills it. */
    PULL,
    /** The network only fills the block. */
    PUSH,
    /** Not connected. */
    NONE;

    /** The next mode, for cycling with a tool. */
    public TransportSideMode next() {
        TransportSideMode[] modes = values();
        return modes[(ordinal() + 1) % modes.length];
    }
}
