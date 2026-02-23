package com.axone_io.ignition.git.managers;

import com.axone_io.ignition.git.DesignerHook;
import com.axone_io.ignition.git.GitScriptInterface;
import com.inductiveautomation.ignition.common.resourcecollection.ResourcePath;
import com.inductiveautomation.ignition.designer.navtree.model.AbstractResourceNavTreeNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.tree.TreePath;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listens on the shared project browser popup menu and injects "View Docs"
 * items when markdown files exist near the right-clicked resource.
 */
public class DocsPopupMenuListener implements PopupMenuListener {
    private static final Logger logger = LoggerFactory.getLogger(DocsPopupMenuListener.class);
    private static final long CACHE_TTL_MS = 30_000;

    private final JTree navTree;
    private final ConcurrentHashMap<String, CachedDocs> cache = new ConcurrentHashMap<>();

    public DocsPopupMenuListener(JTree navTree) {
        this.navTree = navTree;
    }

    @Override
    public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
        JPopupMenu popup = (JPopupMenu) e.getSource();

        try {
            TreePath selectionPath = navTree.getSelectionPath();
            if (selectionPath == null) {
                return;
            }

            Object node = selectionPath.getLastPathComponent();
            if (!(node instanceof AbstractResourceNavTreeNode)) {
                return;
            }

            AbstractResourceNavTreeNode resourceNode = (AbstractResourceNavTreeNode) node;

            // ResourcePath.toString() can throw NameInvalidException for nodes
            // with names like '.bin' that Ignition considers invalid
            ResourcePath rp;
            String resourcePath;
            try {
                rp = resourceNode.getResourcePath();
                if (rp == null) {
                    return;
                }
                resourcePath = rp.toString();
            } catch (Exception pathEx) {
                logger.debug("Skipping node with invalid resource path: {}", resourceNode.getName());
                return;
            }

            String projectName = DesignerHook.projectName;

            logger.debug("Docs context menu: node='{}', toString='{}', moduleId='{}', type='{}', folderPath='{}'",
                    resourceNode.getName(), resourcePath, rp.getModuleId(), rp.getType(), rp.getFolderPath());

            if (projectName == null || projectName.isEmpty() || resourcePath == null || resourcePath.isEmpty()) {
                return;
            }

            // Check cache first
            String cacheKey = projectName + ":" + resourcePath;
            CachedDocs cached = cache.get(cacheKey);
            if (cached != null && !cached.isExpired()) {
                injectMenuItems(popup, projectName, cached.docs);
                return;
            }

            // Call RPC to discover docs
            GitScriptInterface rpc = DesignerHook.rpc;
            if (rpc == null) {
                return;
            }

            List<String> docs = rpc.listDocsForResource(projectName, resourcePath);
            logger.debug("Docs context menu: RPC returned {} docs for path '{}': {}", docs.size(), resourcePath, docs);
            cache.put(cacheKey, new CachedDocs(docs));

            injectMenuItems(popup, projectName, docs);

        } catch (Exception ex) {
            // Silent failure - docs are a convenience feature
            logger.debug("Error discovering docs for context menu", ex);
        }
    }

    private void injectMenuItems(JPopupMenu popup, String projectName, List<String> docs) {
        if (docs == null || docs.isEmpty()) {
            return;
        }

        popup.addSeparator();

        if (docs.size() == 1) {
            String docPath = docs.get(0);
            String fileName = docPath.contains("/")
                    ? docPath.substring(docPath.lastIndexOf('/') + 1)
                    : docPath;
            JMenuItem item = new JMenuItem("View Docs: " + fileName);
            item.addActionListener(ev -> GitActionManager.openDocsViewer(projectName, docPath));
            popup.add(item);
        } else {
            JMenu submenu = new JMenu("View Docs");
            for (String docPath : docs) {
                String fileName = docPath.contains("/")
                        ? docPath.substring(docPath.lastIndexOf('/') + 1)
                        : docPath;
                // Show directory context for disambiguation
                String parentDir = "";
                if (docPath.contains("/")) {
                    String dir = docPath.substring(0, docPath.lastIndexOf('/'));
                    if (dir.contains("/")) {
                        parentDir = dir.substring(dir.lastIndexOf('/') + 1);
                    } else {
                        parentDir = dir;
                    }
                }
                String label = parentDir.isEmpty() ? fileName : fileName + "  (" + parentDir + ")";
                JMenuItem item = new JMenuItem(label);
                final String path = docPath;
                item.addActionListener(ev -> GitActionManager.openDocsViewer(projectName, path));
                submenu.add(item);
            }
            popup.add(submenu);
        }
    }

    @Override
    public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
        // no-op
    }

    @Override
    public void popupMenuCanceled(PopupMenuEvent e) {
        // no-op
    }

    private static class CachedDocs {
        final List<String> docs;
        final long timestamp;

        CachedDocs(List<String> docs) {
            this.docs = docs;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }
}
