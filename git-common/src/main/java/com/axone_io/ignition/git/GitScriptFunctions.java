package com.axone_io.ignition.git;

import com.inductiveautomation.ignition.common.BundleUtil;
import com.inductiveautomation.ignition.common.script.hints.JythonElement;
import com.inductiveautomation.ignition.common.script.hints.ScriptArg;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Curated {@code system.git.*} scripting facade.
 *
 * <p>Exposes ONLY tag import and read-only status functions. Write operations
 * (push, commit, switchBranch, hotfix...) are deliberately NOT exposed here —
 * they stay Designer-only where production-mode safeguards live.</p>
 *
 * <p>The delegate is resolved lazily on every call: in Designer/Client scope the
 * RPC proxy is not available until the module hook's startup() has run, which can
 * be after initializeScriptManager() registers this class.</p>
 *
 * <p>NOTE: unlike {@link GitScriptInterface}, overloads ARE allowed here. This class
 * is consumed by Jython (which resolves Java overloads), never by the RPC layer.</p>
 */
public class GitScriptFunctions {

    static {
        BundleUtil.get().addBundle(
            GitScriptFunctions.class.getSimpleName(),
            GitScriptFunctions.class.getClassLoader(),
            GitScriptFunctions.class.getName().replace('.', '/')
        );
    }

    private final Supplier<GitScriptInterface> delegateSupplier;

    public GitScriptFunctions(Supplier<GitScriptInterface> delegateSupplier) {
        this.delegateSupplier = Objects.requireNonNull(delegateSupplier, "delegateSupplier is required");
    }

    private GitScriptInterface delegate() {
        GitScriptInterface d = delegateSupplier.get();
        if (d == null) {
            throw new IllegalStateException(
                "system.git is not ready: no gateway connection available yet. " +
                "Ensure the Git gateway module is installed and running.");
        }
        return d;
    }

    private static String requireProject(String projectName) {
        if (projectName == null || projectName.trim().isEmpty()) {
            throw new IllegalArgumentException("projectName is required");
        }
        return projectName;
    }

    @JythonElement(docBundlePrefix = "GitScriptFunctions")
    public boolean importTags(@ScriptArg("projectName") String projectName) throws Exception {
        return importTags(projectName, null);
    }

    @JythonElement(docBundlePrefix = "GitScriptFunctions")
    public boolean importTags(@ScriptArg("projectName") String projectName,
                              @ScriptArg("collisionPolicy") String collisionPolicy) throws Exception {
        requireProject(projectName);
        return delegate().importTags(projectName, collisionPolicy);
    }

    @JythonElement(docBundlePrefix = "GitScriptFunctions")
    public List<UncommittedChange> getUncommittedChanges(@ScriptArg("projectName") String projectName,
                                                         @ScriptArg("userName") String userName) throws Exception {
        requireProject(projectName);
        // RPC interface name is (sic) getUncommitedChanges; fix the spelling at the script layer.
        return delegate().getUncommitedChanges(projectName, userName);
    }

    @JythonElement(docBundlePrefix = "GitScriptFunctions")
    public String getCurrentBranch(@ScriptArg("projectName") String projectName) throws Exception {
        requireProject(projectName);
        return delegate().getCurrentBranch(projectName);
    }

    @JythonElement(docBundlePrefix = "GitScriptFunctions")
    public BranchStatus getBranchStatus(@ScriptArg("projectName") String projectName,
                                        @ScriptArg("userName") String userName) throws Exception {
        requireProject(projectName);
        return delegate().getBranchStatus(projectName, userName);
    }

    @JythonElement(docBundlePrefix = "GitScriptFunctions")
    public List<BranchInfo> listBranches(@ScriptArg("projectName") String projectName,
                                         @ScriptArg("userName") String userName) throws Exception {
        requireProject(projectName);
        return delegate().listBranches(projectName, userName);
    }

    @JythonElement(docBundlePrefix = "GitScriptFunctions")
    public List<CommitInfo> getCommitHistory(@ScriptArg("projectName") String projectName,
                                             @ScriptArg("userName") String userName,
                                             @ScriptArg("maxCount") int maxCount) throws Exception {
        requireProject(projectName);
        return delegate().getCommitHistory(projectName, userName, maxCount);
    }

    @JythonElement(docBundlePrefix = "GitScriptFunctions")
    public List<String> listRepositoryTags(@ScriptArg("projectName") String projectName) throws Exception {
        requireProject(projectName);
        return delegate().listRepositoryTags(projectName);
    }

    @JythonElement(docBundlePrefix = "GitScriptFunctions")
    public List<String> getGitTrackedProjectNames() throws Exception {
        return delegate().getGitTrackedProjectNames();
    }
}
