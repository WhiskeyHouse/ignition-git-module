package com.axone_io.ignition.git.managers;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.lib.StoredConfig;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Behaviour tests for the pull reconciliation policy.
 *
 * <p>These exist because a production gateway silently rewrote 72 commits of local history: the
 * module called {@code git.pull()} without setting a reconciliation mode, so JGit inherited
 * {@code pull.rebase} from the repository config and rebased the branch. The tests below drive
 * real repositories through a real diverged pull rather than asserting on command configuration,
 * because what matters is the effect on history, not which setter was called.</p>
 */
public class GitPullPolicyTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private Git remote;
    private Git local;
    private File localDir;

    @Before
    public void setUp() throws Exception {
        File remoteDir = tempFolder.newFolder("remote.git");
        remote = Git.init().setBare(true).setDirectory(remoteDir).setInitialBranch("main").call();

        localDir = tempFolder.newFolder("local");
        local = Git.cloneRepository()
                .setURI(remoteDir.toURI().toString())
                .setDirectory(localDir)
                .call();
        disableSigning(local.getRepository());

        commit(local, "README.md", "base", "Initial commit");
        local.push().setRemote("origin").call();
    }

    @After
    public void tearDown() {
        if (local != null) local.close();
        if (remote != null) remote.close();
    }

    // --- helpers ---

    private static void disableSigning(Repository repository) throws Exception {
        StoredConfig config = repository.getConfig();
        config.setBoolean("commit", null, "gpgSign", false);
        config.setBoolean("tag", null, "gpgSign", false);
        config.unset("gpg", null, "format");
        config.save();
    }

    private static void commit(Git git, String name, String content, String message) throws Exception {
        File file = new File(git.getRepository().getWorkTree(), name);
        Files.writeString(file.toPath(), content);
        git.add().addFilepattern(name).call();
        git.commit().setMessage(message).call();
    }

    /** Pushes an unrelated commit to the remote via a throwaway clone, leaving the remote ahead. */
    private void advanceRemote() throws Exception {
        File otherDir = tempFolder.newFolder("other");
        try (Git other = Git.cloneRepository()
                .setURI(remote.getRepository().getDirectory().toURI().toString())
                .setDirectory(otherDir)
                .call()) {
            disableSigning(other.getRepository());
            commit(other, "remote-change.txt", "from remote", "Remote-side commit");
            other.push().setRemote("origin").call();
        }
    }

    private ObjectId head() throws Exception {
        return local.getRepository().resolve("HEAD");
    }

    private PullResult pullWithPolicy() throws Exception {
        return GitPullPolicy.applyTo(local.pull().setRemote("origin")).call();
    }

    // --- fast-forward: the happy path still works ---

    @Test
    public void pull_whenRemoteIsAhead_fastForwardsLocalBranch() throws Exception {
        advanceRemote();
        ObjectId before = head();

        PullResult result = pullWithPolicy();

        assertNull("a fast-forwardable pull should not be reported as a failure",
                GitPullPolicy.describeFailure(result, local.getRepository()));
        assertTrue("pull should have succeeded", result.isSuccessful());
        assertTrue("HEAD should have advanced", !before.equals(head()));
        assertTrue("remote file should now be present",
                new File(localDir, "remote-change.txt").exists());
    }

    // --- divergence: the regression that rewrote 72 commits ---

    @Test
    public void pull_whenDivergedAndRebaseConfigured_doesNotRewriteLocalHistory() throws Exception {
        // The gateway repo that broke had pull.rebase=true; the module must not honour it.
        StoredConfig config = local.getRepository().getConfig();
        config.setBoolean("pull", null, "rebase", true);
        config.save();

        advanceRemote();
        commit(local, "local-change.txt", "from local", "Local-side commit");
        ObjectId localHeadBeforePull = head();

        try {
            pullWithPolicy();
        } catch (Exception expected) {
            // A refused pull may surface as an exception; either way history must be intact.
        }

        assertEquals("local history must not be rewritten by a diverged pull",
                localHeadBeforePull, head());
    }

    @Test
    public void pull_whenDiverged_leavesRepositoryInSafeState() throws Exception {
        advanceRemote();
        commit(local, "local-change.txt", "from local", "Local-side commit");

        try {
            pullWithPolicy();
        } catch (Exception expected) {
            // fall through to the state assertion
        }

        assertEquals("a refused pull must not strand the repo mid-rebase or mid-merge",
                RepositoryState.SAFE, local.getRepository().getRepositoryState());
    }

    @Test
    public void pull_whenDiverged_isReportedAsFailureNamingTheBranch() throws Exception {
        advanceRemote();
        commit(local, "local-change.txt", "from local", "Local-side commit");

        String failure;
        try {
            PullResult result = pullWithPolicy();
            failure = GitPullPolicy.describeFailure(result, local.getRepository());
        } catch (Exception e) {
            failure = e.getMessage();
        }

        assertNotNull("a diverged pull must be reported as a failure, not silently ignored", failure);
        assertTrue("the failure should name the branch so an operator can act on it: " + failure,
                failure.contains("main"));
    }

    @Test
    public void pull_whenDiverged_doesNotCreateAMergeCommit() throws Exception {
        advanceRemote();
        commit(local, "local-change.txt", "from local", "Local-side commit");
        ObjectId before = head();

        try {
            pullWithPolicy();
        } catch (Exception expected) {
            // fall through
        }

        assertEquals("fast-forward-only means no merge commit may be created", before, head());
    }
}
