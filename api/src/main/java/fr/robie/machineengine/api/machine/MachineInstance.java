package fr.robie.machineengine.api.machine;

import fr.robie.machineengine.api.capability.CapabilityType;
import fr.robie.machineengine.api.error.MachineError;
import fr.robie.machineengine.api.error.Result;
import fr.robie.machineengine.api.upgrade.InstalledUpgrade;
import fr.robie.machineengine.api.upgrade.UpgradeType;
import fr.robie.machineengine.api.world.Direction;
import fr.robie.machineengine.api.world.WorldPosition;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A machine in the world (ARCHITECTURE.md §12). Owner thread only, except {@link #inspect()} snapshots
 * which are immutable once taken.
 */
@ApiStatus.NonExtendable
public interface MachineInstance {

    @NotNull MachineType type();

    @NotNull WorldPosition position();

    @NotNull Direction facing();

    @NotNull SchedulerState schedulerState();

    @Nullable WakeReason lastWakeReason();

    @Nullable MachineError lastError();

    /** Whether the machine is visibly working, see {@link MachineContext#setActive}. */
    boolean isActive();

    /**
     * A capability exposed on an absolute face, as another machine or transport sees it.
     * {@code side == null} asks for unsided capabilities.
     */
    <T> @NotNull Optional<T> capability(@NotNull CapabilityType<T> type, @Nullable Direction side);

    @NotNull List<InstalledUpgrade> upgrades();

    /**
     * Installs {@code count} units of an upgrade.
     *
     * @return an error if the type is not accepted, the slots are full, or the stack limit is reached
     */
    @NotNull Result<InstalledUpgrade> installUpgrade(@NotNull UpgradeType type, int count);

    /**
     * How many more units of {@code type} the machine takes now.
     *
     * @return at least 1, or an error saying why none fit: not registered, not accepted, every upgrade
     *         slot used by other types, or already at {@link UpgradeType#maxStack()}
     */
    @NotNull Result<Integer> upgradeRoom(@NotNull UpgradeType type);

    /** Removes up to {@code count} units; returns how many were removed. */
    int removeUpgrade(@NotNull UpgradeType type, int count);

    /**
     * Runs one of the type's menu actions (D40, {@link MachineType#action}). Owner thread. An unknown id, a
     * machine no longer valid, or an action that throws is refused; a run that did something posts
     * {@link MachineEvents#ACTION}.
     */
    @NotNull MachineAction.Outcome runAction(@NotNull String id, @NotNull MachineAction.Click click);

    /** The rows of one of the type's lists (D40, {@link MachineType#list}); empty for an unknown id. */
    @NotNull List<Map<String, String>> list(@NotNull String id);

    /** Signals an external event (owner thread). Wakes the machine if its state accepts it. */
    void wake(@NotNull WakeReason reason);

    @NotNull MachineInspection inspect();

    /**
     * The same context the machine's behavior gets, for code outside its tick (listeners, commands,
     * tools): its data, storages and world. Owner thread only.
     */
    @NotNull MachineContext context();
}
