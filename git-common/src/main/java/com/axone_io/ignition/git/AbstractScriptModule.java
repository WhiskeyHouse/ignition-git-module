package com.axone_io.ignition.git;

import com.axone_io.ignition.git.dto.HotfixResult;
import com.axone_io.ignition.git.dto.ProductionModeConfig;
import com.axone_io.ignition.git.dto.RepoDirtyState;
import com.inductiveautomation.ignition.common.BundleUtil;

import java.util.List;

public abstract class AbstractScriptModule implements GitScriptInterface {

    static {
        BundleUtil.get().addBundle(
            AbstractScriptModule.class.getSimpleName(),
            AbstractScriptModule.class.getClassLoader(),
            AbstractScriptModule.class.getName().replace('.', '/')
        );
    }

    @Override
    public boolean pull(String projectName,
                        String userName,
                        boolean importTags,
                        boolean importTheme,
                        boolean importImages) throws Exception {
        return pullImpl(projectName, userName, importTags, importTheme, importImages);
    }

    @Override
    public boolean push(String projectName,
                        String userName) throws Exception {
        return pushImpl(projectName,userName);
    }

    @Override
    public boolean commit(String projectName,
                          String userName,
                          String[] changes,
                          String message) {
        return commitImpl(projectName, userName, changes, message);
    }

    @Override
    public List<UncommittedChange> getUncommitedChanges(String projectName,
                                                        String userName) {
        return getUncommitedChangesImpl(projectName, userName);
    }

    @Override
    public RepoDirtyState getRepoDirtyState(String projectName,
                                            String userName) {
        return getRepoDirtyStateImpl(projectName, userName);
    }

    @Override
    public boolean isRegisteredUser(String projectName,
                                    String userName) {
        return isRegisteredUserImpl(projectName, userName);
    }

    @Override
    public boolean exportConfig(String projectName) {
        return exportConfigImpl(projectName);
    }

    @Override
    public void setupLocalRepo(String projectName,
                               String userName) throws Exception {
        setupLocalRepoImpl(projectName, userName);
    }

    @Override
    public List<CommitInfo> getCommitHistory(String projectName,
                                            String userName,
                                            int maxCount) {
        return getCommitHistoryImpl(projectName, userName, maxCount);
    }

    @Override
    public List<BranchInfo> listBranches(String projectName,
                                        String userName) throws Exception {
        return listBranchesImpl(projectName, userName);
    }

    @Override
    public boolean fetchFromRemote(String projectName,
                                   String userName) throws Exception {
        return fetchFromRemoteImpl(projectName, userName);
    }

    @Override
    public String getCurrentBranch(String projectName) throws Exception {
        return getCurrentBranchImpl(projectName);
    }

    @Override
    public BranchStatus getBranchStatus(String projectName,
                                       String userName) throws Exception {
        return getBranchStatusImpl(projectName, userName);
    }

    @Override
    public boolean switchBranch(String projectName,
                                String userName,
                                String branchName,
                                boolean createNew,
                                boolean forceCheckout) throws Exception {
        return switchBranchImpl(projectName, userName, branchName, createNew, forceCheckout);
    }

    @Override
    public boolean stashChanges(String projectName,
                                String userName,
                                String message) throws Exception {
        return stashChangesImpl(projectName, userName, message);
    }

    @Override
    public boolean stashPop(String projectName,
                            String userName) throws Exception {
        return stashPopImpl(projectName, userName);
    }

    @Override
    public boolean discardAllChanges(String projectName,
                                     String userName) throws Exception {
        return discardAllChangesImpl(projectName, userName);
    }

    @Override
    public List<String> getConflictingFiles(String projectName) throws Exception {
        return getConflictingFilesImpl(projectName);
    }

    @Override
    public boolean resolveConflicts(String projectName,
                                    String strategy) throws Exception {
        return resolveConflictsImpl(projectName, strategy);
    }

    @Override
    public boolean abortMerge(String projectName) throws Exception {
        return abortMergeImpl(projectName);
    }

    @Override
    public boolean hasConflicts(String projectName) throws Exception {
        return hasConflictsImpl(projectName);
    }

    @Override
    public boolean importResources(String projectName,
                                   boolean importTags,
                                   boolean importTheme,
                                   boolean importImages,
                                   String collisionPolicy) throws Exception {
        return importResourcesImpl(projectName, importTags, importTheme, importImages, collisionPolicy);
    }

    @Override
    public boolean importTags(String projectName,
                              String collisionPolicy) throws Exception {
        return importTagsImpl(projectName, collisionPolicy);
    }

    @Override
    public List<String> getGitTrackedProjectNames() {
        return getGitTrackedProjectNamesImpl();
    }

    @Override
    public List<String> listDocsForResource(String projectName,
                                            String resourcePath) throws Exception {
        return listDocsForResourceImpl(projectName, resourcePath);
    }

    @Override
    public ProductionModeConfig getProductionModeConfig(String projectName) throws Exception {
        return getProductionModeConfigImpl(projectName);
    }

