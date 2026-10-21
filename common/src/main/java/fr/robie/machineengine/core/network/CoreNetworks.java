package fr.robie.machineengine.core.network;

import fr.robie.machineengine.api.capability.Capabilities;
import fr.robie.machineengine.api.item.Items;
import fr.robie.machineengine.api.machine.PortDefinition;
import fr.robie.machineengine.api.machine.PortDirection;
import fr.robie.machineengine.api.network.NetworkContext;
import fr.robie.machineengine.api.network.NetworkEndpoint;
import fr.robie.machineengine.api.network.NetworkType;
import fr.robie.machineengine.api.network.NetworkView;
import fr.robie.machineengine.api.network.TransportSideMode;
import fr.robie.machineengine.api.network.TransportType;
import fr.robie.machineengine.api.resource.ResourceVariant;
import fr.robie.machineengine.api.resource.tag.ResourceTags;
import fr.robie.machineengine.api.transfer.ResourceHandler;
import fr.robie.machineengine.api.transfer.StorageView;
import fr.robie.machineengine.api.transfer.TransactionContext;
import fr.robie.machineengine.api.transfer.TransactionManager;
import fr.robie.machineengine.api.world.Direction;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.core.machine.MachineHost;
import fr.robie.machineengine.core.machine.MachineInstanceImpl;
import fr.robie.messageflow.formatter.Placeholder;
import fr.robie.messageflow.logger.Logger;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Networks of transports (ARCHITECTURE.md §15). Owned by the engine, per world, and rebuilt from
 * the loaded transports, so nothing about networks is persisted:
 * <ul>
 *   <li>adding a transport joins or merges the networks of its neighbors (incremental);</li>
 *   <li>removing or unloading one splits its network if needed (search limited to that network);</li>
 *   <li>a network only spans transports owned by one thread (Folia region); at a region border
 *       the networks stay separate;</li>
 *   <li>a network ticks only while it moves something, and wakes when one of its machines'
 *       storages changes or its topology changes.</li>
 * </ul>
 */
public final class CoreNetworks {
    private static final Comparator<NetworkEndpoint> ENDPOINT_ORDER = Comparator
            .comparing((NetworkEndpoint endpoint) -> endpoint.machine().world().asString())
            .thenComparingInt(endpoint -> endpoint.machine().position().x())
            .thenComparingInt(endpoint -> endpoint.machine().position().y())
            .thenComparingInt(endpoint -> endpoint.machine().position().z())
            .thenComparing(NetworkEndpoint::side);

    private final TransactionManager transactions;
    private final Supplier<ResourceTags> tags;
    private final Logger logger;
    private final Map<Key, WorldGraph> worlds = new ConcurrentHashMap<>();

    public CoreNetworks(@NotNull TransactionManager transactions, @NotNull Supplier<ResourceTags> tags, @NotNull Logger logger) {
        this.transactions = transactions;
        this.tags = tags;
        this.logger = logger;
    }

    // -- topology (owner thread of the position) ---------------------------------------------------

    public void addTransport(@NotNull TransportType type, @NotNull WorldPosition position, @NotNull MachineHost host) {
        graph(position).add(type, position, host);
    }

    /** The transport was broken or its chunk unloaded. */
    public void removeTransport(@NotNull WorldPosition position) {
        graph(position).remove(position);
        this.sideModes.remove(position);
    }

    // -- side modes of transports (Mekanism's connection types) ------------------------------

    /** Per transport, the sides not in {@link TransportSideMode#DEFAULT}. */
    private final Map<WorldPosition, Map<Direction, TransportSideMode>> sideModes =
            new ConcurrentHashMap<>();

    public @NotNull TransportSideMode sideMode(@NotNull WorldPosition transport, @NotNull Direction side) {
        Map<Direction, TransportSideMode> modes = this.sideModes.get(transport);
        return modes == null ? TransportSideMode.DEFAULT
                : modes.getOrDefault(side, TransportSideMode.DEFAULT);
    }

