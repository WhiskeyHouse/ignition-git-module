package com.axone_io.ignition.git;

import com.axone_io.ignition.git.dto.HotfixResult;
import com.axone_io.ignition.git.dto.ProductionModeConfig;
import com.inductiveautomation.ignition.common.rpc.RpcInterface;
import java.util.List;

@RpcInterface(packageId = "com.axone_io.ignition.git")
public interface GitScriptInterface {

    boolean pull(String projectName, String userName, boolean importTags, boolean importTheme,
                 boolean importImages) throws Exception;
    boolean push(String projectName, String userName) throws Exception;
    boolean commit(String projectName, String userName, String[] changes, String message);
    List<UncommittedChange> getUncommitedChanges(String projectName, String userName);
    boolean isRegisteredUser(String projectName, String userName);
    boolean exportConfig(String projectName);
    void setupLocalRepo(String projectName, String userName) throws Exception;
    List<CommitInfo> getCommitHistory(String projectName, String userName, int maxCount);

    // Branch management methods
    List<BranchInfo> listBranches(String projectName, String userName) throws Exception;
    boolean fetchFromRemote(String projectName, String userName) throws Exception;
    String getCurrentBranch(String projectName) throws Exception;
    BranchStatus getBranchStatus(String projectName, String userName) throws Exception;
    boolean switchBranch(String projectName, String userName, String branchName, boolean createNew, boolean forceCheckout) throws Exception;

    // Stash operations
    boolean stashChanges(String projectName, String userName, String message) throws Exception;
    boolean stashPop(String projectName, String userName) throws Exception;
    boolean discardAllChanges(String projectName, String userName) throws Exception;

    // Merge conflict operations
    List<String> getConflictingFiles(String projectName) throws Exception;
    boolean resolveConflicts(String projectName, String strategy) throws Exception;
    boolean abortMerge(String projectName) throws Exception;
    boolean hasConflicts(String projectName) throws Exception;

    // Import resources from repo (without pull)
    boolean importResources(String projectName, boolean importTags, boolean importTheme, boolean importImages, String collisionPolicy) throws Exception;

    // Import only tags from the working tree into gateway tag providers (no pull).
    // collisionPolicy may be null/empty -> falls back to the repo's tag_config policy.
    boolean importTags(String projectName, String collisionPolicy) throws Exception;

    // Multi-project awareness
    List<String> getGitTrackedProjectNames();

    // Docs discovery
    List<String> listDocsForResource(String projectName, String resourcePath) throws Exception;

    // Production mode operations
    ProductionModeConfig getProductionModeConfig(String projectName) throws Exception;

    /**
     * Describe commits on the production branch that have not reached the remote (typically an
     * unmerged hotfix), or {@code null} when there are none. Advisory only — never blocks.
     */
    String getUnpushedProductionCommits(String projectName) throws Exception;
    boolean validateProductionModePull(String projectName, String userName) throws Exception;
    boolean validateProductionModePush(String projectName, String userName, String targetBranch) throws Exception;
    List<String> listRepositoryTags(String projectName) throws Exception;
    boolean backupCurrentTags(String projectName) throws Exception;
    boolean restoreTagsFromBackup(String projectName) throws Exception;

    // Hotfix operations
    HotfixResult executeHotfix(String projectName, String userName,
                               String hotfixDescription, String commitMessage,
                               String[] changes) throws Exception;
    HotfixResult getHotfixProgress(String projectName) throws Exception;
    HotfixResult getLastHotfixStatus(String projectName) throws Exception;

    // Diagnostic (#2): probe getTagGroupsAsync() per provider. Temporary.
    String diagnoseTagGroups() throws Exception;

}
