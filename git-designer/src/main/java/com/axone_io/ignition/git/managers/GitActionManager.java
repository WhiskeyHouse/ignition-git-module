package com.axone_io.ignition.git.managers;

import com.axone_io.ignition.git.records.GitProjectsConfigRecord;
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
        try {
            List<CommitInfo> commits = rpc.getCommitHistory(projectName, userName, 100);
            new CommitHistoryViewer(commits, context.getFrame());
        } catch (Exception e) {
            logger.error("Error loading commit history", e);
            JOptionPane.showMessageDialog(context.getFrame(),
                    "Failed to load commit history: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }
}