    /** The sides of {@code transport} not in the default mode, e.g. to save them with the block. */
    public @NotNull Map<Direction, TransportSideMode> sideModes(@NotNull WorldPosition transport) {
        Map<Direction, TransportSideMode> modes = this.sideModes.get(transport);
        return modes == null ? Map.of() : Map.copyOf(modes);
    }

    /** Sets how {@code side} of {@code transport} connects; its network re-reads its endpoints. */
    public void setSideMode(@NotNull WorldPosition transport, @NotNull Direction side, @NotNull TransportSideMode mode) {
        if (mode == TransportSideMode.DEFAULT) {
            Map<Direction, TransportSideMode> modes = this.sideModes.get(transport);
            if (modes != null) {
                modes.remove(side);
                if (modes.isEmpty()) this.sideModes.remove(transport);
            }
        } else {
            this.sideModes.computeIfAbsent(transport, key -> new ConcurrentHashMap<>()).put(side, mode);
        }
        // Toward another transport, "none" cuts the link: the networks are rebuilt around it.
        if (transportAt(transport.relative(side)) != null) graph(transport).relink(transport);
        machineChanged(transport.relative(side));
    }

    /** Whether the transports at {@code position} and on its {@code side} link up (neither side set to none). */
    private boolean linked(WorldPosition position, Direction side) {
        return sideMode(position, side) != TransportSideMode.NONE
                && sideMode(position.relative(side), side.opposite()) != TransportSideMode.NONE;
    }

    /** A machine next to {@code position} loaded, unloaded, rotated or was removed. */
    public void machineChanged(@NotNull WorldPosition position) {
        graph(position).neighborNetworks(position).forEach(network -> {
            network.endpointsDirty = true;
            network.activate();
        });
    }

    /** A storage of the machine at {@code position} changed: its networks may have work. */
    public void machineActivity(@NotNull WorldPosition position) {
        WorldGraph graph = this.worlds.get(position.world());
        if (graph != null) graph.neighborNetworks(position).forEach(Network::activate);
    }

    // -- inspection ----------------------------------------------------------------------------

    public @NotNull Optional<NetworkView> network(@NotNull WorldPosition transport) {
        WorldGraph graph = this.worlds.get(transport.world());
        if (graph == null) return Optional.empty();
        return Optional.ofNullable(graph.viewOf(transport));
    }

    /** The transport at {@code position}, if one is loaded there. */
    public @Nullable TransportType transportAt(@NotNull WorldPosition position) {
        WorldGraph graph = this.worlds.get(position.world());
        return graph == null ? null : graph.transportAt(position);
    }

    /**
     * The sides a transport connects through, for drawing it: a neighbor transport of the same
     * network type, or a machine port on the facing side that carries something the transport does
     * ({@code probes}: one variant of each resource type the transport's filter accepts).
     */
    public @NotNull Set<Direction> connections(@NotNull WorldPosition position, @NotNull MachineHost host,
                                               @NotNull Collection<ResourceVariant> probes) {
        TransportType type = transportAt(position);
        if (type == null) return Set.of();
        ResourceTags tags = this.tags.get();
        List<ResourceVariant> carried = probes.stream().filter(probe -> type.filter().test(probe, tags)).toList();
        Set<Direction> connected = EnumSet.noneOf(Direction.class);
        for (Direction direction : Direction.values()) {
            WorldPosition neighbor = position.relative(direction);
            TransportType other = transportAt(neighbor);
            if (other != null) {
                if (other.network() == type.network() && linked(position, direction)) connected.add(direction);
                continue;
            }
            if (sideMode(position, direction) == TransportSideMode.NONE) continue;
            if (!host.isOwnedByCurrentThread(neighbor)) continue;
            MachineInstanceImpl machine = host.machineAt(neighbor);
            if (machine != null) {
                if (carriesThrough(machine, direction.opposite(), carried, tags)) connected.add(direction);
            } else if (carried.stream().anyMatch(probe -> probe.type().category().equals(Items.CATEGORY))
                    && host.worldHandlerAt(neighbor, direction.opposite()) != null) {
                connected.add(direction); // an item transport next to a container block
            }
        }
        return connected;
    }

