package fr.robie.machineengine.craftengine;

import fr.robie.machineengine.api.machine.PortDefinition;
import fr.robie.machineengine.api.machine.PortDirection;
import fr.robie.machineengine.api.resource.ResourceStack;
import fr.robie.machineengine.api.resource.ResourceVariant;
import fr.robie.machineengine.api.transfer.Transaction;
import fr.robie.machineengine.api.world.Direction;
import fr.robie.machineengine.api.world.RelativeSide;
import fr.robie.machineengine.core.machine.MachineInstanceImpl;
import fr.robie.machineengine.core.machine.MachineSlot;
import fr.robie.machineengine.core.machine.MachineStorage;
import fr.robie.machineengine.api.paper.PaperItems;
import net.momirealms.craftengine.bukkit.util.ItemStackUtils;
import net.momirealms.craftengine.bukkit.world.BukkitContainer;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.plugin.CraftEngine;
import net.momirealms.craftengine.core.world.WorldPosition;
import net.momirealms.craftengine.core.world.WorldlyContainer;
import org.bukkit.entity.HumanEntity;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Hopper (and dropper, hopper minecart) interop (§22.4): a machine's item ports seen as a vanilla
 * {@code WorldlyContainer}, through CraftEngine's container bridge.
 *
 * <p>Vanilla hoppers merge into and split container stacks <em>in place</em> and call
 * {@code setChanged()} afterwards; a machine slot is a resource amount, not an item stack, so
 * in-place edits cannot be tracked reliably. As CraftEngine's drawer does, each machine slot is
 * therefore exposed as two container slots with a maximum stack size of 1: a "place" slot that always
 * looks empty (hoppers insert with {@code setItem}) and a "take" slot showing one item of the
 * contents (hoppers take with {@code removeItem}, or Paper's {@code setItem(slot, empty)}). Every
 * change goes through a machine transaction, one item at a time, with the port's direction, sides and
 * filter applied, and only on the thread that owns the machine (Folia).</p>
 */
final class MachineHopperContainer implements BukkitContainer, WorldlyContainer {
    private static final int[] NONE = new int[0];

    private final MachineBlockEntityController controller;
    private @Nullable Object nms;

    MachineHopperContainer(MachineBlockEntityController controller) {
        this.controller = controller;
    }

    /** The vanilla container handed to hoppers; created once per block entity. */
    Object vanilla() {
        if (this.nms == null) {
            this.nms = CraftEngine.instance().platform().createContainer(this);
        }
        return this.nms;
    }

    // -- slots ------------------------------------------------------------------------------------

    /** One machine slot reachable through a port, with that port. */
    private record Exposed(MachineSlot slot, List<PortDefinition> ports) {
    }

    /** Machine slots reachable through any port, in a stable order; empty if not safe to touch. */
    private List<Exposed> exposed() {
        MachineInstanceImpl machine = machine();
        if (machine == null) return List.of();
        List<Exposed> exposed = new ArrayList<>();
        Map<String, List<PortDefinition>> byStorage = new LinkedHashMap<>();
        for (PortDefinition port : machine.type().ports()) {
            if (port.direction() != PortDirection.DISABLED) byStorage.computeIfAbsent(port.storage(), id -> new ArrayList<>()).add(port);
        }
        byStorage.forEach((storageId, ports) -> {
            if (machine.context().storage(storageId) instanceof MachineStorage storage) {
                for (MachineSlot slot : storage.slots()) exposed.add(new Exposed(slot, ports));
            }
        });
        return exposed;
    }

    private @Nullable MachineInstanceImpl machine() {
        MachineInstanceImpl machine = this.controller.instance();
        CraftEngineMachineHost host = CraftEngineMachineHost.current();
        if (machine == null || !machine.isValid() || host == null || !host.isOwnedByCurrentThread(machine.position())) return null;
        return machine;
    }

    private @Nullable Exposed at(int slot) {
        List<Exposed> exposed = exposed();
        int index = slot >> 1;
        return slot >= 0 && index < exposed.size() ? exposed.get(index) : null;
    }

    private static boolean isTake(int slot) {
        return (slot & 1) == 1;
    }

    private @Nullable PortDefinition port(Exposed exposed, net.momirealms.craftengine.core.util.Direction face, boolean insert) {
        MachineInstanceImpl machine = machine();
        if (machine == null) return null;
        Direction side = Direction.valueOf(face.name().toUpperCase(Locale.ROOT));
        for (PortDefinition port : exposed.ports()) {
            if (insert ? !port.direction().allowsInsertion() : !port.direction().allowsExtraction()) continue;
            for (RelativeSide relative : port.sides()) {
                if (relative.toAbsolute(machine.facing()) == side) return port;
            }
        }
        return null;
    }

    @Override
    public int[] getSlotsForFace(net.momirealms.craftengine.core.util.Direction face) {
        List<Exposed> exposed = exposed();
        if (exposed.isEmpty()) return NONE;
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < exposed.size(); i++) {
            if (port(exposed.get(i), face, true) != null) slots.add(i << 1);
            if (port(exposed.get(i), face, false) != null) slots.add((i << 1) | 1);
        }
        return slots.stream().mapToInt(Integer::intValue).toArray();
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, Item stack, net.momirealms.craftengine.core.util.Direction face) {
        Exposed exposed = at(slot);
        if (exposed == null || isTake(slot) || stack.isEmpty()) return false;
        PortDefinition port = port(exposed, face, true);
        MachineInstanceImpl machine = machine();
        if (port == null || machine == null) return false;
        ResourceStack offered = resource(stack);
        if (!port.filter().test(offered.variant(), machine.services().tags())) return false;
        // Accepts only if the whole offer fits: what a hopper hands over must never be lost.
        try (Transaction simulation = machine.services().transactions().openOuter()) {
            return exposed.slot().insert(offered.variant(), offered.amount(), simulation) == offered.amount();
        }
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, Item stack, net.momirealms.craftengine.core.util.Direction face) {
        Exposed exposed = at(slot);
        if (exposed == null || !isTake(slot) || exposed.slot().isEmpty()) return false;
        PortDefinition port = port(exposed, face, false);
        MachineInstanceImpl machine = machine();
        return port != null && machine != null && port.filter().test(exposed.slot().variant(), machine.services().tags());
    }

    // -- container --------------------------------------------------------------------------------

    @Override
    public int containerSize() {
        return exposed().size() * 2;
    }

    @Override
    public boolean isEmpty() {
        for (Exposed exposed : exposed()) {
            if (!exposed.slot().isEmpty() && item(exposed.slot().variant(), 1) != null) return false;
        }
        return true;
    }

    @Override
    public Item getItem(int slot) {
        Exposed exposed = at(slot);
        if (exposed == null || !isTake(slot) || exposed.slot().isEmpty()) return Item.empty();
        Item item = item(exposed.slot().variant(), 1);
        return item == null ? Item.empty() : item;
    }

    @Override
    public Item removeItem(int slot, int count) {
        Exposed exposed = at(slot);
        if (exposed == null || !isTake(slot) || count <= 0 || exposed.slot().isEmpty()) return Item.empty();
        ResourceVariant variant = exposed.slot().variant();
        Item item = item(variant, 1);
        if (item == null) return Item.empty();
        long taken = change(exposed.slot(), variant, -1);
        return taken == 0 ? Item.empty() : item;
    }

    @Override
    public Item removeItemNoUpdate(int slot) {
        return removeItem(slot, 1);
    }

    /**
     * "The slot now holds {@code item}". A place slot always holds nothing, so anything put there goes
     * into the machine; a take slot shows one item, so the difference to that one item is applied.
     */
    @Override
    public void setItem(int slot, Item item) {
        Exposed exposed = at(slot);
        if (exposed == null) {
            if (!item.isEmpty()) spill(item);
            return;
        }
        if (!isTake(slot)) {
            if (item.isEmpty()) return;
            ResourceStack offered = resource(item);
            long inserted = change(exposed.slot(), offered.variant(), offered.amount());
            if (inserted < offered.amount()) spill(item.copyWithCount((int) (offered.amount() - inserted)));
            return;
        }
        boolean shown = !exposed.slot().isEmpty() && item(exposed.slot().variant(), 1) != null;
        if (item.isEmpty()) {
            if (shown) change(exposed.slot(), exposed.slot().variant(), -1);
            return;
        }
        ResourceStack now = resource(item);
        long shownCount = shown && exposed.slot().variant().equals(now.variant()) ? 1 : 0;
        if (shown && shownCount == 0) {
            // A different item replaces the shown one: take the shown one out first.
            change(exposed.slot(), exposed.slot().variant(), -1);
        }
        long delta = now.amount() - shownCount;
        if (delta > 0) {
            long inserted = change(exposed.slot(), now.variant(), delta);
            if (inserted < delta) spill(item.copyWithCount((int) (delta - inserted)));
        } else if (delta < 0) {
            change(exposed.slot(), now.variant(), delta);
        }
    }

    /** Inserts (positive) or extracts (negative) {@code amount} in one transaction; returns how much moved. */
    private long change(MachineSlot slot, ResourceVariant variant, long amount) {
        MachineInstanceImpl machine = machine();
        if (machine == null) return 0;
        try (Transaction transaction = machine.services().transactions().openOuter()) {
            long moved = amount > 0 ? slot.insert(variant, amount, transaction) : slot.extract(variant, -amount, transaction);
            transaction.commit();
            return moved;
        }
    }

    /** Never loses items: what the machine could not take after all is dropped at the machine. */
    private void spill(Item item) {
        CraftEngineMachineHost host = CraftEngineMachineHost.current();
        MachineInstanceImpl machine = this.controller.instance();
        if (host == null || machine == null) return;
        host.dropResources(machine.position(), List.of(resource(item)));
    }

    @Override
    public int maxStackSize() {
        return 1;
    }

    @Override
    public void setMaxStackSize(int size) {
    }

    @Override
    public void setChanged() {
        // Every change already went through a machine transaction, which marks the machine dirty.
    }

    @Override
    public boolean stillValid(Player player) {
        return false;
    }

    @Override
    public void clearContent() {
        // Not a container players open; nothing in MachineEngine clears machines this way.
    }

    @Override
    public List<Item> contents() {
        List<Item> contents = new ArrayList<>();
        for (int slot = 0; slot < containerSize(); slot++) contents.add(getItem(slot));
        return contents;
    }

    @Override
    public @Nullable WorldPosition position() {
        return null;
    }

    @Override
    public void onOpen(HumanEntity player) {
    }

    @Override
    public void onClose(HumanEntity player) {
    }

    @Override
    public List<HumanEntity> getViewers() {
        return List.of();
    }

    @Override
    public @Nullable InventoryHolder getOwner() {
        return null;
    }

    // -- conversion -------------------------------------------------------------------------------

    private static ResourceStack resource(Item item) {
        return PaperItems.toResource(ItemStackUtils.getBukkitStack(item));
    }

    private static @Nullable Item item(ResourceVariant variant, int amount) {
        ItemStack stack = PaperItems.toItemStack(variant, amount);
        return stack == null ? null : ItemStackUtils.wrap(ItemStackUtils.unwrap(stack));
    }
}
