package fr.robie.machineengine.test;

import fr.robie.machineengine.api.resource.ResourceVariant;
import fr.robie.machineengine.api.resource.match.ResourcePredicate;
import fr.robie.machineengine.api.transfer.Transaction;
import fr.robie.machineengine.api.transfer.TransactionManager;
import fr.robie.machineengine.api.transfer.TransferResult;
import fr.robie.machineengine.api.transfer.Transfers;
import fr.robie.machineengine.api.transfer.storage.CombinedResourceHandler;
import fr.robie.machineengine.api.transfer.storage.SingleVariantStorage;
import fr.robie.machineengine.api.resource.tag.ResourceTags;
import org.junit.jupiter.api.Test;

import java.util.List;

import static fr.robie.machineengine.test.Fixtures.EU;
import static fr.robie.machineengine.test.Fixtures.FE;
import static fr.robie.machineengine.test.Fixtures.OIL;
import static fr.robie.machineengine.test.Fixtures.STEAM;
import static fr.robie.machineengine.test.Fixtures.TEMPERATURE;
import static fr.robie.machineengine.test.Fixtures.WATER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StorageTest {
    private final TransactionManager transactions = TestEngine.builder().start().transactions();

    @Test
    void capacityFilterAndDirectionsAreRespected() {
        SingleVariantStorage tank = SingleVariantStorage.builder(1000).filter(ResourcePredicate.type(WATER).bind(ResourceTags.EMPTY)).build();
        SingleVariantStorage sink = SingleVariantStorage.builder(100).insertOnly().build();
        try (Transaction tx = this.transactions.openOuter()) {
            assertEquals(1000, tank.insert(WATER.variant(), 1500, tx));
            assertEquals(0, tank.insert(WATER.variant(), 1, tx), "full");
            assertEquals(0, tank.insert(OIL.variant(), 10, tx), "filtered");
            assertEquals(50, sink.insert(FE.variant(), 50, tx));
            assertEquals(0, sink.extract(FE.variant(), 50, tx), "insert-only");
            assertThrows(IllegalArgumentException.class, () -> tank.insert(WATER.variant(), -1, tx));
            tx.commit();
        }
    }

    @Test
    void differentTypesNeverShareASlot() {
        SingleVariantStorage battery = SingleVariantStorage.of(1000);
        try (Transaction tx = this.transactions.openOuter()) {
            battery.insert(FE.variant(), 10, tx);
            assertEquals(0, battery.insert(EU.variant(), 10, tx), "no implicit conversion or mixing");
            assertEquals(0, battery.extract(EU.variant(), 10, tx));
            tx.commit();
        }
    }

    @Test
    void mergePolicyCombinesComponentsDeterministically() {
        SingleVariantStorage boiler = SingleVariantStorage.of(1000);
        ResourceVariant cool = STEAM.variant().with(TEMPERATURE, 300L);
        ResourceVariant hot = STEAM.variant().with(TEMPERATURE, 400L);
        try (Transaction tx = this.transactions.openOuter()) {
            boiler.insert(cool, 300, tx);
            assertEquals(100, boiler.insert(hot, 100, tx));
            tx.commit();
        }
        assertEquals(400, boiler.amount());
        assertEquals(STEAM.variant().with(TEMPERATURE, 325L), boiler.variant());

        try (Transaction tx = this.transactions.openOuter()) {
            assertEquals(0, boiler.extract(cool, 10, tx), "extraction is by exact variant");
            assertEquals(0, boiler.insert(STEAM.variant(), 10, tx), "a missing component does not merge with a present one");
        }
    }

    @Test
    void combinedHandlersFillExistingStacksFirst() {
        CombinedResourceHandler inventory = CombinedResourceHandler.slots(3, 64);
        try (Transaction tx = this.transactions.openOuter()) {
            inventory.parts().get(2).insert(FE.variant(), 10, tx);
            assertEquals(60, inventory.insert(FE.variant(), 60, tx));
            tx.commit();
        }
        List<Long> amounts = inventory.views().stream().map(view -> view.amount()).toList();
        assertEquals(List.of(6L, 0L, 64L), amounts, "slot 2 fills up before empty slot 0 is used");
    }

    @Test
    void transfersReportCompletePartialAndRejected() {
        SingleVariantStorage source = SingleVariantStorage.of(1000);
        SingleVariantStorage target = SingleVariantStorage.of(300);
        try (Transaction tx = this.transactions.openOuter()) {
            source.insert(WATER.variant(), 500, tx);

            TransferResult complete = Transfers.move(source, target, variant -> true, 200, tx);
            assertEquals(TransferResult.Status.COMPLETE, complete.status());
            assertNull(complete.reason());

            TransferResult partial = Transfers.move(source, target, variant -> true, 200, tx);
            assertEquals(TransferResult.Status.PARTIAL, partial.status());
            assertEquals(100, partial.moved());

            TransferResult rejected = Transfers.move(source, target, variant -> true, 50, tx);
            assertEquals(TransferResult.Status.REJECTED, rejected.status());
            tx.commit();
        }
        assertEquals(200, source.amount());
        assertEquals(300, target.amount());
    }

    @Test
    void simulationChangesNothing() {
        SingleVariantStorage target = SingleVariantStorage.of(100);
        try (Transaction tx = this.transactions.openOuter()) {
            assertEquals(100, Transfers.simulateInsert(target, OIL.variant(), 250, tx));
            assertEquals(0, target.amount());
            tx.commit();
        }
        assertEquals(0, target.amount());
    }
}