    private static boolean carriesThrough(MachineInstanceImpl machine, Direction side, List<ResourceVariant> carried, ResourceTags tags) {
        for (PortDefinition port : machine.type().ports()) {
            // The machine's own configuration: a mode may disable a port, side configuration moves it.
            if (machine.context().portDirection(port.id()) == PortDirection.DISABLED) continue;
            if (machine.context().portSides(port.id()).stream().noneMatch(relative -> relative.toAbsolute(machine.facing()) == side)) continue;
            var storage = machine.type().storages().stream().filter(definition -> definition.id().equals(port.storage())).findFirst();
            if (storage.isEmpty()) continue;
            for (ResourceVariant probe : carried) {
                if (port.filter().test(probe, tags) && storage.get().filter().test(probe, tags)) return true;
            }
        }
        return false;
    }

    public @NotNull List<NetworkView> networks(@NotNull Key world) {
        WorldGraph graph = this.worlds.get(world);
        return graph == null ? List.of() : graph.views();
    }

    private WorldGraph graph(WorldPosition position) {
        return this.worlds.computeIfAbsent(position.world(), world -> new WorldGraph());
    }

    // ------------------------------------------------------------------------------------------

    /** Transports and networks of one world. Topology changes are serialized by the graph lock. */
    private final class WorldGraph {
        private final Map<WorldPosition, TransportType> transports = new HashMap<>();
        private final Map<WorldPosition, Network> networkOf = new HashMap<>();

        synchronized void add(TransportType type, WorldPosition position, MachineHost host) {
            if (this.transports.containsKey(position)) remove(position);
            this.transports.put(position, type);
            Set<Network> joined = new LinkedHashSet<>();
            for (Direction direction : Direction.values()) {
                WorldPosition neighbor = position.relative(direction);
                Network network = this.networkOf.get(neighbor);
                if (network != null && network.type == type.network() && host.isOwnedByCurrentThread(neighbor)
                        && linked(position, direction)) joined.add(network);
            }
            Network target;
            if (joined.isEmpty()) {
                target = new Network(type.network(), host);
            } else {
                // Merge into the largest network: fewest positions move.
                target = joined.stream().max(Comparator.comparingInt(network -> network.members.size())).orElseThrow();
                for (Network other : joined) {
                    if (other == target) continue;
                    for (WorldPosition member : other.members) {
                        target.members.add(member);
                        this.networkOf.put(member, target);
                    }
                    other.valid = false;
                }
            }
            target.members.add(position);
            this.networkOf.put(position, target);
            target.topologyChanged(this.transports);
        }

        synchronized void remove(WorldPosition position) {
            this.transports.remove(position);
            Network network = this.networkOf.remove(position);
            if (network == null) return;
            network.members.remove(position);
            if (network.members.isEmpty()) {
                network.valid = false;
                return;
            }
            // Split into connected components, searching only the old network.
            Set<WorldPosition> unvisited = new LinkedHashSet<>(network.members);
            List<Set<WorldPosition>> components = new ArrayList<>();
            while (!unvisited.isEmpty()) {
                WorldPosition start = unvisited.iterator().next();
                Set<WorldPosition> component = new LinkedHashSet<>();
                Deque<WorldPosition> queue = new ArrayDeque<>(List.of(start));
                unvisited.remove(start);
                while (!queue.isEmpty()) {
                    WorldPosition current = queue.poll();
                    component.add(current);
                    for (Direction direction : Direction.values()) {
                        WorldPosition next = current.relative(direction);
                        if (linked(current, direction) && unvisited.remove(next)) queue.add(next);
                    }
                }
                components.add(component);
            }
            if (components.size() == 1) {
                network.topologyChanged(this.transports);
                return;
            }
            network.valid = false;
            for (Set<WorldPosition> component : components) {
                Network split = new Network(network.type, network.host);
                split.members.addAll(component);
                component.forEach(member -> this.networkOf.put(member, split));
                split.topologyChanged(this.transports);
            }
        }

