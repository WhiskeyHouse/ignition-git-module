package com.axone_io.ignition.git;

import com.axone_io.ignition.git.actions.GitBaseAction;
import com.axone_io.ignition.git.dto.ProductionModeConfig;
import com.axone_io.ignition.git.managers.DocsPopupMenuListener;
import com.axone_io.ignition.git.managers.GitActionManager;
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
import com.inductiveautomation.ignition.common.script.hints.PropertiesFileDocProvider;
import com.inductiveautomation.ignition.designer.model.AbstractDesignerModuleHook;
import com.inductiveautomation.ignition.designer.model.SaveContext;
import com.inductiveautomation.ignition.designer.navtree.model.AbstractNavTreeNode;
import com.inductiveautomation.ignition.designer.navtree.NavTreePanel;
import com.jidesoft.action.DockableBarManager;
import com.jidesoft.docking.DockableFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.event.PopupMenuListener;
import java.lang.reflect.Field;
import java.util.*;
import java.util.List;

public class DesignerHook extends AbstractDesignerModuleHook {
    private static final Logger logger = LoggerFactory.getLogger(DesignerHook.class);

    // volatile: written in startup(), read from script threads (system.git supplier) and the EDT
    public static volatile GitScriptInterface rpc;
    public static List<ChangeOperation> changes = new ArrayList<>();
    public static DesignerContext context;
    public static String projectName;
    public static String userName;
    JPanel gitStatusBar;
    JLabel productionBadge;
    Timer gitUserTimer;
    PopupMenuListener docsPopupListener;
    JPopupMenu sharedPopupMenuRef;
    private static ProductionModeConfig cachedProductionConfig;
    private Timer productionConfigRefreshTimer;

    @Override
    public void initializeScriptManager(ScriptManager manager) {
        super.initializeScriptManager(manager);

        // The static rpc field is populated in startup(), which can run AFTER this
        // method — GitScriptFunctions resolves the supplier lazily on each call.
        manager.addScriptModule(
                "system.git",
                new GitScriptFunctions(() -> rpc),
                new PropertiesFileDocProvider()
        );
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
        initDocsContextMenu();

        // Cache production mode config for save-hook detection
        refreshProductionConfig();
        productionConfigRefreshTimer = new Timer(300000, e -> refreshProductionConfig()); // 5 minutes
        productionConfigRefreshTimer.start();
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

        // Production mode badge — shown when production mode is active
        productionBadge = new JLabel(" PRODUCTION ");
        productionBadge.setFont(new java.awt.Font("Dialog", java.awt.Font.BOLD, 10));
        productionBadge.setForeground(java.awt.Color.WHITE);
        productionBadge.setBackground(new java.awt.Color(211, 47, 47));
        productionBadge.setOpaque(true);
        productionBadge.setBorder(javax.swing.BorderFactory.createCompoundBorder(
            javax.swing.BorderFactory.createLineBorder(new java.awt.Color(183, 28, 28), 1),
            javax.swing.BorderFactory.createEmptyBorder(2, 6, 2, 6)
        ));
        productionBadge.setVisible(false); // Hidden by default, shown after config check
        gitStatusBar.add(productionBadge);

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
        toolbar.add(new GitBaseAction(GitBaseAction.GitActionType.DOCS));
        toolbar.add(new GitBaseAction(GitBaseAction.GitActionType.REPO));

        toolBarManager.addDockableBar(toolbar);
    }

