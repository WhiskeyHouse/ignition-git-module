package com.axone_io.ignition.git;

import com.axone_io.ignition.git.dto.HotfixResult;
import com.axone_io.ignition.git.dto.ProductionModeConfig;
import com.axone_io.ignition.git.dto.RepoDirtyState;

import java.util.List;

public class ClientScriptModule extends AbstractScriptModule {

    private final GitScriptInterface rpc;

    /**
     * Constructor that accepts an RPC interface.
     * In 8.3+, obtain the RPC interface using:
     * context.getGatewayInterface().getRpcInterface(GitScriptInterface.class)
     */
    public ClientScriptModule(GitScriptInterface rpc) {
        this.rpc = rpc;
    }

    @Override
    protected boolean pullImpl(String projectName, String userName, boolean importTags, boolean importTheme,
                               boolean importImages) throws Exception {
        return rpc.pull(projectName, userName, importTags, importTheme, importImages);
    }

    @Override
    protected boolean pushImpl(String projectName, String userName) throws Exception {
        return rpc.push(projectName, userName);
    }

    @Override
    protected boolean commitImpl(String projectName, String userName, String[] changes, String message) {
        return rpc.commit(projectName, userName, changes, message);
    }

    @Override
    protected List<UncommittedChange> getUncommitedChangesImpl(String projectName, String userName) {
        return rpc.getUncommitedChanges(projectName, userName);
    }

    @Override
    protected RepoDirtyState getRepoDirtyStateImpl(String projectName, String userName) {
        return rpc.getRepoDirtyState(projectName, userName);
    }

    @Override
    protected boolean isRegisteredUserImpl(String projectName, String userName){
        return rpc.isRegisteredUser(projectName, userName);
    }

    @Override
    protected boolean exportConfigImpl(String projectName) {
        return rpc.exportConfig(projectName);
    }

    @Override
    protected void setupLocalRepoImpl(String projectName, String userName) throws Exception {
        rpc.setupLocalRepo(projectName, userName);
    }

    @Override
    protected List<CommitInfo> getCommitHistoryImpl(String projectName, String userName, int maxCount) {
        return rpc.getCommitHistory(projectName, userName, maxCount);
    }

    @Override
    protected List<BranchInfo> listBranchesImpl(String projectName, String userName) throws Exception {
        return rpc.listBranches(projectName, userName);
    }

    @Override
    protected boolean fetchFromRemoteImpl(String projectName, String userName) throws Exception {
        return rpc.fetchFromRemote(projectName, userName);
    }

    @Override
    protected String getCurrentBranchImpl(String projectName) throws Exception {
        return rpc.getCurrentBranch(projectName);
    }

    @Override
    protected BranchStatus getBranchStatusImpl(String projectName, String userName) throws Exception {
        return rpc.getBranchStatus(projectName, userName);
    }

    @Override
    protected boolean switchBranchImpl(String projectName, String userName, String branchName, boolean createNew, boolean forceCheckout) throws Exception {
        return rpc.switchBranch(projectName, userName, branchName, createNew, forceCheckout);
    }

    @Override
    protected boolean stashChangesImpl(String projectName, String userName, String message) throws Exception {
        return rpc.stashChanges(projectName, userName, message);
    }

    @Override
    protected boolean stashPopImpl(String projectName, String userName) throws Exception {
        return rpc.stashPop(projectName, userName);
    }

    @Override
    protected boolean discardAllChangesImpl(String projectName, String userName) throws Exception {
        return rpc.discardAllChanges(projectName, userName);
    }

    @Override
    protected List<String> getConflictingFilesImpl(String projectName) throws Exception {
        return rpc.getConflictingFiles(projectName);
    }

    @Override
    protected boolean resolveConflictsImpl(String projectName, String strategy) throws Exception {
        return rpc.resolveConflicts(projectName, strategy);
    }

    @Override
    protected boolean abortMergeImpl(String projectName) throws Exception {
        return rpc.abortMerge(projectName);
    }

    @Override
    protected boolean hasConflictsImpl(String projectName) throws Exception {
        return rpc.hasConflicts(projectName);
    }

    @Override
    protected boolean importResourcesImpl(String projectName, boolean importTags, boolean importTheme, boolean importImages, String collisionPolicy) throws Exception {
        return rpc.importResources(projectName, importTags, importTheme, importImages, collisionPolicy);
    }

    @Override
    protected boolean importTagsImpl(String projectName, String collisionPolicy) throws Exception {
        return rpc.importTags(projectName, collisionPolicy);
    }

    @Override
    protected List<String> getGitTrackedProjectNamesImpl() {
        return rpc.getGitTrackedProjectNames();
    }

    @Override
    protected List<String> listDocsForResourceImpl(String projectName, String resourcePath) throws Exception {
        return rpc.listDocsForResource(projectName, resourcePath);
    }

    @Override
    protected ProductionModeConfig getProductionModeConfigImpl(String projectName) throws Exception {
        return rpc.getProductionModeConfig(projectName);
    }

    @Override
    protected String getUnpushedProductionCommitsImpl(String projectName) throws Exception {
        return rpc.getUnpushedProductionCommits(projectName);
    }

    @Override
    protected boolean validateProductionModePullImpl(String projectName, String userName) throws Exception {
        return rpc.validateProductionModePull(projectName, userName);
    }

    @Override
    protected boolean validateProductionModePushImpl(String projectName, String userName, String targetBranch) throws Exception {
        return rpc.validateProductionModePush(projectName, userName, targetBranch);
    }

    @Override
    protected List<String> listRepositoryTagsImpl(String projectName) throws Exception {
        return rpc.listRepositoryTags(projectName);
    }

    @Override
    protected boolean backupCurrentTagsImpl(String projectName) throws Exception {
        return rpc.backupCurrentTags(projectName);
    }

    @Override
    protected boolean restoreTagsFromBackupImpl(String projectName) throws Exception {
        return rpc.restoreTagsFromBackup(projectName);
    }

    @Override
    protected HotfixResult executeHotfixImpl(String projectName, String userName,
                                             String hotfixDescription, String commitMessage,
                                             String[] changes) throws Exception {
        return rpc.executeHotfix(projectName, userName, hotfixDescription, commitMessage, changes);
    }

    @Override
    protected HotfixResult getHotfixProgressImpl(String projectName) throws Exception {
        return rpc.getHotfixProgress(projectName);
    }

    @Override
    protected HotfixResult getLastHotfixStatusImpl(String projectName) throws Exception {
        return rpc.getLastHotfixStatus(projectName);
    }

    @Override
    protected String diagnoseTagGroupsImpl() throws Exception {
        return rpc.diagnoseTagGroups();
    }
}
