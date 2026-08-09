package com.axone_io.ignition.git.managers;

import org.junit.After;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ImportSuppressionTest {

    @After
    public void tearDown() {
        // Guard against a failing test leaking suppression into the next one.
        while (ImportSuppression.isSuppressed()) {
            ImportSuppression.exitForTest();
        }
    }

    @Test
    public void notSuppressedByDefault() {
        assertFalse(ImportSuppression.isSuppressed());
    }

    @Test
    public void suppressedInsideRun() {
        AtomicBoolean seen = new AtomicBoolean();
        ImportSuppression.run(() -> seen.set(ImportSuppression.isSuppressed()));
        assertTrue(seen.get());
    }

    @Test
    public void suppressionLiftedAfterRun() {
        ImportSuppression.run(() -> { });
        assertFalse(ImportSuppression.isSuppressed());
    }

    @Test
    public void suppressionLiftedAfterThrowingBody() {
        try {
            ImportSuppression.run(() -> {
                throw new IllegalStateException("import blew up");
            });
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
            ImportSuppression.run(() -> { });
            innerExitStillSuppressed.set(ImportSuppression.isSuppressed());
        });
        assertTrue("inner exit must not lift the outer suppression",
                innerExitStillSuppressed.get());
        assertFalse(ImportSuppression.isSuppressed());
    }

    @Test
    public void suppressionIsGlobal_notPerThread() throws Exception {
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
        });
        assertTrue(seenFromOtherThread.get());
    }
}
