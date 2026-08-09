package com.axone_io.ignition.git.managers;

import com.inductiveautomation.ignition.common.util.LoggerEx;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.PullCommand;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.lib.Repository;

/**
 * Reconciliation policy for {@code git pull} on a gateway repository.
 *
 * <p>The module used to call {@code git.pull()} without setting a reconciliation mode, which
 * left JGit free to inherit {@code pull.rebase} / {@code branch.&lt;name&gt;.rebase} from whatever
 * happened to be configured on that particular gateway. On a repository with
 * {@code pull.rebase=true} a routine deploy rewrote every local commit, turning a project that
 * was merely behind into one reporting "72 and 7 different commits" against its remote.</p>
 *
 * <p>A gateway working tree is not a developer's checkout: nobody is watching to resolve a
 * conflict, and the commits being replayed are machine-generated auto-commits. So the policy is
 * deliberately strict — <b>fast-forward or fail</b>. Divergence is an operator decision, not
 * something a deploy should silently resolve.</p>
 */
public final class GitPullPolicy {

    private static final LoggerEx logger = LoggerEx.newBuilder().build(GitPullPolicy.class);

    private GitPullPolicy() {
    }

    /**
     * Pins the pull to fast-forward-only and disables rebase, regardless of repository config.
     *
     * @return the same command, for chaining onto {@code git.pull()}
     */
    public static PullCommand applyTo(PullCommand pull) {
        pull.setRebase(false);
        pull.setFastForward(MergeCommand.FastForwardMode.FF_ONLY);
        return pull;
    }

    /**
     * Explains why a pull did not succeed, or returns {@code null} when it did.
     *
     * <p>Callers must treat a non-null result as fatal and skip any import that would follow:
     * a refused pull leaves the working tree on the old revision, and importing it would push
     * stale — or, if the pull got far enough to conflict, half-applied — resources into the
     * running gateway.</p>
     */
    public static String describeFailure(PullResult result, Repository repository) {
        if (result == null) {
            return "the pull returned no result";
        }
        if (result.isSuccessful()) {
            return null;
        }

        String branch = describeBranch(repository);
        logger.warn("Pull did not succeed on branch '" + branch + "': " + result);

        return "cannot fast-forward '" + branch + "' from its remote. The local branch has "
                + "commits the remote does not, so the two have diverged. Reconcile them manually "
                + "(the gateway will not merge or rebase automatically), then retry.";
    }

    private static String describeBranch(Repository repository) {
        try {
            String branch = repository.getBranch();
            return branch == null ? "(unknown branch)" : branch;
        } catch (Exception e) {
            logger.debug("Could not resolve current branch name", e);
            return "(unknown branch)";
        }
    }
}
