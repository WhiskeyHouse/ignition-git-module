package com.axone_io.ignition.git;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Decides whether a given project is allowed to export the gateway's shared resources.
 *
 * <p>Tags, themes and images are gateway-scoped, not project-scoped:</p>
 * <ul>
 *   <li>tags come from {@code context.getTagManager().getTagProviders()}</li>
 *   <li>images come from {@code context.getImageManager()}</li>
 *   <li>themes come from {@code data/modules/com.inductiveautomation.perspective/themes}</li>
 * </ul>
 *
 * <p>But the export runs per project. On a gateway hosting more than one git-backed project, every
 * project wrote a full copy of the same gateway state into its own repository and wiped and rebuilt
 * that copy on its own schedule. Two projects committing at different moments produce two divergent
 * snapshots of a single gateway, and whichever one exports while the tag providers are still
 * registering publishes its truncated view as a deletion commit.</p>
 *
 * <p>So exactly one project owns these resources, and it has to be named explicitly. Inferring an
 * owner would reintroduce the same class of bug the moment a second project appeared.</p>
 */
public final class GatewayResourceExportPolicy {

    /** The {@code git.yaml} key that designates the owning project. */
    public static final String CONFIG_KEY = "gateway_exportResources";

    private GatewayResourceExportPolicy() {
    }

    /**
     * Explains why {@code projectName} must not export gateway-scoped resources, or returns
     * {@code null} when it is the designated owner and may proceed.
     *
     * <p>Callers should skip the export and log the reason rather than failing the whole
     * operation: an unconfigured gateway should still be able to commit its project resources,
     * and refusing to export is always safer than exporting from the wrong project.</p>
     *
     * @param projectName the project attempting the export
     * @param owners      names of every project with {@link #CONFIG_KEY} enabled; may be null
     */
    public static String describeSkipReason(String projectName, Collection<String> owners) {
        List<String> claimants = new ArrayList<>();
        if (owners != null) {
            for (String owner : owners) {
                if (owner != null && !owner.trim().isEmpty()) {
                    claimants.add(owner.trim());
                }
            }
        }

        if (claimants.isEmpty()) {
            return "no project on this gateway is designated to export gateway-scoped resources "
                    + "(tags, themes, images). Set '" + CONFIG_KEY + "' on exactly one project — in "
                    + "git.yaml or on the Gateway config page — so that one repository owns them. "
                    + "Project resources are still exported and committed as normal.";
        }

        if (claimants.size() > 1) {
            return "gateway-scoped resources are claimed by more than one project ("
                    + String.join(", ", claimants) + "). Ownership must be unambiguous, because "
                    + "each owner would wipe and rewrite the same gateway state into its own "
                    + "repository. Enable '" + CONFIG_KEY + "' on exactly one project.";
        }

        String owner = claimants.get(0);
        if (owner.equals(projectName)) {
            return null;
        }

        return "gateway-scoped resources (tags, themes, images) are owned by project '" + owner
                + "', so they are not exported from '" + projectName + "'.";
    }
}
