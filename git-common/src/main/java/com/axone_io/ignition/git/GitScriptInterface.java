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


}
