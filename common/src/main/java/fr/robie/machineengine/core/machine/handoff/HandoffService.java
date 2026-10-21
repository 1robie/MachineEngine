package fr.robie.machineengine.core.machine.handoff;

import fr.robie.machineengine.api.capability.Capabilities;
import fr.robie.machineengine.api.machine.PushResult;
import fr.robie.machineengine.api.machine.WakeReason;
import fr.robie.machineengine.api.resource.ResourceStack;
import fr.robie.machineengine.api.resource.ResourceVariant;
import fr.robie.machineengine.api.transfer.ResourceHandler;
import fr.robie.machineengine.api.transfer.StorageView;
import fr.robie.machineengine.api.transfer.Transaction;
import fr.robie.machineengine.api.transfer.TransactionContext;
import fr.robie.machineengine.core.machine.MachineHost;
import fr.robie.machineengine.core.machine.MachineInstanceImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Cross-region transfers by asynchronous handoff with escrow (D1, ARCHITECTURE.md §16.3).
 *
 * <ol>
 *   <li>Source (its thread): move resources from its storage into escrow, in one transaction.</li>
 *   <li>Target (its thread): insert what fits, record the transfer id with the result, in one
 *       transaction; a re-delivered id gets the recorded result instead.</li>
 *   <li>Source: remove the accepted part from escrow and return the rest to its storage.</li>
 * </ol>
 *
 * <p>Escrow is persisted with the source and re-sent with the same id when the source loads, so no
 * path loses or duplicates resources except the documented crash window (the target's chunk not
 * saved after the source's chunk recorded the acknowledgement).</p>
 */
public final class HandoffService {
    /** Ticks a route rests after the target was missing (unloaded chunk, no machine, no port). */
    public static final long MISSING_TARGET_BACKOFF = 20;
    /** Ticks a route rests after the target accepted nothing (full). */
    public static final long FULL_TARGET_BACKOFF = 5;

    /** Escrow of removed machines whose handoffs were still in flight, by transfer id. */
    private final Map<TransferId, Escrow> orphaned = new ConcurrentHashMap<>();

    // -- source side ---------------------------------------------------------------------------

    public @NotNull PushResult send(@NotNull MachineInstanceImpl source, @NotNull Route route, @NotNull String storageId,
                                    @NotNull Predicate<ResourceVariant> filter, long maxAmount) {
        ResourceHandler storage = source.context().storage(storageId);
        try (Transaction transaction = openTransaction(source)) {
            List<ResourceStack> taken = new ArrayList<>();
            long remaining = maxAmount;
            for (StorageView view : storage.views()) {
                if (remaining == 0) break;
                if (view.isEmpty() || !filter.test(view.variant())) continue;
                long extracted = storage.extract(view.variant(), Math.min(remaining, view.amount()), transaction);
                if (extracted > 0) {
                    taken.add(view.variant().stack(extracted));
                    remaining -= extracted;
                }
            }
            if (taken.isEmpty()) return new PushResult(PushResult.Kind.HANDOFF, 0);
            Escrow escrow = new Escrow(new TransferId(source.position(), nextSequence(source)), route, storageId, taken, false);
            Map<Route, Escrow> updated = new LinkedHashMap<>(escrowOf(source));
            updated.put(route, escrow);
            setEscrow(source, updated, transaction);
            MachineHost host = source.hostForHandoff();
            transaction.onCommit(() -> dispatch(host, escrow));
            transaction.commit();
            return new PushResult(PushResult.Kind.HANDOFF, maxAmount - remaining);
        }
    }

    /** On load: re-sends unanswered handoffs (same id) and retries returning remainders. */
    public void resume(@NotNull MachineInstanceImpl source) {
        for (Escrow escrow : List.copyOf(escrowOf(source).values())) {
            if (escrow.returning()) {
                retryReturn(source, escrow.route());
            } else {
                dispatch(source.hostForHandoff(), escrow);
            }
        }
    }

    /** Tries again to put a remainder back into the source storage. */
    public void retryReturn(@NotNull MachineInstanceImpl source, @NotNull Route route) {
        Escrow escrow = escrowOf(source).get(route);
        if (escrow == null || !escrow.returning()) return;
        try (Transaction transaction = openTransaction(source)) {
            settle(source, escrow, escrow.stacks(), transaction);
            transaction.commit();
        }
    }

    /** Called when {@code source} is removed while handoffs are in flight. */
    public void removed(@NotNull MachineInstanceImpl source) {
        for (Escrow escrow : escrowOf(source).values()) {
            if (!escrow.returning()) this.orphaned.put(escrow.id(), escrow);
        }
    }

    private void dispatch(MachineHost host, Escrow escrow) {
        TransferId id = escrow.id();
        Route route = escrow.route();
        List<ResourceStack> stacks = escrow.stacks();
        host.post(route.target(), () -> receive(host, id, route, stacks));
    }

    private void acknowledge(MachineHost host, TransferId id, Route route, List<Long> accepted, List<ResourceStack> stacks,
                             boolean targetMissing) {
        // The source was removed, maybe replaced since by another machine: drop what was not accepted.
        Escrow orphan = this.orphaned.remove(id);
        if (orphan != null) {
            List<ResourceStack> remainder = remainder(orphan.stacks(), accepted);
            if (!remainder.isEmpty()) host.dropResources(id.source(), remainder);
            return;
        }
        MachineInstanceImpl source = host.machineAt(id.source());
        // An unloaded source: its escrow is persisted and re-sent when it loads.
        if (source == null || !source.isValid()) return;
        Escrow escrow = escrowOf(source).get(route);
        if (escrow == null || !escrow.id().equals(id) || escrow.returning()) return; // stale or duplicate answer
        try (Transaction transaction = openTransaction(source)) {
            settle(source, escrow, remainder(stacks, accepted), transaction);
            transaction.commit();
        }
        if (accepted.stream().allMatch(amount -> amount == 0)) {
            // Do not ping-pong messages every tick with a target that cannot take anything (§33).
            source.backOff(route, targetMissing, targetMissing ? MISSING_TARGET_BACKOFF : FULL_TARGET_BACKOFF);
        } else {
            source.wake(WakeReason.OUTPUT_AVAILABLE);
        }
    }

    /** Returns {@code remainder} to the source storage; whatever does not fit stays in escrow. */
    private void settle(MachineInstanceImpl source, Escrow escrow, List<ResourceStack> remainder, TransactionContext transaction) {
        ResourceHandler storage = source.context().storage(escrow.storage());
        List<ResourceStack> left = new ArrayList<>();
        for (ResourceStack stack : remainder) {
            long returned = storage.insert(stack.variant(), stack.amount(), transaction);
            if (returned < stack.amount()) left.add(stack.withAmount(stack.amount() - returned));
        }
        Map<Route, Escrow> updated = new LinkedHashMap<>(escrowOf(source));
        if (left.isEmpty()) updated.remove(escrow.route()); else updated.put(escrow.route(), escrow.returning(left));
        setEscrow(source, updated, transaction);
    }

    // -- target side ---------------------------------------------------------------------------

    private void receive(MachineHost host, TransferId id, Route route, List<ResourceStack> stacks) {
        MachineInstanceImpl target = host.machineAt(route.target());
        List<Long> accepted;
        boolean missing = target == null || target.capability(Capabilities.RESOURCE_HANDLER, route.side()).isEmpty();
        if (target == null) {
            accepted = stacks.stream().map(stack -> 0L).toList();
        } else {
            List<Long> previous = previousAnswer(dedupeOf(target), id, stacks.size());
            accepted = previous != null ? previous : insert(target, id, route, stacks);
        }
        List<Long> answer = List.copyOf(accepted);
        host.post(id.source(), () -> acknowledge(host, id, route, answer, stacks, missing));
    }

    private List<Long> insert(MachineInstanceImpl target, TransferId id, Route route, List<ResourceStack> stacks) {
        ResourceHandler handler = target.capability(Capabilities.RESOURCE_HANDLER, route.side()).orElse(null);
        try (Transaction transaction = openTransaction(target)) {
            List<Long> accepted = new ArrayList<>(stacks.size());
            for (ResourceStack stack : stacks) {
                accepted.add(handler == null ? 0 : handler.insert(stack.variant(), stack.amount(), transaction));
            }
            List<DedupeEntry> entries = new ArrayList<>(dedupeOf(target));
            entries.add(new DedupeEntry(id, accepted));
            // Bounded per source, not overall, so a busy neighbour never pushes out another's ids.
            long fromSource = entries.stream().filter(entry -> entry.id().source().equals(id.source())).count();
            for (Iterator<DedupeEntry> it = entries.iterator(); fromSource > DedupeEntry.PER_SOURCE && it.hasNext(); ) {
                if (it.next().id().source().equals(id.source())) {
                    it.remove();
                    fromSource--;
                }
            }
            setDedupe(target, List.copyOf(entries), transaction);
            transaction.commit();
            return accepted;
        }
    }

    /**
     * The recorded answer for a re-delivered id, or {@code null} for a new transfer. A source has at most
     * one handoff in flight per route and its sequence only grows, so an id older than the latest recorded
     * from that source is a stale copy: answered with nothing accepted, never inserted again. The source
     * ignores that answer unless it still holds that escrow, and then nothing was inserted for it here.
     */
    private static @Nullable List<Long> previousAnswer(List<DedupeEntry> dedupe, TransferId id, int stacks) {
        long latest = Long.MIN_VALUE;
        for (DedupeEntry entry : dedupe) {
            if (!entry.id().source().equals(id.source())) continue;
            if (entry.id().sequence() == id.sequence()) return entry.accepted();
            latest = Math.max(latest, entry.id().sequence());
        }
        return id.sequence() < latest ? Collections.nCopies(stacks, 0L) : null;
    }

    /**
     * The first sequence of a machine created or loaded at {@code gameTime}. A machine sends at most one
     * handoff per route (6) per tick, so this stays above every id an earlier machine at the same
     * position used: targets never take a new machine's transfers for its predecessor's.
     */
    public static long firstSequence(long gameTime) {
        return gameTime << 16;
    }

    // -- helpers -------------------------------------------------------------------------------

    private static List<ResourceStack> remainder(List<ResourceStack> stacks, List<Long> accepted) {
        List<ResourceStack> remainder = new ArrayList<>();
        for (int i = 0; i < stacks.size(); i++) {
            long left = stacks.get(i).amount() - (i < accepted.size() ? accepted.get(i) : 0);
            if (left > 0) remainder.add(stacks.get(i).withAmount(left));
        }
        return remainder;
    }

    private static Transaction openTransaction(MachineInstanceImpl machine) {
        return machine.openHandoffTransaction();
    }

    private static Map<Route, Escrow> escrowOf(MachineInstanceImpl machine) {
        return Objects.requireNonNull(machine.escrowForHandoff());
    }

    private static void setEscrow(MachineInstanceImpl machine, Map<Route, Escrow> escrow, TransactionContext transaction) {
        machine.setEscrowForHandoff(Map.copyOf(escrow), transaction);
    }

    private static List<DedupeEntry> dedupeOf(MachineInstanceImpl machine) {
        return Objects.requireNonNull(machine.dedupeForHandoff());
    }

    private static void setDedupe(MachineInstanceImpl machine, List<DedupeEntry> dedupe, TransactionContext transaction) {
        machine.setDedupeForHandoff(dedupe, transaction);
    }

    private static long nextSequence(MachineInstanceImpl machine) {
        return machine.nextSequenceForHandoff();
    }
}
