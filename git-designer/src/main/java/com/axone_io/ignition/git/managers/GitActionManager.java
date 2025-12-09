package com.axone_io.ignition.git.managers;

import com.axone_io.ignition.git.records.GitProjectsConfigRecord;
import com.axone_io.ignition.git.BranchInfo;
import com.axone_io.ignition.git.BranchPopup;
import com.axone_io.ignition.git.BranchStatus;
import com.axone_io.ignition.git.CommitHistoryViewer;
import com.axone_io.ignition.git.CommitInfo;
import com.axone_io.ignition.git.CommitPopup;
import com.axone_io.ignition.git.DesignerHook;
import com.axone_io.ignition.git.PullPopup;
import com.axone_io.ignition.git.UncommittedChange;
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
        if (commitPopup != null) {
            commitPopup.setData(data);
            commitPopup.setVisible(true);
            commitPopup.toFront();
        } else {
            commitPopup = new CommitPopup(data, context.getFrame()) {
                @Override
                public void onActionPerformed(List<String> changes, String commitMessage) {
                    handleCommitAction(changes, commitMessage);
                    resetMessage();
                }
            };
        }
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



    public static void showPullPopup(String projectName, String userName) {
        if (pullPopup != null) {
            pullPopup.setVisible(true);
            pullPopup.toFront();
        } else {
            pullPopup = new PullPopup(context.getFrame()) {
                @Override
                public void onPullAction(boolean importTags, boolean importTheme, boolean importImages) {
                    handlePullAction(importTags, importTheme, importImages);
                    resetCheckboxes();
                }
            };
        }
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
        try {
            // Fetch from remote first
            logger.info("Fetching branches from remote...");
            rpc.fetchFromRemote(projectName, userName);

            // Get branch list and status
            List<BranchInfo> branches = rpc.listBranches(projectName, userName);
            BranchStatus status = rpc.getBranchStatus(projectName, userName);

            Object[][] data = buildBranchTableData(branches);

            if (branchPopup != null) {
                branchPopup.updateData(data, status);
                branchPopup.setVisible(true);
                branchPopup.toFront();
            } else {
                branchPopup = new BranchPopup(data, status, context.getFrame()) {
                    @Override
                    public void onSwitchBranch(String branchName, boolean createNew) {
                        handleBranchSwitch(projectName, userName, branchName, createNew);
                    }

                    @Override
                    public void onRefresh() {
                        handleBranchRefresh(projectName, userName);
                    }
                };
            }
        } catch (Exception e) {
            logger.error("Error loading branches", e);
            JOptionPane.showMessageDialog(context.getFrame(),
                    "Failed to load branches: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static void handleBranchSwitch(String projectName, String userName, String branchName, boolean createNew) {
        try {
            // Get current status for warnings
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

            // Perform the branch switch
            logger.info("Switching to branch: {}, createNew: {}", branchName, createNew);
            boolean success = rpc.switchBranch(projectName, userName, branchName, createNew);

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
        try {
            logger.info("Refreshing branch list...");

            // Fetch from remote
            rpc.fetchFromRemote(projectName, userName);

            // Refresh data
            List<BranchInfo> branches = rpc.listBranches(projectName, userName);
            BranchStatus status = rpc.getBranchStatus(projectName, userName);

            Object[][] data = buildBranchTableData(branches);

            if (branchPopup != null) {
                branchPopup.updateData(data, status);
            }

            JOptionPane.showMessageDialog(context.getFrame(),
                    "Branch list refreshed successfully.",
                    "Refresh Complete",
                    JOptionPane.INFORMATION_MESSAGE);

        } catch (Exception e) {
            logger.error("Error refreshing branches", e);
            JOptionPane.showMessageDialog(context.getFrame(),
                    "Failed to refresh branches: " + e.getMessage(),
                    "Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }
}
