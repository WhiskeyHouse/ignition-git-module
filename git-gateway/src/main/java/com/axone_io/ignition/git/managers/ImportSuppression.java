package com.axone_io.ignition.git.managers;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Marks spans during which this module is itself writing tags into the gateway's tag
 * providers, so TagChangeWatcher can ignore the resulting resource events.
 *
 * <p>Without this, a pull would import tags, the watcher would see the changes, export
 * them straight back to the working tree, and prompt the user to commit what they had
 * just pulled.</p>
 *
 * <p>Deliberately global rather than thread-local: the resource events arrive on
 * Ignition's own threads, not on the thread doing the import.</p>
 */
public final class ImportSuppression {

    private static final AtomicInteger depth = new AtomicInteger();

    private ImportSuppression() {
    }

    public static boolean isSuppressed() {
        return depth.get() > 0;
    }

    /** Runs {@code body} with suppression active. Exceptions propagate; the guard always lifts. */
    public static void run(Runnable body) {
        depth.incrementAndGet();
        try {
            body.run();
        } finally {
            depth.decrementAndGet();
        }
    }

    /** Test-only escape hatch so a failing test cannot leak suppression into the next one. */
    static void exitForTest() {
        depth.decrementAndGet();
    }
}