        /** Takes a transport out and puts it back, so its links follow changed side modes. */
        synchronized void relink(WorldPosition position) {
            TransportType type = this.transports.get(position);
            Network network = this.networkOf.get(position);
            if (type == null || network == null) return;
            MachineHost host = network.host;
            remove(position);
            add(type, position, host);
        }

        synchronized @Nullable TransportType transportAt(WorldPosition position) {
            return this.transports.get(position);
        }

        /** Built under the lock: another region thread may be changing the network's members. */
        synchronized @Nullable NetworkView viewOf(WorldPosition position) {
            Network network = this.networkOf.get(position);
            return network == null ? null : network.view();
        }

        synchronized Set<Network> neighborNetworks(WorldPosition position) {
            Set<Network> networks = new LinkedHashSet<>();
            for (Direction direction : Direction.values()) {
                Network network = this.networkOf.get(position.relative(direction));
                if (network != null) networks.add(network);
            }
            return networks;
        }

        synchronized List<NetworkView> views() {
            return new LinkedHashSet<>(this.networkOf.values()).stream().map(Network::view).toList();
        }
    }

    /** One connected network. Ticked on the thread owning its anchor (smallest member position). */
    private final class Network {
        private final NetworkType type;
        private final MachineHost host;
        private final Set<WorldPosition> members = new LinkedHashSet<>();
        private volatile boolean valid = true;
        private volatile long rate;
        private volatile Predicate<ResourceVariant> filter = variant -> true;
        private List<TransportType> transportTypes = List.of();
        private List<NetworkEndpoint> endpoints = List.of();
        private volatile boolean endpointsDirty = true;
        private boolean active;
        private boolean scheduled;
        private volatile long movedLastTick;
        private WorldPosition anchor;

        private Network(NetworkType type, MachineHost host) {
            this.type = type;
            this.host = host;
        }

        /** Under the graph lock. */
        void topologyChanged(Map<WorldPosition, TransportType> transports) {
            this.anchor = this.members.stream().min(Comparator.comparingInt((WorldPosition p) -> p.position().x())
                    .thenComparingInt(p -> p.position().y()).thenComparingInt(p -> p.position().z())).orElseThrow();
            List<TransportType> types = this.members.stream().map(transports::get).distinct().toList();
            this.transportTypes = types;
            this.rate = types.stream().mapToLong(TransportType::ratePerTick).min().orElse(0);
            this.endpointsDirty = true;
            activate();
        }

        synchronized void activate() {
            if (!this.valid) return;
            this.active = true;
            if (this.scheduled) return;
            this.scheduled = true;
            this.host.post(this.anchor, this::tick);
        }

        private void tick() {
            synchronized (this) {
                this.scheduled = false;
                if (!this.valid || !this.active) return;
            }
            if (this.endpointsDirty) rebuildEndpoints();
            ResourceTags tags = CoreNetworks.this.tags.get();
            this.filter = variant -> this.transportTypes.stream().allMatch(type -> type.filter().test(variant, tags));
            long moved;
            try {
                moved = this.type.behavior().tick(context(tags));
            } catch (RuntimeException e) {
                CoreNetworks.this.logger.logError("[networks]", "Network %type% at %position% failed", e,
                        Placeholder.of("type", this.type.key().asString(), "position", String.valueOf(this.anchor)));
                moved = 0;
            }
            this.movedLastTick = moved;
            synchronized (this) {
                if (moved == 0) {
                    this.active = false;
                } else if (this.valid && !this.scheduled) {
                    this.scheduled = true;
                    this.host.post(this.anchor, this::tick);
                }
            }
        }

