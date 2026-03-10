package com.axone_io.ignition.git.actions;

import com.axone_io.ignition.git.managers.GitActionManager;
import com.axone_io.ignition.git.utils.IconUtils;
import com.inductiveautomation.ignition.client.util.action.BaseAction;
import com.inductiveautomation.ignition.client.util.gui.ErrorUtil;
import com.inductiveautomation.ignition.common.BundleUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.util.List;

import static com.axone_io.ignition.git.DesignerHook.*;
import static com.axone_io.ignition.git.managers.GitActionManager.showCommitPopup;
import static com.axone_io.ignition.git.managers.GitActionManager.showPullPopup;
import static com.axone_io.ignition.git.managers.GitActionManager.showPushWithProductionCheck;
import static com.axone_io.ignition.git.managers.GitActionManager.showHistoryViewer;
import static com.axone_io.ignition.git.managers.GitActionManager.showBranchPopup;
import static com.axone_io.ignition.git.managers.GitActionManager.showConfirmPopup;
import static com.axone_io.ignition.git.managers.GitActionManager.openRepositoryLink;
import static com.axone_io.ignition.git.managers.GitActionManager.showImportResourcesPopup;
import static com.axone_io.ignition.git.managers.GitActionManager.openDocsViewer;

public class GitBaseAction extends BaseAction {
    private static final Logger logger = LoggerFactory.getLogger(GitBaseAction.class);

    public enum GitActionType {
        PULL(
            "DesignerHook.Actions.Pull",
            "/com/axone_io/ignition/git/icons/ic_pull.svg"
        ),
        PUSH(
            "DesignerHook.Actions.Push",
            "/com/axone_io/ignition/git/icons/ic_push.svg"
        ),
        COMMIT(
            "DesignerHook.Actions.Commit",
            "/com/axone_io/ignition/git/icons/ic_commit.svg"
        ),
        EXPORT(
            "DesignerHook.Actions.ExportGatewayConfig",
            "/com/axone_io/ignition/git/icons/ic_folder.svg"
        ),

        REPO(
            "DesignerHook.Actions.Repo",
            "/com/axone_io/ignition/git/icons/ic_repo.svg"
        ),

        HISTORY(
            "DesignerHook.Actions.History",
            "/com/axone_io/ignition/git/icons/ic_history.svg"
        ),

        BRANCH(
            "DesignerHook.Actions.Branch",
            "/com/axone_io/ignition/git/icons/ic_branch.svg"
        ),

        IMPORT(
            "DesignerHook.Actions.Import",
            "/com/axone_io/ignition/git/icons/ic_import.svg"
        ),

        DOCS(
            "DesignerHook.Actions.Docs",
            "/com/axone_io/ignition/git/icons/ic_docs.svg"
        );

        private final String baseBundleKey;
        private final String resourcePath;

        GitActionType(String baseBundleKey, String resourcePath) {
            this.baseBundleKey = baseBundleKey;
            this.resourcePath = resourcePath;
        }

        public Icon getIcon() {
            return IconUtils.getIcon(resourcePath);
        }
    }

    GitActionType type;

    public GitBaseAction(GitActionType type) {
        super(type.baseBundleKey, type.getIcon());
        this.type = type;
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        handleAction(type);
    }

    // Todo : Find a way to refactor with handleAction
    public static void handleCommitAction(List<String> changes, String commitMessage) {
        String message = BundleUtil.get().getStringLenient(GitActionType.COMMIT.baseBundleKey + ".ConfirmMessage");
        int messageType = JOptionPane.INFORMATION_MESSAGE;

        try {
            rpc.commit(projectName, userName, changes.toArray(new String[0]), commitMessage);
            SwingUtilities.invokeLater(new Thread(() -> showConfirmPopup(message, messageType)));
        } catch (Exception ex) {
            ErrorUtil.showError(ex);
        }
    }

    public static void handlePullAction(boolean importTags, boolean importTheme, boolean importImages) {
        String message = BundleUtil.get().getStringLenient(GitActionType.PULL.baseBundleKey + ".ConfirmMessage");
        int messageType = JOptionPane.INFORMATION_MESSAGE;

        try {
            rpc.pull(projectName, userName, importTags, importTheme, importImages);
            SwingUtilities.invokeLater(new Thread(() -> showConfirmPopup(message, messageType)));
        } catch (Exception ex) {
            ErrorUtil.showError(ex);
        }
    }

    /**
     * Checks if multiple projects are Git-tracked on this gateway and warns the user
     * that exported tags are gateway-scoped and will be committed to the current project's repo.
     *
     * @return true if the user confirms (or there's only one project), false if cancelled
     */
    private static boolean confirmExportMultiProject(String currentProject) {
        try {
            List<String> trackedProjects = rpc.getGitTrackedProjectNames();
            if (trackedProjects.size() > 1) {
                StringBuilder sb = new StringBuilder();
                sb.append("Multiple Git-tracked projects detected on this gateway:\n\n");
                for (String name : trackedProjects) {
                    if (name.equals(currentProject)) {
                        sb.append("  \u2192 ").append(name).append(" (current)\n");
                    } else {
                        sb.append("     ").append(name).append("\n");
                    }
                }
                sb.append("\nTags are gateway-scoped resources shared across all projects.\n");
                sb.append("This export will write tags to the '").append(currentProject).append("' repository.\n\n");
                sb.append("Make sure this is the correct project for tracking tag changes.\n");
                sb.append("Consider configuring .tag-config.json with 'includedProviders'\n");
                sb.append("to limit which tag providers each project exports.\n\n");
                sb.append("Continue with export?");

                int choice = JOptionPane.showConfirmDialog(
                        context.getFrame(),
                        sb.toString(),
                        "Multi-Project Gateway Warning",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE);

                return choice == JOptionPane.YES_OPTION;
            }
        } catch (Exception e) {
            logger.warn("Unable to check for other Git-tracked projects: {}", e.getMessage());
        }
        return true;
    }

    public static void handleAction(GitActionType type) {
        String message = BundleUtil.get().getStringLenient(type.baseBundleKey + ".ConfirmMessage");
        int messageType = JOptionPane.INFORMATION_MESSAGE;
        boolean confirmPopup = Boolean.TRUE;

        try {
            switch (type) {
                case PULL:
                    confirmPopup = Boolean.FALSE;
                    showPullPopup(projectName, userName);
                    break;
                case PUSH:
                    confirmPopup = Boolean.FALSE;
                    showPushWithProductionCheck(projectName, userName);
                    break;
                case COMMIT:
                    confirmPopup = Boolean.FALSE;
                    showCommitPopup(projectName, userName);
                    break;
                case EXPORT:
                    if (!confirmExportMultiProject(projectName)) {
                        confirmPopup = Boolean.FALSE;
                        break;
                    }
                    rpc.exportConfig(projectName);
                    break;
                case REPO:
                    openRepositoryLink();
                    break;
                case HISTORY:
                    confirmPopup = Boolean.FALSE;
                    showHistoryViewer(projectName, userName);
                    break;
                case BRANCH:
                    confirmPopup = Boolean.FALSE;
                    showBranchPopup(projectName, userName);
                    break;
                case IMPORT:
                    confirmPopup = Boolean.FALSE;
                    showImportResourcesPopup(projectName);
                    break;
                case DOCS:
                    confirmPopup = Boolean.FALSE;
                    openDocsViewer(projectName);
                    break;
            }
            if(confirmPopup) SwingUtilities.invokeLater(new Thread(() -> showConfirmPopup(message, messageType)));
        } catch (Exception ex) {
            ErrorUtil.showError(ex);
        }
    }
}
