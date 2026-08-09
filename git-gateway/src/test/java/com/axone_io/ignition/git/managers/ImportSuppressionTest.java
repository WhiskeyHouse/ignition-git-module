package com.axone_io.ignition.git.managers;

import org.junit.After;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ImportSuppressionTest {

    /**
     * Grace used by tests that want the release to happen, without sleeping for the
     * production-sized {@link ImportSuppression#GRACE_MS}.
     */
    private static final long SHORT_GRACE_MS = 50L;

    /** Releases on the calling thread, so tests about the guard itself stay deterministic. */
    private static final long NO_GRACE = 0L;

    @After
    public void tearDown() throws Exception {
        // Let any scheduled release land before draining, so a pending decrement cannot fire
        // during the next test and push the depth negative.
        awaitRelease();
        while (ImportSuppression.isSuppressed()) {
            ImportSuppression.exitForTest();
        }
    }

    /** Waits for suppression to lift, up to a generous bound. */
    private static void awaitRelease() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (ImportSuppression.isSuppressed() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5L);
        }
    }

    @Test
    public void notSuppressedByDefault() {
        assertFalse(ImportSuppression.isSuppressed());
    }

    @Test
    public void suppressedInsideRun() {
        AtomicBoolean seen = new AtomicBoolean();
        ImportSuppression.run(() -> seen.set(ImportSuppression.isSuppressed()), NO_GRACE);
        assertTrue(seen.get());
    }

    @Test
    public void suppressionLiftedAfterRun() {
        ImportSuppression.run(() -> { }, NO_GRACE);
        assertFalse(ImportSuppression.isSuppressed());
    }

    @Test
    public void suppressionLiftedAfterThrowingBody() {
        try {
            ImportSuppression.run(() -> {
                throw new IllegalStateException("import blew up");
            }, NO_GRACE);
            fail("expected the body's exception to propagate");
        } catch (IllegalStateException expected) {
            // propagation is the point — callers must still see import failures
        }
        assertFalse(ImportSuppression.isSuppressed());
    }

    @Test
    public void nestedRunsStaySuppressedUntilOutermostExits() {
        AtomicBoolean innerExitStillSuppressed = new AtomicBoolean();
        ImportSuppression.run(() -> {
            ImportSuppression.run(() -> { }, NO_GRACE);
            innerExitStillSuppressed.set(ImportSuppression.isSuppressed());
        }, NO_GRACE);
        assertTrue("inner exit must not lift the outer suppression",
                innerExitStillSuppressed.get());
        assertFalse(ImportSuppression.isSuppressed());
    }

    @Test
    public void suppressionIsGlobal_notPerThread() {
        // The watcher fires on Ignition's resource threads, not the importing thread,
        // so a ThreadLocal guard would not suppress anything.
        AtomicBoolean seenFromOtherThread = new AtomicBoolean();
        ImportSuppression.run(() -> {
            Thread t = new Thread(() -> seenFromOtherThread.set(ImportSuppression.isSuppressed()));
            t.start();
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, NO_GRACE);
        assertTrue(seenFromOtherThread.get());
    }

    // ---- grace period ----

    @Test
    public void suppressionOutlivesTheImportCall() {
        // Resource events for an import can be dispatched after the import call returns;
        // releasing at the closing brace would let them arm an export of what was just pulled.
        ImportSuppression.run(() -> { }, SHORT_GRACE_MS);
        assertTrue("suppression must still be asserted immediately after run() returns",
                ImportSuppression.isSuppressed());
    }

    @Test
    public void suppressionIsReleasedAfterTheGracePeriod() throws Exception {
        ImportSuppression.run(() -> { }, SHORT_GRACE_MS);
        awaitRelease();
        assertFalse(ImportSuppression.isSuppressed());
    }

    @Test
    public void suppressionOutlivesAThrowingImport() throws Exception {
        // A timed-out or failed import is exactly when stray events are most likely.
        try {
            ImportSuppression.run(() -> {
                throw new IllegalStateException("import blew up");
            }, SHORT_GRACE_MS);
            fail("expected the body's exception to propagate");
        } catch (IllegalStateException expected) {
            // propagation must survive the grace period change
        }
        assertTrue(ImportSuppression.isSuppressed());
        awaitRelease();
        assertFalse(ImportSuppression.isSuppressed());
    }

    @Test
    public void nestedGraceRunsBothHoldTheGuard() throws Exception {
        ImportSuppression.run(() -> ImportSuppression.run(() -> { }, SHORT_GRACE_MS),
                SHORT_GRACE_MS);
        assertTrue(ImportSuppression.isSuppressed());
        awaitRelease();
        assertFalse("both scheduled releases must land, leaving no residual depth",
                ImportSuppression.isSuppressed());
    }

    @Test
    public void graceIsTwiceTheWatcherDebounce() {
        // The point of the grace is that a burst arriving as the import returns is still
        // suppressed when its debounce timer fires.
        assertEquals(2 * TagChangeWatcher.DEBOUNCE_MS, ImportSuppression.GRACE_MS);
    }
}