    @Override
    public String getUnpushedProductionCommits(String projectName) throws Exception {
        return getUnpushedProductionCommitsImpl(projectName);
    }

    @Override
    public boolean validateProductionModePull(String projectName,
                                               String userName) throws Exception {
        return validateProductionModePullImpl(projectName, userName);
    }

    @Override
    public boolean validateProductionModePush(String projectName,
                                               String userName,
                                               String targetBranch) throws Exception {
        return validateProductionModePushImpl(projectName, userName, targetBranch);
    }

    @Override
    public List<String> listRepositoryTags(String projectName) throws Exception {
        return listRepositoryTagsImpl(projectName);
    }

    @Override
    public boolean backupCurrentTags(String projectName) throws Exception {
        return backupCurrentTagsImpl(projectName);
    }

    @Override
    public boolean restoreTagsFromBackup(String projectName) throws Exception {
        return restoreTagsFromBackupImpl(projectName);
    }

    @Override
    public HotfixResult executeHotfix(String projectName, String userName,
                                      String hotfixDescription, String commitMessage,
                                      String[] changes) throws Exception {
        return executeHotfixImpl(projectName, userName, hotfixDescription, commitMessage, changes);
    }

    @Override
    public HotfixResult getHotfixProgress(String projectName) throws Exception {
        return getHotfixProgressImpl(projectName);
    }

    @Override
    public HotfixResult getLastHotfixStatus(String projectName) throws Exception {
        return getLastHotfixStatusImpl(projectName);
    }

    @Override
    public String diagnoseTagGroups() throws Exception {
        return diagnoseTagGroupsImpl();
    }

    protected abstract boolean pullImpl(String projectName, String userName, boolean importTags, boolean importTheme,
                                        boolean importImages) throws Exception;
    protected abstract boolean pushImpl(String projectName, String userName) throws Exception;
    protected abstract boolean commitImpl(String projectName, String userName, String[] changes, String message);
    protected abstract List<UncommittedChange> getUncommitedChangesImpl(String projectName, String userName);
    protected abstract RepoDirtyState getRepoDirtyStateImpl(String projectName, String userName);
    protected abstract boolean isRegisteredUserImpl(String projectName, String userName);
    protected abstract boolean exportConfigImpl(String projectName);
    protected abstract void setupLocalRepoImpl(String projectName, String userName) throws Exception;
    protected abstract List<CommitInfo> getCommitHistoryImpl(String projectName, String userName, int maxCount);
    protected abstract List<BranchInfo> listBranchesImpl(String projectName, String userName) throws Exception;
    protected abstract boolean fetchFromRemoteImpl(String projectName, String userName) throws Exception;
    protected abstract String getCurrentBranchImpl(String projectName) throws Exception;
    protected abstract BranchStatus getBranchStatusImpl(String projectName, String userName) throws Exception;
    protected abstract boolean switchBranchImpl(String projectName, String userName, String branchName, boolean createNew, boolean forceCheckout) throws Exception;
    protected abstract boolean stashChangesImpl(String projectName, String userName, String message) throws Exception;
    protected abstract boolean stashPopImpl(String projectName, String userName) throws Exception;
    protected abstract boolean discardAllChangesImpl(String projectName, String userName) throws Exception;
    protected abstract List<String> getConflictingFilesImpl(String projectName) throws Exception;
    protected abstract boolean resolveConflictsImpl(String projectName, String strategy) throws Exception;
    protected abstract boolean abortMergeImpl(String projectName) throws Exception;
    protected abstract boolean hasConflictsImpl(String projectName) throws Exception;
    protected abstract boolean importResourcesImpl(String projectName, boolean importTags, boolean importTheme, boolean importImages, String collisionPolicy) throws Exception;
    protected abstract boolean importTagsImpl(String projectName, String collisionPolicy) throws Exception;
    protected abstract List<String> getGitTrackedProjectNamesImpl();
    protected abstract List<String> listDocsForResourceImpl(String projectName, String resourcePath) throws Exception;
    protected abstract ProductionModeConfig getProductionModeConfigImpl(String projectName) throws Exception;
    protected abstract String getUnpushedProductionCommitsImpl(String projectName) throws Exception;
    protected abstract boolean validateProductionModePullImpl(String projectName, String userName) throws Exception;
    protected abstract boolean validateProductionModePushImpl(String projectName, String userName, String targetBranch) throws Exception;
    protected abstract List<String> listRepositoryTagsImpl(String projectName) throws Exception;
    protected abstract boolean backupCurrentTagsImpl(String projectName) throws Exception;
    protected abstract boolean restoreTagsFromBackupImpl(String projectName) throws Exception;
    protected abstract HotfixResult executeHotfixImpl(String projectName, String userName,
                                                      String hotfixDescription, String commitMessage,
                                                      String[] changes) throws Exception;
    protected abstract HotfixResult getHotfixProgressImpl(String projectName) throws Exception;
    protected abstract HotfixResult getLastHotfixStatusImpl(String projectName) throws Exception;
    protected abstract String diagnoseTagGroupsImpl() throws Exception;

}
