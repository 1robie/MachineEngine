package fr.robie.machineengine.core.machine;

import fr.robie.machineengine.api.data.DataMap;
import fr.robie.machineengine.api.data.DataValue;
import fr.robie.machineengine.api.event.EventBus;
import fr.robie.machineengine.api.machine.MachineEvents;
import fr.robie.machineengine.api.machine.MachineType;
import fr.robie.machineengine.api.recipe.Recipe;
import fr.robie.machineengine.api.recipe.RecipeLookup;
import fr.robie.machineengine.api.registry.Registries;
import fr.robie.machineengine.api.registry.RegistryAccess;
import fr.robie.machineengine.api.registry.ReloadableRegistry;
import fr.robie.machineengine.api.resource.ResourceStack;
import fr.robie.machineengine.api.resource.tag.ResourceTags;
import fr.robie.machineengine.api.transfer.TransactionManager;
import fr.robie.machineengine.api.world.Direction;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.core.machine.handoff.HandoffService;
import fr.robie.machineengine.core.network.CoreNetworks;
import fr.robie.machineengine.core.resource.ResourceCodecs;
import fr.robie.messageflow.logger.Logger;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

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
    private final CoreNetworks networks;
    private final Logger logger;

    public CoreMachines(@NotNull RegistryAccess registries, @NotNull TransactionManager transactions,
                        @NotNull Supplier<ResourceTags> tags, @NotNull EventBus events, @NotNull ResourceCodecs codecs,
                        @NotNull Logger logger) {
        this.registries = registries;
        this.transactions = transactions;
        this.tags = tags;
        this.events = events;
        this.codecs = codecs;
        this.recipes = new CoreRecipeLookup((ReloadableRegistry<Recipe>)
                registries.registry(Registries.RECIPE));
        this.logger = logger;
        this.networks = new CoreNetworks(transactions, tags, logger);
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

    /**
     * Removes a machine, keeping its state for an item (keep-contents, §20.5). The state leaves out
     * everything tied to the old position (handoff bookkeeping, save time).
     *
     * @return empty if there is nothing worth keeping, or a handoff is still in flight (the caller
     *         then removes the machine normally)
     */
    public @NotNull Optional<Packed> pack(@NotNull LoadedMachine machine) {
        MachineInstanceImpl instance = machine.instance();
        if (instance == null || !instance.isValid() || instance.hasEscrow() || !instance.holdsContents()) {
            return Optional.empty();
        }
        DataMap state = MachineCodec.encode(instance, instance.gameTime())
                .without(MachineCodec.ESCROW).without(MachineCodec.DEDUPE).without(MachineCodec.SAVED_AT);
        return Optional.of(new Packed(instance.type().key(), state, instance.removed()));
    }

    /**
     * Replaces a just-placed machine with a packed state (keep-contents, §20.5). The machine resumes
     * at {@code fresh}'s position and facing; the time spent as an item does not count (no catch-up).
     *
     * @return the restored machine, or {@code fresh} unchanged if the state is for another machine type
     */
    public @NotNull LoadedMachine restore(@NotNull LoadedMachine fresh, @NotNull DataMap state, @NotNull MachineHost host) {
        MachineInstanceImpl instance = fresh.instance();
        if (instance == null || !state.getString(MachineCodec.TYPE).orElse("").equals(instance.type().key().asString())) return fresh;
        DataMap data = state
                .with(MachineCodec.SAVED_AT, DataValue.of(host.gameTime(fresh.position().world())))
                .with(MachineCodec.FACING, DataValue.of(instance.facing().name().toLowerCase(Locale.ROOT)));
        instance.discarded();
        LoadedMachine restored = MachineCodec.decode(data, fresh.position(), this, host);
        MachineInstanceImpl machine = restored.instance();
        if (machine != null) {
            machine.markDirty();
            machine.loaded();
        }
        return restored;
    }

    /**
     * Changing a placed machine's type while keeping its state (E6, tier installers): {@code fresh}
     * (a just-placed machine of the new type) takes over the packed state of another type. Storages,
     * data and upgrades with the same ids carry over; what the new type has no room for stays in its
     * saved data, as for any unknown content (D3). The process must be cancelled before packing: it
     * belongs to the old type's behavior.
     *
     * @return the machine of {@code fresh}'s type holding the state
     */
    public @NotNull LoadedMachine restoreAs(@NotNull LoadedMachine fresh, @NotNull DataMap state, @NotNull MachineHost host) {
        MachineInstanceImpl instance = fresh.instance();
        if (instance == null) return fresh;
        DataMap retyped = state.without(MachineCodec.PROCESS)
                .with(MachineCodec.TYPE, DataValue.of(instance.type().key().asString()));
        return restore(fresh, retyped, host);
    }

    /**
     * A machine packed for an item.
     *
     * @param state    what to store in the item, for {@link #restore}
     * @param contents what the machine held, for hosts that cannot keep {@code state} after all and
     *                 drop these instead
     */
    public record Packed(@NotNull Key type, @NotNull DataMap state, @NotNull List<ResourceStack> contents) {
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
    public @NotNull CoreNetworks networks() {
        return this.networks;
    }

    @Override
    public @NotNull Logger logger() {
        return this.logger;
    }
}
