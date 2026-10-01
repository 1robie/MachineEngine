package fr.robie.machineengine.api.machine;

import fr.robie.machineengine.api.upgrade.UpgradeType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.Keyed;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * What a machine is (ARCHITECTURE.md §12): storages, ports, extra capabilities, persisted data,
 * upgrade slots and behavior. Immutable; one registered instance per type.
 */
public final class MachineType implements Keyed {
    private final Key key;
    private final List<StorageDefinition> storages;
    private final List<PortDefinition> ports;
    private final List<CapabilityBinding<?>> capabilities;
    private final List<MachineDataKey<?>> data;
    private final int upgradeSlots;
    private final Predicate<UpgradeType> acceptsUpgrade;
    private final MachineBehavior.Factory behavior;

    private MachineType(Builder builder) {
        this.key = builder.key;
        this.storages = List.copyOf(builder.storages);
        this.ports = List.copyOf(builder.ports);
        this.capabilities = List.copyOf(builder.capabilities);
        this.data = List.copyOf(builder.data);
        this.upgradeSlots = builder.upgradeSlots;
        this.acceptsUpgrade = builder.acceptsUpgrade;
        this.behavior = Objects.requireNonNull(builder.behavior, "behavior(...) is required");
    }

    public static @NotNull Builder builder(@NotNull Key key) {
        return new Builder(key);
    }

    @Override
    public @NotNull Key key() {
        return this.key;
    }

    public @NotNull List<StorageDefinition> storages() {
        return this.storages;
    }

    public @NotNull Optional<StorageDefinition> storage(@NotNull String id) {
        return this.storages.stream().filter(storage -> storage.id().equals(id)).findFirst();
    }

    public @NotNull List<PortDefinition> ports() {
        return this.ports;
    }

    public @NotNull List<CapabilityBinding<?>> capabilities() {
        return this.capabilities;
    }

    public @NotNull List<MachineDataKey<?>> data() {
        return this.data;
    }

    /** Number of distinct upgrade types that can be installed at once. */
    public int upgradeSlots() {
        return this.upgradeSlots;
    }

    public boolean acceptsUpgrade(@NotNull UpgradeType type) {
        return this.acceptsUpgrade.test(type);
    }

    public @NotNull MachineBehavior.Factory behavior() {
        return this.behavior;
    }

    @Override
    public String toString() {
        return "MachineType[" + this.key.asString() + "]";
    }

    public static final class Builder {
        private final Key key;
        private final List<StorageDefinition> storages = new ArrayList<>();
        private final List<PortDefinition> ports = new ArrayList<>();
        private final List<CapabilityBinding<?>> capabilities = new ArrayList<>();
        private final List<MachineDataKey<?>> data = new ArrayList<>();
        private int upgradeSlots;
        private Predicate<UpgradeType> acceptsUpgrade = type -> true;
        private MachineBehavior.Factory behavior;

        private Builder(Key key) {
            this.key = Objects.requireNonNull(key);
        }

        public @NotNull Builder storage(@NotNull StorageDefinition storage) {
            this.storages.add(storage);
            return this;
        }

        public @NotNull Builder port(@NotNull PortDefinition port) {
            this.ports.add(port);
            return this;
        }

        public @NotNull Builder capability(@NotNull CapabilityBinding<?> capability) {
            this.capabilities.add(capability);
            return this;
        }

        public @NotNull Builder data(@NotNull MachineDataKey<?> key) {
            this.data.add(key);
            return this;
        }

        public @NotNull Builder upgradeSlots(int slots, @NotNull Predicate<UpgradeType> accepts) {
            this.upgradeSlots = slots;
            this.acceptsUpgrade = Objects.requireNonNull(accepts);
            return this;
        }

        public @NotNull Builder behavior(@NotNull MachineBehavior.Factory behavior) {
            this.behavior = Objects.requireNonNull(behavior);
            return this;
        }

        public @NotNull MachineType build() {
            return new MachineType(this);
        }
    }
}
