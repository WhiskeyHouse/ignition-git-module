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
        if (promptOpen) {
            return;
        }
        promptOpen = true;
        try {
            if (state.isProductionMode()) {
                promptProduction(state, projectName, userName);
            } else {
                new PendingChangesDialog(
                        DesignerHook.context.getFrame(),
                        state,
                        () -> GitActionManager.showCommitWithHotfixDetection(projectName, userName));
            }
        } catch (Exception e) {
            logger.warn("Unable to show the uncommitted-changes prompt", e);
        } finally {
            promptOpen = false;
            onDismissed.run();
        }
    }

    private static void promptProduction(RepoDirtyState state, String projectName, String userName) {
        ProductionModeConfig config = DesignerHook.getCachedProductionConfig();
        if (config == null) {
            // The cache is invalidated by pull, branch switch, and hotfix. Fall back to a
            // minimal config rather than skipping the checklist on a production gateway.
            config = new ProductionModeConfig(true, null, null);
        }
        new ProductionModePopup(DesignerHook.context.getFrame(), config,
                "Uncommitted Changes on Production Gateway") {
            @Override
            public void onProceed() {
                GitActionManager.showCommitWithHotfixDetection(projectName, userName);
            }
        };
    }
}
