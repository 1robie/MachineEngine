package fr.robie.machineengine.core.machine;

import fr.robie.machineengine.api.data.DataMap;
import fr.robie.machineengine.api.event.EventBus;
import fr.robie.machineengine.api.machine.MachineEvents;
import fr.robie.machineengine.api.machine.MachineType;
import fr.robie.machineengine.api.recipe.RecipeLookup;
import fr.robie.machineengine.api.registry.Registries;
import fr.robie.machineengine.api.registry.RegistryAccess;
import fr.robie.machineengine.api.resource.ResourceStack;
import fr.robie.machineengine.api.resource.tag.ResourceTags;
import fr.robie.machineengine.api.transfer.TransactionManager;
import fr.robie.machineengine.api.world.Direction;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.core.machine.handoff.HandoffService;
import fr.robie.machineengine.core.resource.ResourceCodecs;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Entry point for hosts: places, loads, saves, unloads and removes machines. One per engine.
 */
public final class CoreMachines implements MachineServices {
    private final RegistryAccess registries;
    private final TransactionManager transactions;
    private final Supplier<ResourceTags> tags;
    private final EventBus events;
    private final ResourceCodecs codecs;
    private final RecipeLookup recipes;
    private final HandoffService handoff = new HandoffService();
    private final fr.robie.machineengine.core.network.CoreNetworks networks;
    private final Logger logger;

    public CoreMachines(@NotNull RegistryAccess registries, @NotNull TransactionManager transactions,
                        @NotNull Supplier<ResourceTags> tags, @NotNull EventBus events, @NotNull ResourceCodecs codecs,
                        @NotNull Logger logger) {
        this.registries = registries;
        this.transactions = transactions;
        this.tags = tags;
        this.events = events;
        this.codecs = codecs;
        this.recipes = new CoreRecipeLookup((fr.robie.machineengine.api.registry.ReloadableRegistry<fr.robie.machineengine.api.recipe.Recipe>)
                registries.registry(Registries.RECIPE));
        this.logger = logger;
        this.networks = new fr.robie.machineengine.core.network.CoreNetworks(transactions, tags, logger);
    }

    /**
     * Places a new machine.
     *
     * @throws IllegalArgumentException if {@code type} is not registered
     */
    public @NotNull MachineInstanceImpl create(@NotNull MachineType type, @NotNull WorldPosition position,
                                               @NotNull Direction facing, @NotNull MachineHost host) {
        if (!this.registries.registry(Registries.MACHINE_TYPE).containsValue(type)) {
            throw new IllegalArgumentException(type + " is not registered");
        }
        MachineInstanceImpl machine = new MachineInstanceImpl(this, host, type, position, facing);
        machine.markDirty();
        machine.postLifecycle(MachineEvents.MACHINE_CREATE);
        machine.loaded();
        return machine;
    }

    /** Loads a saved machine; a placeholder if its type is missing (D3). */
    public @NotNull LoadedMachine load(@NotNull DataMap data, @NotNull WorldPosition position, @NotNull MachineHost host) {
        LoadedMachine loaded = MachineCodec.decode(data, position, this, host);
        MachineInstanceImpl machine = loaded.instance();
        if (machine != null) machine.loaded();
        return loaded;
    }

    public void unload(@NotNull LoadedMachine machine) {
        MachineInstanceImpl instance = machine.instance();
        if (instance != null) instance.unloaded();
    }

    /**
     * Removes a machine from the world.
     *
     * @return the resources it held, for the host to drop. Placeholders return nothing; hosts decide
     *         whether a placeholder may be removed at all (§21).
     */
    public @NotNull List<ResourceStack> remove(@NotNull LoadedMachine machine) {
        MachineInstanceImpl instance = machine.instance();
        return instance == null ? List.of() : instance.removed();
    }

    @Override
    public @NotNull TransactionManager transactions() {
        return this.transactions;
    }

    @Override
    public @NotNull ResourceTags tags() {
        return this.tags.get();
    }

    @Override
    public @NotNull RegistryAccess registries() {
        return this.registries;
    }

    @Override
    public @NotNull RecipeLookup recipes() {
        return this.recipes;
    }

    @Override
    public @NotNull EventBus events() {
        return this.events;
    }

    @Override
    public @NotNull ResourceCodecs resourceCodecs() {
        return this.codecs;
    }

    @Override
    public @NotNull HandoffService handoff() {
        return this.handoff;
    }

    @Override
    public @NotNull fr.robie.machineengine.core.network.CoreNetworks networks() {
        return this.networks;
    }

    @Override
    public @NotNull Logger logger() {
        return this.logger;
    }
}
