package com.axone_io.ignition.git;

import com.axone_io.ignition.git.actions.GitBaseAction;
import com.axone_io.ignition.git.DirtyStatePolicy;
import com.axone_io.ignition.git.dto.ProductionModeConfig;
import com.axone_io.ignition.git.dto.RepoDirtyState;
import com.axone_io.ignition.git.managers.DocsPopupMenuListener;
import com.axone_io.ignition.git.managers.GitActionManager;
import com.axone_io.ignition.git.managers.GitWorkflowPrompter;
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
    // Written on the save worker thread in notifyProjectSaveStart, read in notifyProjectSaveDone
    // (which Ignition dispatches on a separate executor thread).
    private static volatile PendingProductionCommit pendingProductionCommit;
    private Timer productionConfigRefreshTimer;

    Timer repoDirtyTimer;
    JLabel pendingChangesBadge;
    Timer pendingBadgePulseTimer;
    private boolean pulseBright = false;
    private RepoDirtyState lastKnownDirtyState;
    private long dismissedRevision = DirtyStatePolicy.NEVER_DISMISSED;
    /**
     * Set when a production save completes, cleared by the first poll that sees the resulting
     * drift. Written on the save thread and read on the EDT, hence volatile.
     *
     * @see DirtyStatePolicy#resolveDismissedRevision
     */
    private volatile boolean dismissDriftAfterSave = false;
    private boolean dirtyCheckInFlight = false;
    private boolean gitConfigured = true;

    private static final java.awt.Color BADGE_DIM = new java.awt.Color(191, 110, 0);
    private static final java.awt.Color BADGE_BRIGHT = new java.awt.Color(245, 158, 11);

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
            gitConfigured = false;
        }

        initStatusBar();
        initToolBar();
        initDocsContextMenu();

        // Cache production mode config for save-hook detection
        refreshProductionConfig();
        productionConfigRefreshTimer = new Timer(300000, e -> refreshProductionConfig()); // 5 minutes
        productionConfigRefreshTimer.start();

        initRepoDirtyPolling();
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

        // Pending-changes badge — shown after the user dismisses a commit prompt while the
        // working tree is still dirty. Pulses so it reads as an outstanding action rather
        // than decoration, and clears only when the changes are actually committed.
        pendingChangesBadge = new JLabel(" UNCOMMITTED ");
        pendingChangesBadge.setFont(new java.awt.Font("Dialog", java.awt.Font.BOLD, 10));
        pendingChangesBadge.setForeground(java.awt.Color.WHITE);
        pendingChangesBadge.setBackground(BADGE_DIM);
        pendingChangesBadge.setOpaque(true);
        pendingChangesBadge.setToolTipText("Uncommitted changes — click to commit");
        pendingChangesBadge.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        pendingChangesBadge.setBorder(javax.swing.BorderFactory.createCompoundBorder(
            javax.swing.BorderFactory.createLineBorder(new java.awt.Color(230, 145, 56), 1),
            javax.swing.BorderFactory.createEmptyBorder(2, 6, 2, 6)
        ));
        pendingChangesBadge.setVisible(false);
        pendingChangesBadge.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                // Clicking is an explicit request to see the prompt again for these changes.
                dismissedRevision = DirtyStatePolicy.NEVER_DISMISSED;
                applyDirtyState();
            }
        });
        gitStatusBar.add(pendingChangesBadge);

        pendingBadgePulseTimer = new Timer(700, e -> {
            pulseBright = !pulseBright;
            pendingChangesBadge.setBackground(pulseBright ? BADGE_BRIGHT : BADGE_DIM);
            pendingChangesBadge.repaint();
        });

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

    /**
     * Polls working-tree drift. Tag and UDT edits never reach the project-save hook, so
     * polling is the only way the Designer learns about them.
     */
    private void initRepoDirtyPolling() {
        if (!gitConfigured) {
            logger.info("Git is not configured for project '{}'; not polling for uncommitted changes.",
                    projectName);
            return;
        }
        repoDirtyTimer = new Timer(5000, e -> checkRepoDirtyState());
        repoDirtyTimer.start();
    }

    private void checkRepoDirtyState() {
        if (!gitConfigured || dirtyCheckInFlight || GitWorkflowPrompter.isPromptOpen()) {
            return;
        }
        dirtyCheckInFlight = true;

        SwingWorker<RepoDirtyState, Void> worker = new SwingWorker<RepoDirtyState, Void>() {
            @Override
            protected RepoDirtyState doInBackground() {
                return rpc.getRepoDirtyState(projectName, userName);
            }

            @Override
            protected void done() {
                try {
                    RepoDirtyState state = get();
                    if (state != null) {
                        lastKnownDirtyState = state;
                    }
                } catch (Exception ex) {
                    // Fail quiet: a background poller must not turn a flapping connection into
                    // repeated dialogs. The save-time production warning still fails
                    // conservative, so the moment that changes a gateway stays guarded.
                    logger.debug("Unable to poll repository state: {}", ex.getMessage());
                } finally {
                    dirtyCheckInFlight = false;
                    applyDirtyState();
                }
            }
        };
        worker.execute();
    }

    private void applyDirtyState() {
        RepoDirtyState state = lastKnownDirtyState;

        // First poll after a production save carries the change set the checklist showed.
        long resolved = DirtyStatePolicy.resolveDismissedRevision(
                state, dismissedRevision, dismissDriftAfterSave);
        if (resolved != dismissedRevision) {
            dismissedRevision = resolved;
            dismissDriftAfterSave = false;
        }

        DirtyStatePolicy.Action action =
                DirtyStatePolicy.decide(state, dismissedRevision, GitWorkflowPrompter.isPromptOpen());

        if (action == DirtyStatePolicy.Action.PROMPT) {
            long revision = state.getRevision();
            GitWorkflowPrompter.prompt(state, projectName, userName, () -> {
                dismissedRevision = revision;
                updatePendingBadge();
            });
        }
        updatePendingBadge();
    }

    private void updatePendingBadge() {
        if (pendingChangesBadge == null) {
            return;
        }
        boolean show = DirtyStatePolicy.shouldShowBadge(
                lastKnownDirtyState, dismissedRevision, pendingChangesBadge.isVisible());
        SwingUtilities.invokeLater(() -> {
            if (show == pendingChangesBadge.isVisible()) {
                return;
            }
            pendingChangesBadge.setVisible(show);
            if (show) {
                pendingBadgePulseTimer.start();
            } else {
                pendingBadgePulseTimer.stop();
                pendingChangesBadge.setBackground(BADGE_DIM);
            }
        });
    }

    /**
     * Production gate for saves.
     *
     * <p>Saving is the moment the production gateway actually changes, and
     * {@code notifyProjectSaveStart} is the only point at which Ignition lets a module stop a
     * save: {@code IgnitionDesigner.commitAll()} reduces every module hook's result with
     * {@code Boolean.logicalAnd}, and a hook that throws is recorded as {@code false}, which
     * makes {@code handleSave} return before any resource is written. So the safety checklist
     * and the commit details are both collected here \u2014 cancelling either one aborts the save
     * and leaves the changes unsaved and still open in the Designer.</p>
     *
     * <p>The authorised commit is parked in {@link #pendingProductionCommit} and executed in
     * {@link #notifyProjectSaveDone()}, once the resources are actually on disk.</p>
     */
    @Override
    public void notifyProjectSaveStart(SaveContext save) throws Exception {
        pendingProductionCommit = null;

        boolean pendingChangesUnknown = false;
        try {
            changes = context.getProject().getChanges();
            super.notifyProjectSaveStart(save);
        } catch (Exception e) {
            logger.error("Error in notifyProjectSaveStart", e);
            changes = null;
            pendingChangesUnknown = true;
        }

        ProductionModeConfig config = resolveProductionConfigForSave();
        if (config == null || !config.isProductionMode()) {
            // Outside production mode this snapshot only pre-ticks checkboxes in the commit
            // dialog, so losing it is cosmetic and the save proceeds as it always did.
            return;
        }

        // In production mode the snapshot is what tells the commit dialog which resources this
        // save is about to write. Without it the dialog lists only the pre-existing working-tree
        // changes, so the user can authorise a commit that silently excludes everything they
        // just saved — leaving new resources live on the gateway and uncommitted. Refuse the
        // save rather than show a dialog that misrepresents it.
        if (pendingChangesUnknown) {
            throw new SaveCancelledException(
                    "Save cancelled — the set of changes being saved could not be determined, so the "
                            + "production commit dialog cannot be trusted to show what this save writes. "
                            + "Your changes have not been saved. Retry the save; if it keeps failing, "
                            + "check the Designer logs for the underlying error.");
        }

        PendingProductionCommit authorised =
                GitActionManager.promptForProductionSave(projectName, userName, config);
        if (authorised == null) {
            // The only way to veto the save is to throw; this message is surfaced to the user.
            throw new SaveCancelledException(
                    "Save cancelled \u2014 production mode confirmation was not completed. "
                            + "Your changes have not been saved and are still open in the Designer.");
        }
        pendingProductionCommit = authorised;
    }

    /**
     * Production config for the save gate, fetched fresh every time.
     *
     * <p>Deliberately does <em>not</em> consult {@link #cachedProductionConfig}. That cache is
     * refreshed on a five-minute timer, so a gateway switched into production mode since the
     * last refresh would report {@code false} here and let a save through completely ungated \u2014
     * a window in which the feature simply does not exist. The cache still backs the status-bar
     * badge and the branch-switch guard, where staleness is cosmetic rather than a safety hole.</p>
     *
     * <p>One RPC per save is a fair price for a gate that decides whether a production gateway
     * gets modified. An unreachable gateway is treated as production rather than failing open.</p>
     */
    private ProductionModeConfig resolveProductionConfigForSave() {
        try {
            ProductionModeConfig config = rpc.getProductionModeConfig(projectName);
            cachedProductionConfig = config;
            return config;
        } catch (Exception e) {
            logger.warn("Unable to verify production mode before save; gating conservatively", e);
            ProductionModeConfig fallback = new ProductionModeConfig(true, null, null);
            fallback.setWarningMessage("Unable to verify production mode for this gateway: " + e.getMessage());
            return fallback;
        }
    }

    @Override
    public void notifyProjectSaveDone() {
        super.notifyProjectSaveDone();

        PendingProductionCommit authorised = pendingProductionCommit;
        pendingProductionCommit = null;

        // Only a production save has already shown the user its changes, in the safety checklist,
        // so only it suppresses the poller's prompt — an ordinary save should still raise the
        // usual "commit now?" dialog. Arming here rather than at the checklist means an aborted
        // save (which never reaches this method) can never leave a dismissal armed for whatever
        // drift happens to come next.
        if (authorised != null) {
            dismissDriftAfterSave = true;
        }

        // A project save dirties the tree immediately; don't make the user wait for the poll.
        SwingUtilities.invokeLater(this::checkRepoDirtyState);

        if (authorised == null) {
            return;
        }

        // Commits in production mode auto-push, keeping the remote in sync with the gateway;
        // on the production branch this runs the hotfix pipeline instead.
        GitActionManager.executeProductionCommit(projectName, userName, authorised);
    }

    /** Signals a user-cancelled save. Thrown to make {@code commitAll()} abort the save. */
    private static class SaveCancelledException extends Exception {
        SaveCancelledException(String message) {
            super(message);
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

        if (repoDirtyTimer != null) {
            repoDirtyTimer.stop();
        }
        if (pendingBadgePulseTimer != null) {
            pendingBadgePulseTimer.stop();
        }

        if (sharedPopupMenuRef != null && docsPopupListener != null) {
            sharedPopupMenuRef.removePopupMenuListener(docsPopupListener);
        }
    }
}
