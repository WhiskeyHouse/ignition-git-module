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

    protected abstract boolean pullImpl(String projectName, String userName, boolean importTags, boolean importTheme,
                                        boolean importImages) throws Exception;
    protected abstract boolean pushImpl(String projectName, String userName) throws Exception;
    protected abstract boolean commitImpl(String projectName, String userName, String[] changes, String message);
    protected abstract List<UncommittedChange> getUncommitedChangesImpl(String projectName, String userName);
    protected abstract boolean isRegisteredUserImpl(String projectName, String userName);
    protected abstract boolean exportConfigImpl(String projectName);
    protected abstract void setupLocalRepoImpl(String projectName, String userName) throws Exception;
    protected abstract List<CommitInfo> getCommitHistoryImpl(String projectName, String userName, int maxCount);

}
