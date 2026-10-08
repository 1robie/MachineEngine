package fr.robie.machineengine.paper;

import com.tcoded.folialib.wrapper.task.WrappedTask;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FoliaLibSchedulerTest {

    /** What FoliaLib hands to a task when it runs. */
    private static final class FakeWrappedTask implements WrappedTask {
        boolean cancelled;

        @Override
        public void cancel() {
            this.cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return this.cancelled;
        }

        @Override
        public Plugin getOwningPlugin() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isAsync() {
            return false;
        }
    }

    @Test
    void entityTaskRunsUntilCancelled() {
        AtomicInteger runs = new AtomicInteger();
        FoliaLibScheduler.EntityTask task = new FoliaLibScheduler.EntityTask(runs::incrementAndGet, null);
        FakeWrappedTask scheduled = new FakeWrappedTask();

        task.run(scheduled);
        task.run(scheduled);
        assertEquals(2, runs.get());

        task.cancel();
        assertTrue(task.isCancelled());
        assertTrue(scheduled.cancelled, "once FoliaLib's task is known, cancelling cancels it at once");
        task.run(scheduled);
        assertEquals(2, runs.get(), "a run already queued must not run after cancel");
    }

    @Test
    void entityTaskCancelledBeforeItsFirstRunNeverRuns() {
        AtomicInteger runs = new AtomicInteger();
        FoliaLibScheduler.EntityTask task = new FoliaLibScheduler.EntityTask(runs::incrementAndGet, null);
        task.cancel();

        FakeWrappedTask scheduled = new FakeWrappedTask();
        task.run(scheduled);

        assertEquals(0, runs.get());
        assertTrue(scheduled.cancelled, "the first run cancels FoliaLib's task, which was not known at cancel time");
    }

    @Test
    void retiredRunsOnlyWhenNotCancelled() {
        AtomicInteger retired = new AtomicInteger();
        FoliaLibScheduler.EntityTask live = new FoliaLibScheduler.EntityTask(() -> {
        }, retired::incrementAndGet);
        live.retire();
        assertEquals(1, retired.get());

        FoliaLibScheduler.EntityTask cancelled = new FoliaLibScheduler.EntityTask(() -> {
        }, retired::incrementAndGet);
        cancelled.cancel();
        cancelled.retire();
        assertEquals(1, retired.get(), "a cancelled task's retired callback must not run");
        assertFalse(new FoliaLibScheduler.EntityTask(() -> {
        }, null).isCancelled());
        new FoliaLibScheduler.EntityTask(() -> {
        }, null).retire(); // no retired callback: nothing to run
    }

    @Test
    void delaysAndPeriodsAreAtLeastOneTick() {
        assertEquals(1, FoliaLibScheduler.ticks(0));
        assertEquals(1, FoliaLibScheduler.ticks(-5));
        assertEquals(20, FoliaLibScheduler.ticks(20));
    }
}
