package fr.robie.machineengine.api.machine;

import fr.robie.machineengine.api.upgrade.UpgradeType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.Keyed;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * What a machine is (ARCHITECTURE.md §12): storages, ports, extra capabilities, persisted data,
 * upgrade slots, menu actions and lists (D40) and behavior. Immutable; one registered instance per type.
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
    private final CatchUpPolicy catchUp;
    private final Map<String, MachineAction> actions;
    private final Map<String, MachineList> lists;

    private MachineType(Builder builder) {
        this.key = builder.key;
        this.storages = List.copyOf(builder.storages);
        this.ports = List.copyOf(builder.ports);
        this.capabilities = List.copyOf(builder.capabilities);
        this.data = List.copyOf(builder.data);
        this.upgradeSlots = builder.upgradeSlots;
        this.acceptsUpgrade = builder.acceptsUpgrade;
        this.behavior = Objects.requireNonNull(builder.behavior, "behavior(...) is required");
        this.catchUp = builder.catchUp;
        this.actions = Map.copyOf(builder.actions);
        this.lists = Map.copyOf(builder.lists);
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

    public @NotNull CatchUpPolicy catchUp() {
        return this.catchUp;
    }

    /** The actions menus can run on this type's machines, by id (D40). */
    public @NotNull Map<String, MachineAction> actions() {
        return this.actions;
    }

    public @NotNull Optional<MachineAction> action(@NotNull String id) {
        return Optional.ofNullable(this.actions.get(id));
    }

    /** The lists menus can show for this type's machines, by id (D40). */
    public @NotNull Map<String, MachineList> lists() {
        return this.lists;
    }

    public @NotNull Optional<MachineList> list(@NotNull String id) {
        return Optional.ofNullable(this.lists.get(id));
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
        private CatchUpPolicy catchUp = CatchUpPolicy.NONE;
        private final Map<String, MachineAction> actions = new LinkedHashMap<>();
        private final Map<String, MachineList> lists = new LinkedHashMap<>();

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

        /** Opt in to catching up time spent unloaded (D4). */
        public @NotNull Builder catchUp(@NotNull CatchUpPolicy policy) {
            this.catchUp = Objects.requireNonNull(policy);
            return this;
        }

        /** An action menus can run ({@code machineengine_action}, {@code action: <id>}); see {@link MachineAction}. */
        public @NotNull Builder action(@NotNull String id, @NotNull MachineAction action) {
            this.actions.put(checkId(id, "action"), Objects.requireNonNull(action));
            return this;
        }

        /** A list menus can show ({@code list_<id>_…} placeholders); see {@link MachineList}. */
        public @NotNull Builder list(@NotNull String id, @NotNull MachineList list) {
            this.lists.put(checkId(id, "list"), Objects.requireNonNull(list));
            return this;
        }

        private static String checkId(String id, String what) {
            if (!id.matches("[a-z0-9_]+")) throw new IllegalArgumentException(what + " id must match [a-z0-9_]+: " + id);
            return id;
        }

        public @NotNull MachineType build() {
            return new MachineType(this);
        }
    }
}
