package com.axone_io.ignition.git.managers;

import com.axone_io.ignition.git.TagExportConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Coalesces tag and UDT resource changes and exports tags to the working tree once the
 * editing burst settles.
 *
 * <p>Renaming a UDT can touch dozens of tag resources, so every event re-arms a single
 * timer rather than triggering work of its own. The export runs on this class's own
 * executor and never on the thread delivering the resource event — blocking Ignition's
 * resource system would stall the gateway.</p>
 */
public class TagChangeWatcher {
    private static final Logger logger = LoggerFactory.getLogger(TagChangeWatcher.class);

    /** Quiet period after the last tag change before tags are exported. */
    public static final long DEBOUNCE_MS = 5_000L;

    private final long debounceMillis;
    private final Runnable exportAction;
    private final ScheduledExecutorService scheduler;

    private ScheduledFuture<?> pending;

    public TagChangeWatcher(long debounceMillis, Runnable exportAction) {
        this.debounceMillis = debounceMillis;
        this.exportAction = exportAction;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "git-tag-change-watcher");
            t.setDaemon(true);
            return t;
        });
    }

    /** Records a tag/UDT resource change and (re)arms the debounce timer. */
    public synchronized void onTagResourceChanged() {
        if (ImportSuppression.isSuppressed()) {
            logger.debug("Ignoring tag resource change: a module-initiated import is in progress.");
            return;
        }
        if (pending != null) {
            pending.cancel(false);
        }
        pending = scheduler.schedule(this::fire, debounceMillis, TimeUnit.MILLISECONDS);
    }

    private void fire() {
        // The burst may have been a module import that started after the timer was armed.
        if (ImportSuppression.isSuppressed()) {
            logger.debug("Skipping tag export: a module-initiated import is in progress.");
            return;
        }
        try {
            exportAction.run();
        } catch (Exception e) {
            // Swallowed deliberately: an escaping exception would cancel nothing here, but
            // logging it keeps a broken export from failing silently forever.
            logger.error("Automatic tag export failed; tag drift will not be visible until "
                    + "the next change or a manual Export.", e);
        }
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }

    /**
     * Whether a project may be auto-exported without a user present to confirm.
     *
     * <p>On a gateway tracking several projects, exporting tags writes <em>every</em>
     * provider's tags into whichever repository ran the export — which is why the manual
     * Export button asks for confirmation first. Automatic export has nobody to ask, so it
     * runs only where the project has narrowed its scope with {@code includedProviders}.</p>
     */
    public static boolean isAutoExportEligible(TagExportConfig config, int trackedProjectCount) {
        if (trackedProjectCount <= 1) {
            return true;
        }
        return config != null
                && config.getIncludedProviders() != null
                && !config.getIncludedProviders().isEmpty();
    }

    /**
     * Exports each eligible project, isolating per-project failures.
     *
     * @return the number of projects exported successfully
     */
    public static int runExport(List<String> projectNames,
                                Predicate<String> eligible,
                                Consumer<String> exporter) {
        int exported = 0;
        for (String projectName : projectNames) {
            if (!eligible.test(projectName)) {
                logger.debug("Skipping automatic tag export for project '" + projectName
                        + "': not eligible on a multi-project gateway without includedProviders.");
                continue;
            }
            try {
                exporter.accept(projectName);
                exported++;
            } catch (Exception e) {
                logger.warn("Automatic tag export failed for project '" + projectName
                        + "'; other projects are unaffected.", e);
            }
        }
        return exported;
    }
}