        private void rebuildEndpoints() {
            this.endpointsDirty = false;
            Set<WorldPosition> members;
            synchronized (CoreNetworks.this.graph(this.anchor)) {
                members = Set.copyOf(this.members);
            }
            Map<String, NetworkEndpoint> found = new LinkedHashMap<>();
            for (WorldPosition transport : members) {
                for (Direction direction : Direction.values()) {
                    WorldPosition neighbor = transport.relative(direction);
                    if (members.contains(neighbor) || !this.host.isOwnedByCurrentThread(neighbor)) continue;
                    var mode = CoreNetworks.this.sideMode(transport, direction);
                    if (mode == TransportSideMode.NONE) continue;
                    MachineInstanceImpl machine = this.host.machineAt(neighbor);
                    Direction side = direction.opposite();
                    ResourceHandler handler = machine != null ? machine.capability(Capabilities.RESOURCE_HANDLER, side).orElse(null)
                            : this.host.worldHandlerAt(neighbor, side);
                    if (handler == null) continue;
                    // Pull: only taken from. Push: only filled. Default: machines as their ports say, containers only filled.
                    handler = switch (mode) {
                        case PULL -> restricted(handler, false, true);
                        case PUSH -> restricted(handler, true, false);
                        default -> machine == null ? restricted(handler, true, false) : handler;
                    };
                    found.putIfAbsent(neighbor + "|" + side, new NetworkEndpoint(neighbor, side, handler, machine == null));
                }
            }
            List<NetworkEndpoint> sorted = new ArrayList<>(found.values());
            sorted.sort(ENDPOINT_ORDER);
            this.endpoints = List.copyOf(sorted);
        }

        /** {@code handler}, allowing only insertion and/or extraction. */
        private static ResourceHandler restricted(ResourceHandler handler, boolean insert, boolean extract) {
            return new ResourceHandler() {
                @Override
                public long insert(@NotNull ResourceVariant variant, long maxAmount, @NotNull TransactionContext transaction) {
                    return insert ? handler.insert(variant, maxAmount, transaction) : 0;
                }

                @Override
                public long extract(@NotNull ResourceVariant variant, long maxAmount, @NotNull TransactionContext transaction) {
                    return extract ? handler.extract(variant, maxAmount, transaction) : 0;
                }

                @Override
                public @NotNull List<StorageView> views() {
                    return handler.views();
                }

                @Override
                public boolean supportsInsertion() {
                    return insert && handler.supportsInsertion();
                }

                @Override
                public boolean supportsExtraction() {
                    return extract && handler.supportsExtraction();
                }
            };
        }

        private NetworkContext context(ResourceTags tags) {
            Network network = this;
            return new NetworkContext() {
                @Override
                public @NotNull NetworkType type() {
                    return network.type;
                }

                @Override
                public @NotNull List<NetworkEndpoint> endpoints() {
                    return network.endpoints;
                }

                @Override
                public long ratePerTick() {
                    return network.rate;
                }

                @Override
                public @NotNull Predicate<ResourceVariant> filter() {
                    return network.filter;
                }

                @Override
                public @NotNull TransactionManager transactions() {
                    return CoreNetworks.this.transactions;
                }

                @Override
                public @NotNull ResourceTags tags() {
                    return tags;
                }

                @Override
                public long gameTime() {
                    return network.host.gameTime(network.anchor.world());
                }
            };
        }

        /** Under the graph lock. */
        NetworkView view() {
            List<String> attached = this.endpoints.stream().map(endpoint -> endpoint.machine() + " " + endpoint.side()).toList();
            return new NetworkView(this.type, this.members, attached, this.rate, this.active, this.movedLastTick);
        }
    }
}
