package com.axone_io.ignition.git.managers;

import com.axone_io.ignition.git.dto.HotfixResult;
import com.axone_io.ignition.git.dto.ProductionModeConfig;
import com.axone_io.ignition.git.records.GitProjectsConfigRecord;
import com.axone_io.ignition.git.BranchInfo;
import com.axone_io.ignition.git.BranchPopup;
import com.axone_io.ignition.git.BranchStatus;
import com.axone_io.ignition.git.CommitHistoryViewer;
import com.axone_io.ignition.git.CommitInfo;
import com.axone_io.ignition.git.CommitPopup;
import com.axone_io.ignition.git.DesignerHook;
import com.axone_io.ignition.git.HotfixCommitDialog;
import com.axone_io.ignition.git.HotfixProgressDialog;
import com.axone_io.ignition.git.ProductionModePopup;
import com.axone_io.ignition.git.PullPopup;
import com.axone_io.ignition.git.UncommittedChange;
import com.axone_io.ignition.git.actions.GitBaseAction;
import com.inductiveautomation.ignition.common.resourcecollection.ChangeOperation;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceId;
import com.inductiveautomation.ignition.common.util.LoggerEx;

import javax.swing.*;
import javax.swing.SwingWorker;
import java.util.ArrayList;
import java.util.List;
import java.awt.Desktop;
import java.net.URI;
import java.net.URISyntaxException;
import java.io.IOException;






import static com.axone_io.ignition.git.DesignerHook.context;
import static com.axone_io.ignition.git.DesignerHook.rpc;
import static com.axone_io.ignition.git.actions.GitBaseAction.handleCommitAction;
import static com.axone_io.ignition.git.actions.GitBaseAction.handlePullAction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
public class GitActionManager {

    static CommitPopup commitPopup;
    static PullPopup pullPopup;
    static BranchPopup branchPopup;
    private static final Logger logger = LoggerFactory.getLogger(GitActionManager.class);



    public static Object[][] getCommitPopupData(String projectName, String userName) {
        List<ChangeOperation> changes = DesignerHook.changes;

        // Log the total number of change operations found
        logger.debug("Total number of change operations: {}", changes.size());

        List<UncommittedChange> uncommittedChanges = rpc.getUncommitedChanges(projectName, userName);
        Object[][] data = new Object[uncommittedChanges.size()][];

        List<String> resourcesChangedId = new ArrayList<>();
        for (ChangeOperation c : changes) {
            ResourceId rid = ChangeOperation.getResourceIdFromChange(c);
            resourcesChangedId.add(rid.getResourcePath().toString());

            // Log each change operation's details
            logger.debug("ChangeOperation Type: {}, Resource: {}", c.getOperationType(), rid.getResourcePath());
        }

        for (int i = 0; i < uncommittedChanges.size(); i++) {
            UncommittedChange change = uncommittedChanges.get(i);
            String resource = change.getResource();

            boolean toAdd = resourcesChangedId.contains(resource);
            Object[] row = {toAdd, resource, change.getType(), change.getActor()};

            // Log the decision to add or not add the resource to the commit popup
            logger.debug("Resource: {}, Add to commit popup: {}", resource, toAdd);

            data[i] = row;
        }

        return data;
    }

    private static Object[][] buildBranchTableData(List<BranchInfo> branches) {
        Object[][] data = new Object[branches.size()][];
        for (int i = 0; i < branches.size(); i++) {
            BranchInfo branch = branches.get(i);
            String currentMarker = branch.isCurrent() ? "★" : "";
            String type = branch.isLocal() ? (branch.isRemote() ? "Local/Remote" : "Local") : "Remote";
            String statusStr = branch.getStatusString();

            data[i] = new Object[]{branch.getDisplayName(), currentMarker, type, statusStr};
        }
        return data;
    }


