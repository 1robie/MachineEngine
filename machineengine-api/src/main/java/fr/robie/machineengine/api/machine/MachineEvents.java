package fr.robie.machineengine.api.machine;

import fr.robie.machineengine.api.MachineEngineKeys;
import fr.robie.machineengine.api.event.EventType;
import fr.robie.machineengine.api.process.ProcessView;
import org.jetbrains.annotations.NotNull;

/**
 * Machine lifecycle and process events (ARCHITECTURE.md §26). All are posted in the
 * {@link fr.robie.machineengine.api.event.EventPhase#POST} phase, after the change happened.
 */
public final class MachineEvents {
    public static final EventType<Lifecycle> MACHINE_CREATE = EventType.of(MachineEngineKeys.key("machine_create"), Lifecycle.class);
    public static final EventType<Lifecycle> MACHINE_LOAD = EventType.of(MachineEngineKeys.key("machine_load"), Lifecycle.class);
    public static final EventType<Lifecycle> MACHINE_UNLOAD = EventType.of(MachineEngineKeys.key("machine_unload"), Lifecycle.class);
    public static final EventType<Lifecycle> MACHINE_REMOVE = EventType.of(MachineEngineKeys.key("machine_remove"), Lifecycle.class);
    public static final EventType<Process> PROCESS_START = EventType.of(MachineEngineKeys.key("process_start"), Process.class);
    public static final EventType<Process> PROCESS_COMPLETE = EventType.of(MachineEngineKeys.key("process_complete"), Process.class);

    private MachineEvents() {
    }

    public record Lifecycle(@NotNull MachineInstance machine) {
    }

    public record Process(@NotNull MachineInstance machine, @NotNull ProcessView process) {
    }
}
