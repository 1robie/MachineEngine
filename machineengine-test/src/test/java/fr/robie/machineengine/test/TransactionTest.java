package fr.robie.machineengine.test;

import fr.robie.machineengine.api.transfer.Transaction;
import fr.robie.machineengine.api.transfer.TransactionManager;
import fr.robie.machineengine.api.transfer.storage.SingleVariantStorage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static fr.robie.machineengine.test.Fixtures.FE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionTest {
    private final TransactionManager transactions = TestEngine.builder().start().transactions();

    @Test
    void commitKeepsChangesAndAbortRestoresThem() {
        SingleVariantStorage battery = SingleVariantStorage.of(1000);
        try (Transaction tx = this.transactions.openOuter()) {
            assertEquals(400, battery.insert(FE.variant(), 400, tx));
            tx.commit();
        }
        try (Transaction tx = this.transactions.openOuter()) {
            assertEquals(400, battery.extract(FE.variant(), 1000, tx));
            assertEquals(0, battery.amount());
            // closed without commit: aborted
        }
        assertEquals(400, battery.amount());
        assertEquals(FE.variant(), battery.variant());
    }

    @Test
    void abortingANestedTransactionOnlyUndoesItsOwnChanges() {
        SingleVariantStorage battery = SingleVariantStorage.of(1000);
        try (Transaction outer = this.transactions.openOuter()) {
            battery.insert(FE.variant(), 100, outer);
            try (Transaction nested = outer.openNested()) {
                battery.insert(FE.variant(), 200, nested);
                assertEquals(300, battery.amount());
                nested.abort();
            }
            assertEquals(100, battery.amount());
            try (Transaction nested = outer.openNested()) {
                battery.insert(FE.variant(), 50, nested);
                nested.commit();
            }
            assertEquals(150, battery.amount());
            outer.commit();
        }
        assertEquals(150, battery.amount());
    }

    @Test
    void abortingTheOuterTransactionUndoesCommittedNestedChanges() {
        SingleVariantStorage battery = SingleVariantStorage.of(1000);
        try (Transaction outer = this.transactions.openOuter()) {
            try (Transaction nested = outer.openNested()) {
                try (Transaction deeper = nested.openNested()) {
                    battery.insert(FE.variant(), 500, deeper);
                    deeper.commit();
                }
                nested.commit();
            }
            assertEquals(500, battery.amount());
        }
        assertEquals(0, battery.amount());
        assertNull(battery.variant());
    }

    @Test
    void commitActionsRunOnlyAfterTheOuterCommit() {
        List<String> log = new ArrayList<>();
        try (Transaction outer = this.transactions.openOuter()) {
            try (Transaction nested = outer.openNested()) {
                nested.onCommit(() -> log.add("kept"));
                nested.commit();
            }
            try (Transaction nested = outer.openNested()) {
                nested.onCommit(() -> log.add("dropped"));
                nested.abort();
            }
            assertTrue(log.isEmpty(), "nothing runs before the outer commit");
            outer.commit();
        }
        assertEquals(List.of("kept"), log);

        try (Transaction aborted = this.transactions.openOuter()) {
            aborted.onCommit(() -> log.add("never"));
        }
        assertEquals(List.of("kept"), log);
    }

    @Test
    void finalCommitIsReportedToParticipantsOnce() {
        List<Long> committed = new ArrayList<>();
        SingleVariantStorage battery = new SingleVariantStorage(SingleVariantStorage.builder(1000)) {
            @Override
            protected void onFinalCommit() {
                committed.add(amount());
            }
        };
        try (Transaction outer = this.transactions.openOuter()) {
            battery.insert(FE.variant(), 10, outer);
            try (Transaction nested = outer.openNested()) {
                battery.insert(FE.variant(), 20, nested);
                nested.commit();
            }
            outer.commit();
        }
        assertEquals(List.of(30L), committed);
    }

    @Test
    void onlyOneOuterTransactionPerThread() {
        try (Transaction outer = this.transactions.openOuter()) {
            assertTrue(this.transactions.isOpen());
            assertThrows(IllegalStateException.class, this.transactions::openOuter);
            assertEquals(0, outer.nestingDepth());
        }
        assertFalse(this.transactions.isOpen());
    }

    @Test
    void theInnermostTransactionMustCloseFirst() {
        try (Transaction outer = this.transactions.openOuter()) {
            Transaction nested = outer.openNested();
            assertThrows(IllegalStateException.class, outer::commit);
            assertThrows(IllegalStateException.class, outer::openNested);
            nested.commit();
            assertThrows(IllegalStateException.class, nested::commit, "closed transactions cannot be reused");
            outer.commit();
        }
    }

    @Test
    void transactionsAreConfinedToTheirThread() throws InterruptedException {
        try (Transaction outer = this.transactions.openOuter()) {
            CompletableFuture<Void> elsewhere = CompletableFuture.runAsync(() -> SingleVariantStorage.of(10).insert(FE.variant(), 1, outer));
            ExecutionException failure = assertThrows(ExecutionException.class, elsewhere::get);
            assertInstanceOf(IllegalStateException.class, failure.getCause());

            // Another thread has its own, independent transaction stack.
            assertFalse(CompletableFuture.supplyAsync(this.transactions::isOpen).join());
        }
    }
}
