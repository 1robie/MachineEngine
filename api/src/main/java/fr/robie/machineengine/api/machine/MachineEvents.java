package fr.robie.machineengine.api.machine;

import fr.robie.machineengine.api.MachineEngineKeys;
import fr.robie.machineengine.api.event.EventPhase;
import fr.robie.machineengine.api.event.EventType;
import fr.robie.machineengine.api.process.ProcessView;
import fr.robie.machineengine.api.resource.ResourceVariant;
import fr.robie.machineengine.api.upgrade.UpgradeType;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Machine lifecycle and process events (ARCHITECTURE.md §26). All are posted in the
 * {@link EventPhase#POST} phase, after the change happened.
 */
public final class MachineEvents {
    public static final EventType<Lifecycle> MACHINE_CREATE = EventType.of(MachineEngineKeys.key("machine_create"), Lifecycle.class);
    public static final EventType<Lifecycle> MACHINE_LOAD = EventType.of(MachineEngineKeys.key("machine_load"), Lifecycle.class);
    public static final EventType<Lifecycle> MACHINE_UNLOAD = EventType.of(MachineEngineKeys.key("machine_unload"), Lifecycle.class);
    public static final EventType<Lifecycle> MACHINE_REMOVE = EventType.of(MachineEngineKeys.key("machine_remove"), Lifecycle.class);
    public static final EventType<Process> PROCESS_START = EventType.of(MachineEngineKeys.key("process_start"), Process.class);
    public static final EventType<Process> PROCESS_COMPLETE = EventType.of(MachineEngineKeys.key("process_complete"), Process.class);
    /** Upgrades were installed or removed. */
    public static final EventType<Upgrade> UPGRADE_CHANGE = EventType.of(MachineEngineKeys.key("upgrade_change"), Upgrade.class);
    /** A menu action did something (D40); refused actions post nothing. */
    public static final EventType<Action> ACTION = EventType.of(MachineEngineKeys.key("machine_action"), Action.class);
    /**
     * What went in and out of a machine's storages, summed per resource: at most one event per machine
     * per tick, after the changes, and only built while someone listens (no per-transfer events, §26).
     */
    public static final EventType<Resources> RESOURCES_CHANGED = EventType.of(MachineEngineKeys.key("resources_changed"), Resources.class);

    private MachineEvents() {
    }

    public record Lifecycle(@NotNull MachineInstance machine) {
    }

    public record Process(@NotNull MachineInstance machine, @NotNull ProcessView process) {
    }

    /** @param previous how many were installed before; {@code current} how many now (0: removed) */
    public record Upgrade(@NotNull MachineInstance machine, @NotNull UpgradeType upgrade,
                          int previous, int current) {
    }

    /** @param action the action's id in the machine's type */
    public record Action(@NotNull MachineInstance machine, @NotNull String action, @NotNull MachineAction.Click click,
                         @NotNull MachineAction.Outcome outcome) {
    }

    /** Amounts inserted and extracted since the last event of this machine. */
    public record Resources(@NotNull MachineInstance machine,
                            @NotNull Map<ResourceVariant, Long> inserted,
                            @NotNull Map<ResourceVariant, Long> extracted) {
    }
}