    private void initDocsContextMenu() {
        // Delay initialization to allow the NavTreePanel to be fully loaded
        Timer initTimer = new Timer(2000, e -> {
            try {
                DockableFrame frame = context.getDockingManager().getFrame(NavTreePanel.DOCKING_KEY);
                if (frame == null) {
                    logger.debug("NavTreePanel docking frame not found, skipping docs context menu init");
                    return;
                }

                // Find the JTree inside the NavTreePanel
                NavTreePanel navTreePanel = findNavTreePanel(frame);
                if (navTreePanel == null) {
                    logger.debug("NavTreePanel component not found, skipping docs context menu init");
                    return;
                }

                JTree navTree = navTreePanel.getTree();
                if (navTree == null) {
                    logger.debug("NavTree JTree not found, skipping docs context menu init");
                    return;
                }

                // Access the shared popup menu via reflection on AbstractNavTreeNode
                Field popupField = AbstractNavTreeNode.class.getDeclaredField("sharedPopupMenu");
                popupField.setAccessible(true);
                JPopupMenu sharedPopup = (JPopupMenu) popupField.get(null);

                if (sharedPopup == null) {
                    logger.debug("Shared popup menu is null, skipping docs context menu init");
                    return;
                }

                sharedPopupMenuRef = sharedPopup;
                docsPopupListener = new DocsPopupMenuListener(navTree);
                sharedPopup.addPopupMenuListener(docsPopupListener);

                logger.info("Docs context menu listener initialized successfully");

            } catch (NoSuchFieldException ex) {
                logger.warn("Could not find sharedPopupMenu field on AbstractNavTreeNode - " +
                           "docs context menu will not be available. " +
                           "This may indicate an incompatible Ignition SDK version.");
            } catch (Exception ex) {
                logger.warn("Failed to initialize docs context menu: {}", ex.getMessage());
                logger.debug("Docs context menu init error details:", ex);
            }
        });
        initTimer.setRepeats(false);
        initTimer.start();
    }

    private NavTreePanel findNavTreePanel(java.awt.Container container) {
        if (container instanceof NavTreePanel) {
            return (NavTreePanel) container;
        }
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof NavTreePanel) {
                return (NavTreePanel) child;
            }
            if (child instanceof java.awt.Container) {
                NavTreePanel found = findNavTreePanel((java.awt.Container) child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private void refreshProductionConfig() {
        try {
            cachedProductionConfig = rpc.getProductionModeConfig(projectName);
            logger.debug("Refreshed cached production config: {}", cachedProductionConfig);
            // Update status bar badge on EDT
            if (productionBadge != null) {
                boolean show = cachedProductionConfig != null && cachedProductionConfig.isProductionMode();
                SwingUtilities.invokeLater(() -> productionBadge.setVisible(show));
            }
        } catch (Exception e) {
            logger.debug("Unable to refresh production config cache: {}", e.getMessage());
        }
    }

    public static ProductionModeConfig getCachedProductionConfig() {
        return cachedProductionConfig;
    }

    /** Called after operations that may change production state (pull, branch switch, hotfix). */
    public static void invalidateProductionConfigCache() {
        cachedProductionConfig = null;
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
    public void notifyProjectSaveDone() {
        super.notifyProjectSaveDone();

        // In production mode, prompt the engineer to commit after saving
        if (cachedProductionConfig != null && cachedProductionConfig.isProductionMode()) {
            SwingUtilities.invokeLater(() -> {
                int result = JOptionPane.showConfirmDialog(
                    context.getFrame(),
                    "You've saved changes on a production gateway.\nWould you like to commit and track these changes?",
                    "Production Mode \u2014 Commit Changes?",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE
                );
                if (result == JOptionPane.YES_OPTION) {
                    GitActionManager.showCommitWithHotfixDetection(projectName, userName);
                }
            });
        }
    }

    @Override
    public void shutdown() {
        super.shutdown();

        DockableBarManager toolBarManager = context.getToolbarManager();
        toolBarManager.removeDockableBar("Git");

        StatusBar statusBar = context.getStatusBar();
        statusBar.removeDisplay(gitStatusBar);

        gitUserTimer.stop();

        if (productionConfigRefreshTimer != null) {
            productionConfigRefreshTimer.stop();
        }

        if (sharedPopupMenuRef != null && docsPopupListener != null) {
            sharedPopupMenuRef.removePopupMenuListener(docsPopupListener);
        }
    }
}
