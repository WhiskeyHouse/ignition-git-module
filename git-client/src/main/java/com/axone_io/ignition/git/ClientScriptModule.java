package com.axone_io.ignition.git;

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
}
