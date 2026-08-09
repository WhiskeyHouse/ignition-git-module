package com.axone_io.ignition.git.managers;

import com.axone_io.ignition.git.GatewayHook;
import com.axone_io.ignition.git.GatewayResourceExportPolicy;
import com.axone_io.ignition.git.TagExportConfig;
import com.inductiveautomation.ignition.common.resourcecollection.ChangeOperation;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceFilter;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceListener;
import com.inductiveautomation.ignition.gateway.tags.config.TagResourceTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
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
     * <p>Exporting writes <em>every</em> tag provider — plus the gateway's themes and images —
     * into whichever repository ran the export, which is why the manual Export button asks for
     * confirmation first. Automatic export has nobody to ask, so it runs only for the project
     * explicitly designated to own gateway-scoped resources.</p>
     *
     * <p>An earlier rule allowed any lone project, and otherwise required the project to have
     * narrowed {@code includedProviders}. That modelled ownership partitioned by tag provider,
     * which cannot work: themes and images are gateway-scoped with no per-project subdivision,
     * so there is nothing to partition them by. And inferring an owner from "there is only one
     * project" silently changes behaviour the moment a second project is added — the exact
     * failure this guard exists to prevent.</p>
     *
     * @param projectName the project whose automatic export is being considered
     * @param owners      names of every project with {@code gateway_exportResources} enabled
     */
    public static boolean isAutoExportEligible(String projectName, Collection<String> owners) {
        return GatewayResourceExportPolicy.describeSkipReason(projectName, owners) == null;
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
                        + "': it is not the designated owner of gateway-scoped resources.");
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

    /**
     * A watcher wired to the real export path: on each settled burst, export tags for every
     * git-tracked project that is eligible for unattended export.
     */
    public static TagChangeWatcher createDefault() {
        return new TagChangeWatcher(DEBOUNCE_MS, TagChangeWatcher::exportAllTrackedProjects);
    }

    private static void exportAllTrackedProjects() {
        List<String> projects = GatewayHook.getScriptModule().getGitTrackedProjectNames();
        int trackedCount = projects.size();

        // Resolved once per burst rather than per project, so every project in a single export
        // pass sees the same ownership picture.
        Collection<String> owners = GatewayHook.getScriptModule().getGatewayResourceOwnerNames();

        int exported = runExport(
                projects,
                projectName -> isAutoExportEligible(projectName, owners),
                projectName -> GitTagManager.exportTag(GitManager.getProjectFolderPath(projectName)));

        logger.debug("Automatic tag export finished for " + exported + " of " + trackedCount
                + " tracked project(s).");
    }

    /**
     * Adapts this watcher to Ignition's resource system. Every callback collapses to the same
     * thing — "some tag or UDT changed" — because the debounced export re-reads the live tag
     * configuration anyway and has no use for the individual operations.
     */
    public ResourceListener asResourceListener() {
        return new ResourceListener() {
            @Override
            public ResourceFilter getResourceFilter() {
                return ResourceFilter.newBuilder()
                        .addResourceTypes(Arrays.asList(
                                TagResourceTypes.TAG_DEFINITION,
                                TagResourceTypes.TYPE_DEFINITION))
                        .build();
            }

            @Override
            public void resourcesCreated(String collectionName,
                                         List<ChangeOperation.CreateResourceOperation> operations) {
                onTagResourceChanged();
            }

            @Override
            public void resourcesModified(String collectionName,
                                          List<ChangeOperation.ModifyResourceOperation> operations) {
                onTagResourceChanged();
            }

            @Override
            public void resourcesDeleted(String collectionName,
                                         List<ChangeOperation.DeleteResourceOperation> operations) {
                onTagResourceChanged();
            }
        };
    }
}
