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

    /** Removes up to {@code count} units; returns how many were removed. */
    int removeUpgrade(@NotNull UpgradeType type, int count);

    /** Signals an external event (owner thread). Wakes the machine if its state accepts it. */
    void wake(@NotNull WakeReason reason);

    @NotNull MachineInspection inspect();
}
