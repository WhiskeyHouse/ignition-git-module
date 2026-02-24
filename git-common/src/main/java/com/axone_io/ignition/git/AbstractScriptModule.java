package com.axone_io.ignition.git;

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
    public List<String> getGitTrackedProjectNames() {
        return getGitTrackedProjectNamesImpl();
    }

    @Override
    public List<String> listDocsForResource(String projectName,
                                            String resourcePath) throws Exception {
        return listDocsForResourceImpl(projectName, resourcePath);
    }

    protected abstract boolean pullImpl(String projectName, String userName, boolean importTags, boolean importTheme,
                                        boolean importImages) throws Exception;
    protected abstract boolean pushImpl(String projectName, String userName) throws Exception;
    protected abstract boolean commitImpl(String projectName, String userName, String[] changes, String message);
    protected abstract List<UncommittedChange> getUncommitedChangesImpl(String projectName, String userName);
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
    protected abstract List<String> getGitTrackedProjectNamesImpl();
    protected abstract List<String> listDocsForResourceImpl(String projectName, String resourcePath) throws Exception;

}
