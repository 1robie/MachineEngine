package fr.robie.machineengine.api.capability;

import fr.robie.machineengine.api.MachineEngineKeys;
import fr.robie.machineengine.api.transfer.ResourceHandler;

/** Infrastructure capabilities MachineEngine registers itself. */
public final class Capabilities {
    /** Resource access through a machine's ports. */
    public static final CapabilityType<ResourceHandler> RESOURCE_HANDLER =
            CapabilityType.of(MachineEngineKeys.key("resource_handler"), ResourceHandler.class);

    private Capabilities() {
    }
}
