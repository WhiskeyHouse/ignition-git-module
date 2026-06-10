package com.axone_io.ignition.git.managers;

import com.axone_io.ignition.git.GatewayHook;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * Imports git-tracked tags for flagged projects once tag providers are ready,
 * on a single daemon background thread spawned from GatewayHook.startup().
 *
 * <p>Never blocks or fails gateway startup: readiness is polled with a hard
 * timeout, and each project import is individually try/caught (TEC-3757).</p>
 *
 * <p>Distinct from commissioning_importTags: this imports ONLY tags, from
 * whatever is on disk, with no git operations (no stash/fetch/pull).</p>
 */
public class StartupTagImporter {
    private final static LoggerEx logger = LoggerEx.newBuilder().build(StartupTagImporter.class);

    static final long POLL_INTERVAL_MS = 5_000L;
    static final long TIMEOUT_MS = 120_000L;

    /** Spawns the daemon import thread. Returns the thread (for tests/diagnostics). */
    public static Thread start(List<String> projectNames) {
        Thread t = new Thread(
                () -> runImport(
                        projectNames,
                        StartupTagImporter::providersReady,
                        project -> GitTagManager.importTagManager(project, null),
                        StartupTagImporter::sleep,
                        TIMEOUT_MS / POLL_INTERVAL_MS),
                "git-startup-tag-import");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /**
     * Poll/import loop with injected collaborators so it is unit-testable.
     * Polls {@code providersReady} up to {@code maxPolls} times (sleeping
     * POLL_INTERVAL_MS between polls), then imports each project, isolating
     * per-project failures.
     */
    static void runImport(List<String> projectNames,
                          BooleanSupplier providersReady,
                          Consumer<String> importer,
                          LongConsumer sleeper,
                          long maxPolls) {
        long polls = 0;
        while (!providersReady.getAsBoolean()) {
            polls++;
            if (polls > maxPolls) {
                logger.warn("Tag providers not ready after " + (maxPolls * POLL_INTERVAL_MS / 1000)
                        + "s; skipping startup tag import for projects: " + projectNames);
                return;
            }
            sleeper.accept(POLL_INTERVAL_MS);
        }
        for (String project : projectNames) {
            try {
                logger.info("Startup tag import (tags_importOnStartup) for project '" + project + "'.");
                importer.accept(project);
            } catch (Exception e) {
                logger.error("Startup tag import failed for project '" + project + "', continuing.", e);
            }
        }
    }

    static boolean providersReady() {
        try {
            GatewayContext ctx = GatewayHook.context;
            return ctx != null && !ctx.getTagManager().getTagProviders().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
