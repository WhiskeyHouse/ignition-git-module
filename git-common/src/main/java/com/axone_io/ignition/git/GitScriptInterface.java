package com.axone_io.ignition.git;

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

    // Multi-project awareness
    List<String> getGitTrackedProjectNames();

    // Docs discovery
    List<String> listDocsForResource(String projectName, String resourcePath) throws Exception;

}
