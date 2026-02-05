package com.axone_io.ignition.git;

import com.axone_io.ignition.git.actions.GitBaseAction;
import com.axone_io.ignition.git.utils.IconUtils;
import com.inductiveautomation.ignition.client.gateway_interface.GatewayConnection;
import com.inductiveautomation.ignition.common.BundleUtil;
import com.inductiveautomation.ignition.common.ConcurrencySessionInfo;
import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.common.resourcecollection.ChangeOperation;
import com.inductiveautomation.ignition.common.rpc.proto.ProtoRpcSerializer;
import com.inductiveautomation.ignition.designer.gui.DesignerToolbar;
import com.inductiveautomation.ignition.designer.gui.StatusBar;
import com.inductiveautomation.ignition.designer.model.DesignerContext;
import com.inductiveautomation.ignition.common.script.ScriptManager;
import com.inductiveautomation.ignition.designer.model.AbstractDesignerModuleHook;
import com.inductiveautomation.ignition.designer.model.SaveContext;
import com.jidesoft.action.DockableBarManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.Timer;
import java.util.*;
import java.util.List;

public class DesignerHook extends AbstractDesignerModuleHook {
    private static final Logger logger = LoggerFactory.getLogger(DesignerHook.class);

    public static GitScriptInterface rpc;
    public static List<ChangeOperation> changes = new ArrayList<>();
    public static DesignerContext context;
    public static String projectName;
    public static String userName;
    JPanel gitStatusBar;
    Timer gitUserTimer;
    @Override
    public void initializeScriptManager(ScriptManager manager) {
        super.initializeScriptManager(manager);

        /*manager.addScriptModule(
            "system.git",
            new ClientScriptModule(),
            new PropertiesFileDocProvider()
        );*/
    }

    @Override
    public void startup(DesignerContext context, LicenseState activationState) throws Exception {
        super.startup(context, activationState);
        DesignerHook.context = context;
        BundleUtil.get().addBundle("DesignerHook", getClass(), "DesignerHook");

        // Initialize RPC interface using new 8.3+ pattern
        // Get RPC interface via GatewayConnection using ProtoRpcSerializer
        try {
            rpc = GatewayConnection.getRpcInterface(
                    ProtoRpcSerializer.DEFAULT_INSTANCE,
                    "com.axone_io.ignition.git",  // Module ID
                    GitScriptInterface.class
            );

            if (rpc == null) {
                String errorMsg = "Failed to initialize Git module: RPC interface is null. " +
                        "Unable to communicate with gateway. Ensure the Git gateway module is installed and running.";
                logger.error(errorMsg);
                throw new IllegalStateException(errorMsg);
            }
        } catch (Exception e) {
            String errorMsg = "Failed to initialize Git module RPC interface: " + e.getMessage() +
                    ". Unable to communicate with gateway. Ensure the Git gateway module is installed and running.";
            logger.error(errorMsg, e);
            throw new IllegalStateException(errorMsg, e);
        }

        projectName = context.getProjectName();

        Optional<ConcurrencySessionInfo> sessionInfo = context.getResourceEditManager().getCurrentSessionInfo();
        userName = sessionInfo.isPresent() ? sessionInfo.get().username() : "";

        // Attempt to setup local Git repository
        try {
            logger.info("Attempting Git setup for project: '{}', user: '{}'", projectName, userName);
            rpc.setupLocalRepo(projectName, userName);
            logger.info("Git setup successful for project: '{}'", projectName);
        } catch (Exception e) {
            logger.warn("Git not configured for project '{}' and user '{}': {}. Module will load but Git features may not work.",
                        projectName, userName, e.getMessage());
            logger.debug("Git setup error details:", e);
            // Continue - module loads but Git features may not be fully functional
        }

        initStatusBar();
        initToolBar();

    }

    private void initStatusBar(){
        StatusBar statusBar = context.getStatusBar();
        gitStatusBar = new JPanel();

        JLabel gitIconLabel = new JLabel(IconUtils.getIcon("/com/axone_io/ignition/git/icons/ic_git.svg"));
        gitIconLabel.setSize(35, 35);
        gitStatusBar.add(gitIconLabel);

        gitStatusBar.add(new JLabel(userName));

        // Check if user is registered (with error handling)
        boolean userValid = false;
        try {
            userValid = rpc.isRegisteredUser(projectName, userName);
        } catch (Exception e) {
            logger.warn("Unable to check if user is registered: {}", e.getMessage());
        }

        String userIconPath = userValid ? "/com/axone_io/ignition/git/icons/ic_verified_user.svg" : "/com/axone_io/ignition/git/icons/ic_unregister_user.svg";
        JLabel labelUserIcon = new JLabel(IconUtils.getIcon(userIconPath));
        labelUserIcon.setSize(35,35);
        gitStatusBar.add(labelUserIcon);

        statusBar.addDisplay(gitStatusBar);

        gitUserTimer = new Timer(10000, e -> {
            try {
                boolean valid = rpc.isRegisteredUser(projectName, userName);
                String userIconPath1 = valid ? "/com/axone_io/ignition/git/icons/ic_verified_user.svg" : "/com/axone_io/ignition/git/icons/ic_unregister_user.svg";
                labelUserIcon.setIcon(IconUtils.getIcon(userIconPath1));
            } catch (Exception ex) {
                logger.debug("Unable to check user registration status: {}", ex.getMessage());
            }
        });

        gitUserTimer.start();
    }

    private void initToolBar() {
        DockableBarManager toolBarManager = context.getToolbarManager();
        DesignerToolbar toolbar = new DesignerToolbar("Git", "DesignerHook.Toolbar.Name");
        toolbar.add(new GitBaseAction(GitBaseAction.GitActionType.PUSH));
        toolbar.add(new GitBaseAction(GitBaseAction.GitActionType.PULL));
        toolbar.add(new GitBaseAction(GitBaseAction.GitActionType.COMMIT));
        toolbar.add(new GitBaseAction(GitBaseAction.GitActionType.BRANCH));
        toolbar.add(new GitBaseAction(GitBaseAction.GitActionType.HISTORY));
        toolbar.add(new GitBaseAction(GitBaseAction.GitActionType.IMPORT));
        toolbar.add(new GitBaseAction(GitBaseAction.GitActionType.EXPORT));
        toolbar.add(new GitBaseAction(GitBaseAction.GitActionType.REPO));

        toolBarManager.addDockableBar(toolbar);
    }

    @Override
    public void notifyProjectSaveStart(SaveContext save) {
        try {
            changes = context.getProject().getChanges();
            super.notifyProjectSaveStart(save);
        } catch (Exception e) {
            logger.error("Error in notifyProjectSaveStart", e);
            changes = null;
        }
    }

    @Override
    public void notifyProjectSaveDone(){
        super.notifyProjectSaveDone();
    }

    @Override
    public void shutdown() {
        super.shutdown();

        DockableBarManager toolBarManager = context.getToolbarManager();
        toolBarManager.removeDockableBar("Git");

        StatusBar statusBar = context.getStatusBar();
        statusBar.removeDisplay(gitStatusBar);

        gitUserTimer.stop();
    }
}
