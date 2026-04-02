package com.axone_io.ignition.git.managers;

import com.axone_io.ignition.git.dto.HotfixResult;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.*;

public class HotfixManagerTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private Git git;
    private Repository repository;
    private File repoDir;

    @Before
    public void setUp() throws Exception {
        repoDir = tempFolder.newFolder("test-repo");
        git = Git.init().setDirectory(repoDir).setInitialBranch("main").call();
        repository = git.getRepository();

        StoredConfig repoConfig = repository.getConfig();
        repoConfig.setBoolean("commit", null, "gpgSign", false);
        repoConfig.setBoolean("tag", null, "gpgSign", false);
        repoConfig.unset("gpg", null, "format");
        repoConfig.save();

        File readme = new File(repoDir, "README.md");
        Files.writeString(readme.toPath(), "test");
        git.add().addFilepattern("README.md").call();
        git.commit().setMessage("Initial commit").call();
    }

    @After
    public void tearDown() {
        if (git != null) git.close();
    }

    @Test
    public void createHotfixBranch_createsBranchAndSwitches() throws Exception {
        // Dirty the working tree first
        File script = new File(repoDir, "script.py");
        Files.writeString(script.toPath(), "fixed content");

        String branchName = HotfixManager.createHotfixBranch(git, "fix-pump-alarm");

        assertEquals("hotfix/fix-pump-alarm", branchName);
        assertEquals("hotfix/fix-pump-alarm", repository.getBranch());
        // Working tree changes should still be present
        assertTrue(script.exists());
        assertEquals("fixed content", Files.readString(script.toPath()));
    }

    @Test
    public void commitOnHotfixBranch_commitsSpecifiedFiles() throws Exception {
        File script = new File(repoDir, "script.py");
        Files.writeString(script.toPath(), "fixed content");

        HotfixManager.createHotfixBranch(git, "fix-pump-alarm");

        String hash = HotfixManager.commitHotfix(git, new String[]{"script.py"},
            "Fix pump alarm threshold", "test@example.com");

        assertNotNull(hash);
        assertEquals(7, hash.length()); // Short hash
        // Verify commit exists
        var log = git.log().setMaxCount(1).call().iterator().next();
        assertEquals("Fix pump alarm threshold", log.getFullMessage());
    }

    @Test
    public void mergeHotfixIntoMain_mergesAndSwitchesBack() throws Exception {
        File script = new File(repoDir, "script.py");
        Files.writeString(script.toPath(), "fixed content");
        HotfixManager.createHotfixBranch(git, "fix-pump-alarm");
        HotfixManager.commitHotfix(git, new String[]{"script.py"},
            "Fix pump alarm threshold", "test@example.com");

        HotfixManager.mergeHotfixIntoMain(git, "hotfix/fix-pump-alarm", "main");

        assertEquals("main", repository.getBranch());
        // Verify the fix exists on main
        assertEquals("fixed content", Files.readString(script.toPath()));
    }

    @Test
    public void cleanupHotfixBranch_deletesBranch() throws Exception {
        File script = new File(repoDir, "script.py");
        Files.writeString(script.toPath(), "fixed content");
        HotfixManager.createHotfixBranch(git, "fix-pump-alarm");
        HotfixManager.commitHotfix(git, new String[]{"script.py"},
            "Fix pump alarm threshold", "test@example.com");
        HotfixManager.mergeHotfixIntoMain(git, "hotfix/fix-pump-alarm", "main");

        HotfixManager.cleanupHotfixBranch(git, "hotfix/fix-pump-alarm");

        List<String> branches = git.branchList().call().stream()
            .map(ref -> ref.getName().replace("refs/heads/", ""))
            .toList();
        assertFalse(branches.contains("hotfix/fix-pump-alarm"));
        assertTrue(branches.contains("main"));
    }

    @Test
    public void rollback_switchesBackToMainAndDeletesBranch() throws Exception {
        File script = new File(repoDir, "script.py");
        Files.writeString(script.toPath(), "fixed content");
        HotfixManager.createHotfixBranch(git, "fix-pump-alarm");

        HotfixManager.rollback(git, "hotfix/fix-pump-alarm", "main");

        assertEquals("main", repository.getBranch());
        List<String> branches = git.branchList().call().stream()
            .map(ref -> ref.getName().replace("refs/heads/", ""))
            .toList();
        assertFalse(branches.contains("hotfix/fix-pump-alarm"));
    }
}
