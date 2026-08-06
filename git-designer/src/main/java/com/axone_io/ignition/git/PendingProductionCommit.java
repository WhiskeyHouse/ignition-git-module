package com.axone_io.ignition.git;

import java.util.ArrayList;
import java.util.List;

/**
 * A production-mode commit the user authorised <em>before</em> the save ran.
 *
 * <p>Ignition only lets a module veto a save from {@code notifyProjectSaveStart}, so the
 * production checklist and the commit message are both collected there. Cancelling either
 * dialog aborts the save outright and nothing is written to the gateway. When the user
 * confirms, their answers are parked here until {@code notifyProjectSaveDone}, at which
 * point the resources are on disk and the git commit can run against them.</p>
 */
public class PendingProductionCommit {
    private final boolean hotfix;
    private final String hotfixDescription;
    private final String commitMessage;
    private final List<String> changes;

    public PendingProductionCommit(boolean hotfix, String hotfixDescription, String commitMessage,
                                   List<String> changes) {
        this.hotfix = hotfix;
        this.hotfixDescription = hotfixDescription;
        this.commitMessage = commitMessage;
        this.changes = changes == null ? new ArrayList<>() : new ArrayList<>(changes);
    }

    public boolean isHotfix() {
        return hotfix;
    }

    public String getHotfixDescription() {
        return hotfixDescription;
    }

    public String getCommitMessage() {
        return commitMessage;
    }

    public List<String> getChanges() {
        return changes;
    }

    public String[] getChangesArray() {
        return changes.toArray(new String[0]);
    }
}