    public static void showCommitPopup(String projectName, String userName) {
        Object[][] data = GitActionManager.getCommitPopupData(projectName, userName);
        // Always recreate to ensure correct project context
        if (commitPopup != null) {
            commitPopup.dispose();
            commitPopup = null;
        }
        commitPopup = new CommitPopup(data, context.getFrame()) {
            @Override
            public void onActionPerformed(List<String> changes, String commitMessage) {
                handleCommitAction(changes, commitMessage);
                resetMessage();
            }
        };
        commitPopup.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                commitPopup = null;
            }
        });
    }
    public static void openRepositoryLink() {
        try {
            Desktop desktop = Desktop.getDesktop();
            String repoLink = "https://github.com";
            desktop.browse(new URI(repoLink)); // This line might throw IOException or URISyntaxException
        } catch (IOException | URISyntaxException e) {
            logger.error("Error opening repository link", e);
        }
    }

    private static String getGatewayBaseUrl() {
        try {
            com.inductiveautomation.ignition.client.gateway_interface.GatewayConnection gc =
                com.inductiveautomation.ignition.client.gateway_interface.GatewayConnectionManager.getInstance();
            if (gc != null) {
                String webUrl = gc.getGatewayWebURL();
                if (webUrl != null && !webUrl.isEmpty()) {
                    return webUrl;
                }
            }
        } catch (Exception e) {
            logger.debug("Could not get gateway address dynamically, using fallback", e);
        }
        return "https://localhost:9043";
    }

    public static void openDocsViewer(String projectName) {
        try {
            String baseUrl = getGatewayBaseUrl();
            // Use the redirect endpoint which stores params in sessionStorage
            // before navigating to the config page (bypasses SPA param stripping)
            String docsUrl = baseUrl + "/data/git/docs/redirect?project="
                + java.net.URLEncoder.encode(projectName, java.nio.charset.StandardCharsets.UTF_8);
            Desktop.getDesktop().browse(new URI(docsUrl));
        } catch (IOException | URISyntaxException e) {
            logger.error("Error opening docs viewer", e);
        }
    }

    public static void openDocsViewer(String projectName, String docPath) {
        try {
            String baseUrl = getGatewayBaseUrl();
            // Use the redirect endpoint which stores params in sessionStorage
            // before navigating to the config page (bypasses SPA param stripping)
            String docsUrl = baseUrl + "/data/git/docs/redirect?project="
                + java.net.URLEncoder.encode(projectName, java.nio.charset.StandardCharsets.UTF_8)
                + "&path=" + java.net.URLEncoder.encode(docPath, java.nio.charset.StandardCharsets.UTF_8);
            Desktop.getDesktop().browse(new URI(docsUrl));
        } catch (IOException | URISyntaxException e) {
            logger.error("Error opening docs viewer", e);
        }
    }



    public static void showPullPopup(String projectName, String userName) {
        // Check production mode and current branch first
        SwingWorker<Object[], Void> worker = new SwingWorker<Object[], Void>() {
            @Override
            protected Object[] doInBackground() throws Exception {
                ProductionModeConfig config = rpc.getProductionModeConfig(projectName);
                String currentBranch = rpc.getCurrentBranch(projectName);
                // Advisory only — a failure here must not stop the user from pulling.
                String divergence = null;
                try {
                    divergence = rpc.getUnpushedProductionCommits(projectName);
                } catch (Exception e) {
                    logger.warn("Could not check for unpushed production commits", e);
                }
                return new Object[]{config, currentBranch, divergence};
            }

            @Override
            protected void done() {
                try {
                    Object[] results = get();
                    ProductionModeConfig prodConfig = (ProductionModeConfig) results[0];
                    String currentBranch = (String) results[1];
                    String divergence = (String) results[2];

                    // Block pull on hotfix branches
                    if (currentBranch != null && currentBranch.startsWith("hotfix/")) {
                        JOptionPane.showMessageDialog(
                            context.getFrame(),
                            "Pull is disabled on hotfix branches.\nComplete your hotfix first, then pull on main.",
                            "Hotfix Branch — Pull Blocked",
                            JOptionPane.WARNING_MESSAGE
                        );
                        return;
                    }

                    // If production mode is active, show warning popup first
                    if (prodConfig.isProductionMode()) {
                        logger.info("Production mode is active for project: " + projectName);

                        if (divergence != null) {
                            logger.warn("Pull requested while production branch has unpushed commits: {}", divergence);
                        }

                        new ProductionModePopup(context.getFrame(), prodConfig, "Pull from Git", divergence) {
                            @Override
                            public void onProceed() {
                                // User confirmed, show regular pull popup
                                showPullPopupInternal(projectName, userName);
                            }
                        };
                    } else {
                        // Not in production mode, show regular pull popup
                        showPullPopupInternal(projectName, userName);
                    }
                } catch (Exception e) {
                    logger.error("Error checking production mode", e);
                    JOptionPane.showMessageDialog(
                        context.getFrame(),
                        "Unable to verify production mode for this project. Pull was blocked.\n\n" + e.getMessage(),
                        "Production Mode Check Failed",
                        JOptionPane.ERROR_MESSAGE
                    );
                }
            }
        };
        worker.execute();
    }

    private static void showPullPopupInternal(String projectName, String userName) {
        // Always recreate to ensure correct project context
        if (pullPopup != null) {
            pullPopup.dispose();
            pullPopup = null;
        }
        pullPopup = new PullPopup(context.getFrame()) {
            @Override
            public void onPullAction(boolean importTags, boolean importTheme, boolean importImages) {
                handlePullAction(importTags, importTheme, importImages);
                resetCheckboxes();
            }
        };
        pullPopup.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                pullPopup = null;
            }
        });
    }

    /**
     * Check if current commit should trigger the hotfix workflow.
     * Called from the toolbar Commit action and from the save-prompt.
     */
    public static void showCommitWithHotfixDetection(String projectName, String userName) {
        SwingWorker<Object[], Void> worker = new SwingWorker<Object[], Void>() {
            @Override
            protected Object[] doInBackground() throws Exception {
                ProductionModeConfig config = rpc.getProductionModeConfig(projectName);
                String currentBranch = rpc.getCurrentBranch(projectName);
                Object[][] changeData = getCommitPopupData(projectName, userName);
                return new Object[]{config, currentBranch, changeData};
            }

            @Override
            protected void done() {
                try {
                    Object[] results = get();
                    ProductionModeConfig config = (ProductionModeConfig) results[0];
                    String currentBranch = (String) results[1];
                    Object[][] changeData = (Object[][]) results[2];

                    boolean isHotfix = config.isProductionMode()
                        && config.getProductionBranch() != null
                        && config.getProductionBranch().equals(currentBranch);

                    if (isHotfix) {
                        logger.info("Hotfix scenario detected: production mode on branch '{}'", currentBranch);
                        showHotfixCommitDialog(projectName, userName, config, changeData);
                    } else {
                        showCommitPopup(projectName, userName);
                    }
                } catch (Exception e) {
                    logger.error("Error checking hotfix scenario", e);
                    showCommitPopup(projectName, userName);
                }
            }
        };
        worker.execute();
    }

    private static void showHotfixCommitDialog(String projectName, String userName,
                                                ProductionModeConfig config, Object[][] changeData) {
        String productionBranch = config.getProductionBranch();

        HotfixCommitDialog dialog = new HotfixCommitDialog(
            context.getFrame(), changeData, productionBranch
        );
        dialog.setVisible(true);

        if (!dialog.isConfirmed()) {
            return;
        }

        String description = dialog.getHotfixDescription();
        String message = dialog.getCommitMessage();
        List<String> changes = dialog.getSelectedChanges();

        if (changes.isEmpty()) {
            JOptionPane.showMessageDialog(context.getFrame(),
                "No changes selected for hotfix.", "No Changes", JOptionPane.WARNING_MESSAGE);
            return;
        }

        executeHotfixWithProgress(projectName, userName, description, message, changes.toArray(new String[0]));
    }

    private static void executeHotfixWithProgress(String projectName, String userName,
                                                   String description, String message, String[] changes) {
        SwingWorker<HotfixResult, Void> executor = new SwingWorker<HotfixResult, Void>() {
            @Override
            protected HotfixResult doInBackground() throws Exception {
                return rpc.executeHotfix(projectName, userName, description, message, changes);
            }

            @Override
            protected void done() {
                // Progress dialog handles completion via polling
            }
        };
        executor.execute();

        HotfixProgressDialog progressDialog = new HotfixProgressDialog(
            context.getFrame(), rpc, projectName
        );
        progressDialog.setVisible(true);

        // After progress dialog closes, invalidate cache
        DesignerHook.invalidateProductionConfigCache();
    }

    /**
     * Push the current branch to remote. Push is outbound and does not modify this gateway,
     * so there is no Designer-side production warning here — the warning happens at save time
     * (see DesignerHook.notifyProjectSaveDone), and the gateway still hard-blocks pushes from
     * unsafe repository states.
     */
    public static void pushCurrentBranch(String projectName, String userName) {
        executePush(projectName, userName);
    }

    private static void executePush(String projectName, String userName) {
        SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                rpc.push(projectName, userName);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    String message = com.inductiveautomation.ignition.common.BundleUtil.get()
                            .getStringLenient("DesignerHook.Actions.Push.ConfirmMessage");
                    SwingUtilities.invokeLater(() -> showConfirmPopup(message, JOptionPane.INFORMATION_MESSAGE));
                } catch (Exception e) {
                    logger.error("Error during push", e);
                    // A production guard rejecting the push is an expected, actionable outcome —
                    // lead with the gateway's reason rather than a wrapped ExecutionException.
                    SwingUtilities.invokeLater(() -> {
                        com.inductiveautomation.ignition.client.util.gui.ErrorUtil.showError(
                                "Push failed or could not be confirmed:\n\n"
                                        + GitBaseAction.rootMessage(e), e);
                    });
                }
            }
        };
        worker.execute();
    }

    public static void showConfirmPopup(String message, int messageType) {
        JOptionPane.showConfirmDialog(context.getFrame(),
                message, "Info", JOptionPane.DEFAULT_OPTION, messageType);
    }

    public static void showHistoryViewer(String projectName, String userName) {
        SwingWorker<List<CommitInfo>, Void> worker = new SwingWorker<List<CommitInfo>, Void>() {
            @Override
            protected List<CommitInfo> doInBackground() throws Exception {
                return rpc.getCommitHistory(projectName, userName, 100);
            }

            @Override
            protected void done() {
                try {
                    List<CommitInfo> commits = get();
                    new CommitHistoryViewer(commits, context.getFrame());
                } catch (Exception e) {
                    logger.error("Error loading commit history", e);
                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(context.getFrame(),
                                "Failed to load commit history: " + e.getMessage(),
                                "Error", JOptionPane.ERROR_MESSAGE);
                    });
                }
            }
        };
        worker.execute();
    }

    public static void showBranchPopup(String projectName, String userName) {
        // Block branch switching in production mode — use the hotfix workflow instead
        ProductionModeConfig cachedConfig = DesignerHook.getCachedProductionConfig();
        if (cachedConfig != null && cachedConfig.isProductionMode()) {
            // Check if already on a hotfix branch (allow viewing for context)
            try {
                String currentBranch = rpc.getCurrentBranch(projectName);
                if (currentBranch != null && currentBranch.startsWith("hotfix/")) {
                    // On a hotfix branch — allow viewing but warn
                    JOptionPane.showMessageDialog(
                        context.getFrame(),
                        "You are on hotfix branch '" + currentBranch + "'.\n" +
                        "Complete your hotfix to return to the production branch.",
                        "Hotfix Branch Active",
                        JOptionPane.INFORMATION_MESSAGE
                    );
                    return;
                }
            } catch (Exception ignored) {}

            JOptionPane.showMessageDialog(
                context.getFrame(),
                "Branch switching is disabled in production mode.\n" +
                "Use the hotfix workflow to make changes.\n\n" +
                "Save your changes and commit — the hotfix workflow\n" +
                "will handle branching automatically.",
                "Production Mode \u2014 Branch Switching Disabled",
                JOptionPane.WARNING_MESSAGE
            );
            return;
        }

        // Use SwingWorker to avoid blocking the EDT and prevent heap space issues
        SwingWorker<BranchData, Void> worker = new SwingWorker<BranchData, Void>() {
            @Override
            protected BranchData doInBackground() throws Exception {
                // Fetch from remote first
                logger.info("Fetching branches from remote...");
                rpc.fetchFromRemote(projectName, userName);

                // Get branch list and status
                List<BranchInfo> branches = rpc.listBranches(projectName, userName);
                BranchStatus status = rpc.getBranchStatus(projectName, userName);

                return new BranchData(branches, status);
            }

            @Override
            protected void done() {
                try {
                    BranchData branchData = get();
                    Object[][] data = buildBranchTableData(branchData.branches);

                    // Always recreate to ensure correct project context
                    if (branchPopup != null) {
                        branchPopup.dispose();
                        branchPopup = null;
                    }
                    branchPopup = new BranchPopup(data, branchData.status, context.getFrame()) {
                        @Override
                        public void onSwitchBranch(String branchName, boolean createNew, boolean forceCheckout) {
                            handleBranchSwitch(projectName, userName, branchName, createNew, forceCheckout);
                        }

                        @Override
                        public void onRefresh() {
                            handleBranchRefresh(projectName, userName);
                        }

                        @Override
                        public void onStash(String message) {
                            handleStash(projectName, userName, message);
                        }

                        @Override
                        public void onDiscard() {
                            handleDiscard(projectName, userName);
                        }

                        @Override
                        public void onResolveConflicts(String strategy) {
                            handleResolveConflicts(projectName, strategy);
                        }

                        @Override
                        public void onAbortMerge() {
                            handleAbortMerge(projectName);
                        }
                    };
                    branchPopup.addWindowListener(new java.awt.event.WindowAdapter() {
                        @Override
                        public void windowClosed(java.awt.event.WindowEvent e) {
                            branchPopup = null;
                        }
                    });
                } catch (Exception e) {
                    logger.error("Error loading branches", e);
                    SwingUtilities.invokeLater(() -> {
                        String message = e.getMessage();
                        if (e.getCause() != null) {
                            message = e.getCause().getMessage();
                        }
                        if (message != null && message.contains("heap space")) {
                            message = "Out of memory - the repository may have too many branches or objects. Try increasing Designer heap size.";
                        }
                        JOptionPane.showMessageDialog(context.getFrame(),
                                "Failed to load branches: " + message,
                                "Error", JOptionPane.ERROR_MESSAGE);
                    });
                }
            }
        };
        worker.execute();
    }

    // Helper class for SwingWorker result
    private static class BranchData {
        final List<BranchInfo> branches;
        final BranchStatus status;

        BranchData(List<BranchInfo> branches, BranchStatus status) {
            this.branches = branches;
            this.status = status;
        }
    }

    private static void handleBranchSwitch(String projectName, String userName, String branchName, boolean createNew, boolean forceCheckout) {
        try {
            // Get current status for warnings (only if not force checkout)
            if (!forceCheckout) {
                BranchStatus status = rpc.getBranchStatus(projectName, userName);

                // Show confirmation dialog if there are warnings
                if (status.hasWarnings()) {
                    String warningMessage = status.getWarningMessage();
                    warningMessage += "\nDo you want to proceed with switching branches?";

                    int choice = JOptionPane.showConfirmDialog(context.getFrame(),
                            warningMessage,
                            "Warning - Uncommitted Changes",
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.WARNING_MESSAGE);

                    if (choice != JOptionPane.YES_OPTION) {
                        return; // User cancelled
                    }
                }
            }

            // Perform the branch switch
            logger.info("Switching to branch: " + branchName + ", createNew: " + createNew + ", forceCheckout: " + forceCheckout);
            boolean success = rpc.switchBranch(projectName, userName, branchName, createNew, forceCheckout);

            if (success) {
                branchPopup.dispose();
                branchPopup = null;

                String message = createNew ?
                        "Successfully created and switched to branch: " + branchName :
                        "Successfully switched to branch: " + branchName;

                JOptionPane.showMessageDialog(context.getFrame(),
                        message,
                        "Success",
                        JOptionPane.INFORMATION_MESSAGE);
            }

        } catch (Exception e) {
            logger.error("Error switching branch", e);
            JOptionPane.showMessageDialog(context.getFrame(),
                    "Failed to switch branch: " + e.getMessage(),
                    "Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private static void handleBranchRefresh(String projectName, String userName) {
        // Use SwingWorker to avoid blocking the EDT
        SwingWorker<BranchData, Void> worker = new SwingWorker<BranchData, Void>() {
            @Override
            protected BranchData doInBackground() throws Exception {
                logger.info("Refreshing branch list...");

                // Fetch from remote
                rpc.fetchFromRemote(projectName, userName);

                // Refresh data
                List<BranchInfo> branches = rpc.listBranches(projectName, userName);
                BranchStatus status = rpc.getBranchStatus(projectName, userName);

                return new BranchData(branches, status);
            }

            @Override
            protected void done() {
                try {
                    BranchData branchData = get();
                    Object[][] data = buildBranchTableData(branchData.branches);

                    if (branchPopup != null) {
                        branchPopup.updateData(data, branchData.status);
                    }

                    JOptionPane.showMessageDialog(context.getFrame(),
                            "Branch list refreshed successfully.",
                            "Refresh Complete",
                            JOptionPane.INFORMATION_MESSAGE);

                } catch (Exception e) {
                    logger.error("Error refreshing branches", e);
                    SwingUtilities.invokeLater(() -> {
                        String message = e.getMessage();
                        if (e.getCause() != null) {
                            message = e.getCause().getMessage();
                        }
                        if (message != null && message.contains("heap space")) {
                            message = "Out of memory - the repository may have too many branches or objects.";
                        }
                        JOptionPane.showMessageDialog(context.getFrame(),
                                "Failed to refresh branches: " + message,
                                "Error",
                                JOptionPane.ERROR_MESSAGE);
                    });
                }
            }
        };
        worker.execute();
    }

    private static void handleStash(String projectName, String userName, String message) {
        String safeMessage = message == null ? "" : message;
        SwingWorker<Boolean, Void> worker = new SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() throws Exception {
                logger.info("Stashing changes with message: " + (safeMessage.isEmpty() ? "(default)" : safeMessage));
                return rpc.stashChanges(projectName, userName, safeMessage);
            }

            @Override
            protected void done() {
                try {
                    boolean success = get();
                    if (success) {
                        JOptionPane.showMessageDialog(context.getFrame(),
                                "Changes have been stashed successfully.",
                                "Stash Complete",
                                JOptionPane.INFORMATION_MESSAGE);

                        // Refresh the branch popup to update warnings
                        refreshBranchPopup(projectName, userName);
                    } else {
                        JOptionPane.showMessageDialog(context.getFrame(),
                                "Failed to stash changes.",
                                "Stash Failed",
                                JOptionPane.ERROR_MESSAGE);
                    }
                } catch (Exception e) {
                    logger.error("Error stashing changes", e);
                    JOptionPane.showMessageDialog(context.getFrame(),
                            "Failed to stash changes: " + e.getMessage(),
                            "Error",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();
    }

    private static void handleDiscard(String projectName, String userName) {
        SwingWorker<Boolean, Void> worker = new SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() throws Exception {
                logger.info("Discarding all uncommitted changes");
                return rpc.discardAllChanges(projectName, userName);
            }

            @Override
            protected void done() {
                try {
                    boolean success = get();
                    if (success) {
                        JOptionPane.showMessageDialog(context.getFrame(),
                                "All uncommitted changes have been discarded.",
                                "Discard Complete",
                                JOptionPane.INFORMATION_MESSAGE);

                        // Refresh the branch popup to update warnings
                        refreshBranchPopup(projectName, userName);
                    } else {
                        JOptionPane.showMessageDialog(context.getFrame(),
                                "Failed to discard changes.",
                                "Discard Failed",
                                JOptionPane.ERROR_MESSAGE);
                    }
                } catch (Exception e) {
                    logger.error("Error discarding changes", e);
                    JOptionPane.showMessageDialog(context.getFrame(),
                            "Failed to discard changes: " + e.getMessage(),
                            "Error",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();
    }

    private static void handleResolveConflicts(String projectName, String strategy) {
        SwingWorker<Boolean, Void> worker = new SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() throws Exception {
                logger.info("Resolving conflicts with strategy: " + strategy);
                return rpc.resolveConflicts(projectName, strategy);
            }

            @Override
            protected void done() {
                try {
                    boolean success = get();
                    if (success) {
                        String strategyDesc = "ours".equals(strategy) ? "keeping your version" : "accepting incoming changes";
                        JOptionPane.showMessageDialog(context.getFrame(),
                                "Conflicts have been resolved by " + strategyDesc + ".\n" +
                                "You can now commit the resolved files.",
                                "Conflicts Resolved",
                                JOptionPane.INFORMATION_MESSAGE);

                        // Refresh the branch popup to update warnings
                        refreshBranchPopup(projectName, DesignerHook.userName);
                    } else {
                        JOptionPane.showMessageDialog(context.getFrame(),
                                "Failed to resolve conflicts.",
                                "Resolve Failed",
                                JOptionPane.ERROR_MESSAGE);
                    }
                } catch (Exception e) {
                    logger.error("Error resolving conflicts", e);
                    JOptionPane.showMessageDialog(context.getFrame(),
                            "Failed to resolve conflicts: " + e.getMessage(),
                            "Error",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();
    }

    private static void handleAbortMerge(String projectName) {
        SwingWorker<Boolean, Void> worker = new SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() throws Exception {
                logger.info("Aborting merge");
                return rpc.abortMerge(projectName);
            }

            @Override
            protected void done() {
                try {
                    boolean success = get();
                    if (success) {
                        JOptionPane.showMessageDialog(context.getFrame(),
                                "Merge has been aborted. Repository restored to previous state.",
                                "Merge Aborted",
                                JOptionPane.INFORMATION_MESSAGE);

                        // Refresh the branch popup to update warnings
                        refreshBranchPopup(projectName, DesignerHook.userName);
                    } else {
                        JOptionPane.showMessageDialog(context.getFrame(),
                                "Repository is not in a merge state.",
                                "Not In Merge",
                                JOptionPane.INFORMATION_MESSAGE);
                    }
                } catch (Exception e) {
                    logger.error("Error aborting merge", e);
                    JOptionPane.showMessageDialog(context.getFrame(),
                            "Failed to abort merge: " + e.getMessage(),
                            "Error",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();
    }

    private static void refreshBranchPopup(String projectName, String userName) {
        if (branchPopup != null) {
            SwingWorker<BranchData, Void> refreshWorker = new SwingWorker<BranchData, Void>() {
                @Override
                protected BranchData doInBackground() throws Exception {
                    List<BranchInfo> branches = rpc.listBranches(projectName, userName);
                    BranchStatus status = rpc.getBranchStatus(projectName, userName);
                    return new BranchData(branches, status);
                }

                @Override
                protected void done() {
                    try {
                        BranchData branchData = get();
                        Object[][] data = buildBranchTableData(branchData.branches);
                        if (branchPopup != null) {
                            branchPopup.updateData(data, branchData.status);
                        }
                    } catch (Exception e) {
                        logger.error("Error refreshing branch popup", e);
                    }
                }
            };
            refreshWorker.execute();
        }
    }

    public static void showImportResourcesPopup(String projectName) {
        // Show checkbox dialog for importing resources without pulling
        JCheckBox tagsCheckBox = new JCheckBox("Tags & Tag Groups", true);
        JCheckBox themesCheckBox = new JCheckBox("Themes", false);
        JCheckBox imagesCheckBox = new JCheckBox("Images", false);

        // Collision policy dropdown (only relevant for tags)
        JLabel collisionLabel = new JLabel("Tag collision policy:");
        String[] collisionOptions = {"Overwrite", "Merge", "Abort on Collision"};
        JComboBox<String> collisionCombo = new JComboBox<>(collisionOptions);
        collisionCombo.setSelectedIndex(0);

        // Enable/disable collision dropdown based on tags checkbox
        collisionLabel.setEnabled(tagsCheckBox.isSelected());
        collisionCombo.setEnabled(tagsCheckBox.isSelected());
        tagsCheckBox.addActionListener(e -> {
            collisionLabel.setEnabled(tagsCheckBox.isSelected());
            collisionCombo.setEnabled(tagsCheckBox.isSelected());
        });

        Object[] message = {
            "Select resources to import from the local repository:",
            tagsCheckBox,
            themesCheckBox,
            imagesCheckBox,
            Box.createVerticalStrut(8),
            collisionLabel,
            collisionCombo
        };

        int option = JOptionPane.showConfirmDialog(context.getFrame(),
                message,
                "Import Resources",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);

        if (option == JOptionPane.OK_OPTION) {
            String collisionPolicy = (String) collisionCombo.getSelectedItem();
            handleImportResources(projectName,
                    tagsCheckBox.isSelected(),
                    themesCheckBox.isSelected(),
                    imagesCheckBox.isSelected(),
                    collisionPolicy);
        }
    }

    public static void handleImportResources(String projectName, boolean importTags, boolean importTheme, boolean importImages, String collisionPolicy) {
        SwingWorker<Boolean, Void> worker = new SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() throws Exception {
                logger.info("Importing resources for project: " + projectName +
                           " (tags=" + importTags + ", theme=" + importTheme + ", images=" + importImages +
                           ", collisionPolicy=" + collisionPolicy + ")");
                return rpc.importResources(projectName, importTags, importTheme, importImages, collisionPolicy);
            }

            @Override
            protected void done() {
                try {
                    boolean success = get();
                    if (success) {
                        StringBuilder imported = new StringBuilder("Successfully imported:");
                        if (importTags) imported.append("\n  • Tags & Tag Groups");
                        if (importTheme) imported.append("\n  • Themes");
                        if (importImages) imported.append("\n  • Images");

                        JOptionPane.showMessageDialog(context.getFrame(),
                                imported.toString(),
                                "Import Complete",
                                JOptionPane.INFORMATION_MESSAGE);
                    }
                } catch (Exception e) {
                    logger.error("Error importing resources", e);
                    JOptionPane.showMessageDialog(context.getFrame(),
                            "Failed to import resources: " + e.getMessage(),
                            "Error",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();
    }
}
