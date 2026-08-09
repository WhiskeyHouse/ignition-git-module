package com.axone_io.ignition.git.managers;

import com.axone_io.ignition.git.DesignerHook;
import com.axone_io.ignition.git.PendingChangesDialog;
import com.axone_io.ignition.git.ProductionModePopup;
import com.axone_io.ignition.git.dto.ProductionModeConfig;
import com.axone_io.ignition.git.dto.RepoDirtyState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routes uncommitted-change prompts by gateway mode. Both paths land in the same commit
 * dialog; production adds the safety checklist first, exactly as project saves do.
 */
public final class GitWorkflowPrompter {
    private static final Logger logger = LoggerFactory.getLogger(GitWorkflowPrompter.class);

    private static volatile boolean promptOpen = false;

    private GitWorkflowPrompter() {
    }

    /** Whether a prompt is on screen. The poller uses this so dialogs never stack. */
    public static boolean isPromptOpen() {
        return promptOpen;
    }

    /**
     * Shows the prompt for the current mode. Must be called on the EDT. Both dialogs are
     * application-modal and call {@code setVisible(true)} from their constructors, so this
     * method does not return until the user has dismissed the prompt.
     *
     * @param onDismissed run after the dialog closes, whichever button was used — the caller
     *                    records the dismissed revision so the same changes do not re-prompt.
     */
    public static void prompt(RepoDirtyState state,
                              String projectName,
                              String userName,
                              Runnable onDismissed) {
        show(onDismissed, () -> {
            if (state.isProductionMode()) {
                ProductionModeConfig config = DesignerHook.getCachedProductionConfig();
                if (config == null) {
                    // The cache is invalidated by pull, branch switch, and hotfix. Fall back to
                    // a minimal config rather than skipping the checklist on a production gateway.
                    config = new ProductionModeConfig(true, null, null);
                }
                showProductionChecklist(config, "Uncommitted Changes on Production Gateway",
                        projectName, userName);
            } else {
                new PendingChangesDialog(
                        DesignerHook.context.getFrame(),
                        state,
                        () -> GitActionManager.showCommitWithHotfixDetection(projectName, userName));
            }
        });
    }

    /**
     * Shows the save-time production checklist. A project save both dirties the tree and
     * triggers the drift poll, so this shares the {@code promptOpen} latch with
     * {@link #prompt}: whichever trigger fires first wins and the other is a no-op. Without
     * that, one Save could open two checklists and start the hotfix pipeline twice.
     *
     * <p>The config is supplied by the caller rather than read from the cache because the
     * save path fails <em>conservative</em> — an unverifiable gateway is warned about — while
     * the poller fails quiet.</p>
     *
     * @param onDismissed run after the dialog closes; the caller records the revision as
     *                    dismissed so the poller does not immediately re-prompt for the same
     *                    changes.
     */
    public static void promptProductionSave(ProductionModeConfig config,
                                            String projectName,
                                            String userName,
                                            Runnable onDismissed) {
        show(onDismissed, () -> showProductionChecklist(config, "Save to Production Gateway",
                projectName, userName));
    }

    /**
     * Runs {@code dialog} under the shared latch. Both dialogs are application-modal and
     * open from their constructors, so {@code dialog.run()} does not return until dismissed.
     */
    private static void show(Runnable onDismissed, Runnable dialog) {
        if (promptOpen) {
            return;
        }
        promptOpen = true;
        try {
            dialog.run();
        } catch (Exception e) {
            logger.warn("Unable to show the uncommitted-changes prompt", e);
        } finally {
            promptOpen = false;
            onDismissed.run();
        }
    }

    private static void showProductionChecklist(ProductionModeConfig config,
                                                String title,
                                                String projectName,
                                                String userName) {
        new ProductionModePopup(DesignerHook.context.getFrame(), config, title) {
            @Override
            public void onProceed() {
                GitActionManager.showCommitWithHotfixDetection(projectName, userName);
            }
        };
    }
}
