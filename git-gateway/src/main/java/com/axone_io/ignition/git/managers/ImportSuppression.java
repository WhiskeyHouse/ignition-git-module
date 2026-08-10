package com.axone_io.ignition.git.managers;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
 *
 * <p>Suppression outlives the import call by {@link #GRACE_MS}. An import returning means
 * the tag config was <em>applied</em>, not that the config collection has finished
 * dispatching the matching {@code resourcesModified} callbacks; events landing in that
 * window would otherwise arm the debounce and export the tags straight back out. The grace
 * period is twice the watcher's debounce, so a burst arriving the instant the import
 * returns is still suppressed when its timer fires.</p>
 */
public final class ImportSuppression {

    /** How long suppression stays asserted after the import body returns. */
    public static final long GRACE_MS = 2 * TagChangeWatcher.DEBOUNCE_MS;

    private static final AtomicInteger depth = new AtomicInteger();

    /**
     * Not final: {@link #shutdown()} retires this executor's thread so it stops pinning the
     * module's classloader across a hot-deploy, and tests need to recreate it afterward (via
     * {@link #resetForTest()}) since this guard is a static singleton for the life of the
     * classloader.
     */
    private static volatile ScheduledExecutorService releaser = newReleaser();

    private static ScheduledExecutorService newReleaser() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "git-import-suppression-release");
            // Daemon: a pending release must never hold up gateway shutdown.
            t.setDaemon(true);
            return t;
        });
    }

    private ImportSuppression() {
    }

    /**
     * Retires the release thread. The thread is created lazily, on the first {@code schedule()}
     * call (i.e. the first pull), so this is a no-op if nothing has ever been suppressed.
     *
     * <p>Safe to call more than once, and safe to call from {@code GatewayHook.shutdown()} even
     * though this guard's state is a static singleton, not an instance owned by the hook: a
     * module restart loads a fresh classloader with a fresh {@code ImportSuppression} class, so
     * "shut down forever" here only ever means "for the remaining life of the old classloader,"
     * which is what we want.</p>
     *
     * <p>Any release still in flight is abandoned, but the guard cannot be stranded asserted: a
     * {@code run()} that has not yet released when this fires — or one that starts afterward —
     * still lifts its own depth via the {@link java.util.concurrent.RejectedExecutionException}
     * fallback in {@link #scheduleRelease}.</p>
     */
    public static void shutdown() {
        releaser.shutdownNow();
    }

    /** Test-only: recreates the release executor after a test has called {@link #shutdown()}. */
    static void resetForTest() {
        releaser = newReleaser();
    }

    public static boolean isSuppressed() {
        return depth.get() > 0;
    }

    /** Runs {@code body} with suppression active. Exceptions propagate; the guard always lifts. */
    public static void run(Runnable body) {
        run(body, GRACE_MS);
    }

    /**
     * Runs {@code body} with suppression active, releasing the guard {@code graceMillis}
     * after it returns.
     *
     * <p>Package-private with an injectable delay so tests can exercise the grace-period
     * behaviour without sleeping for seconds. A delay of {@code 0} releases immediately on
     * the calling thread, which keeps the release deterministic under test.</p>
     */
    static void run(Runnable body, long graceMillis) {
        depth.incrementAndGet();
        try {
            body.run();
        } finally {
            scheduleRelease(graceMillis);
        }
    }

    private static void scheduleRelease(long graceMillis) {
        if (graceMillis <= 0) {
            depth.decrementAndGet();
            return;
        }
        try {
            releaser.schedule(depth::decrementAndGet, graceMillis, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            // A rejected release (executor shut down) would strand the guard on forever,
            // silently disabling automatic export for the life of the gateway.
            depth.decrementAndGet();
        }
    }

    /** Test-only escape hatch so a failing test cannot leak suppression into the next one. */
    static void exitForTest() {
        depth.decrementAndGet();
    }
}
