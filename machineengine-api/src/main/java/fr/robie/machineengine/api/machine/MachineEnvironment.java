package fr.robie.machineengine.api.machine;

/**
 * What a machine's block sees of the world (E3), read on the owner thread: e.g. for solar panels.
 *
 * @param skyVisible whether the block above has a clear view of the sky
 * @param daytime    whether it is day in the machine's world
 * @param raining    whether it rains or snows at the machine
 * @param skyLight   sky light level at the block above (0 to 15)
 */
public record MachineEnvironment(boolean skyVisible, boolean daytime, boolean raining, int skyLight) {
    /** No world (headless, unloaded): no sky, night, dry. */
    public static final MachineEnvironment NONE = new MachineEnvironment(false, false, false, 0);
}
