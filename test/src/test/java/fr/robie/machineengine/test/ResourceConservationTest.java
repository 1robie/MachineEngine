package fr.robie.machineengine.test;

import fr.robie.machineengine.api.resource.ResourceType;
import fr.robie.machineengine.api.resource.ResourceVariant;
import fr.robie.machineengine.api.transfer.ResourceHandler;
import fr.robie.machineengine.api.transfer.StorageView;
import fr.robie.machineengine.api.transfer.Transaction;
import fr.robie.machineengine.api.transfer.TransactionContext;
import fr.robie.machineengine.api.transfer.TransactionManager;
import fr.robie.machineengine.api.transfer.Transfers;
import fr.robie.machineengine.api.transfer.storage.CombinedResourceHandler;
import fr.robie.machineengine.api.transfer.storage.SingleVariantStorage;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static fr.robie.machineengine.test.Fixtures.FE;
import static fr.robie.machineengine.test.Fixtures.OIL;
import static fr.robie.machineengine.test.Fixtures.STEAM;
import static fr.robie.machineengine.test.Fixtures.WATER;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Property test for the §10.3 invariant: whatever sequence of transfers, nested transactions,
 * commits and aborts happens, transfers never create or destroy resources, and an aborted outer
 * transaction restores the exact previous state.
 */
class ResourceConservationTest {
    private static final List<ResourceType> TYPES = List.of(FE, WATER, STEAM, OIL);

    private final TransactionManager transactions = TestEngine.builder().start().transactions();

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 42, 1337, 2024, 99_991, 123_456_789})
    void transfersConserveResources(long seed) {
        Random random = new Random(seed);
        List<ResourceHandler> handlers = List.of(
                SingleVariantStorage.of(1_000),
                SingleVariantStorage.of(250),
                SingleVariantStorage.builder(500).insertOnly().build(),
                SingleVariantStorage.builder(500).extractOnly().build(),
                CombinedResourceHandler.slots(4, 100),
                CombinedResourceHandler.slots(2, 10_000));
        fill(handlers, random);
        Map<ResourceType, Long> expected = ResourceAssertions.totals(handlers);

        for (int round = 0; round < 200; round++) {
            List<StorageView> before = ResourceAssertions.snapshot(handlers);
            boolean commit = random.nextInt(3) != 0;
            try (Transaction outer = this.transactions.openOuter()) {
                randomOperations(handlers, random, outer, 0);
                ResourceAssertions.assertConserved(expected, handlers);
                if (commit) outer.commit();
            }
            ResourceAssertions.assertConserved(expected, handlers);
            if (!commit) {
                assertEquals(before, ResourceAssertions.snapshot(handlers), "an aborted transaction restores the exact state");
            }
        }
    }

    private void randomOperations(List<ResourceHandler> handlers, Random random, TransactionContext transaction, int depth) {
        int operations = 1 + random.nextInt(5);
        for (int i = 0; i < operations; i++) {
            if (depth < 4 && random.nextInt(3) == 0) {
                try (Transaction nested = transaction.openNested()) {
                    randomOperations(handlers, random, nested, depth + 1);
                    if (random.nextBoolean()) nested.commit();
                }
            } else {
                ResourceHandler source = handlers.get(random.nextInt(handlers.size()));
                ResourceHandler target = handlers.get(random.nextInt(handlers.size()));
                ResourceType type = TYPES.get(random.nextInt(TYPES.size()));
                long amount = random.nextInt(400);
                Transfers.move(source, target, variant -> variant.type() == type || random.nextInt(4) == 0, amount, transaction);
            }
        }
    }

    private void fill(List<ResourceHandler> handlers, Random random) {
        for (ResourceHandler handler : handlers) {
            List<SingleVariantStorage> slots = handler instanceof CombinedResourceHandler combined
                    ? combined.parts().stream().map(SingleVariantStorage.class::cast).toList()
                    : List.of((SingleVariantStorage) handler);
            for (SingleVariantStorage slot : slots) {
                ResourceVariant variant = TYPES.get(random.nextInt(TYPES.size())).variant();
                slot.load(variant, random.nextLong(slot.capacity() + 1));
            }
        }
    }
}
