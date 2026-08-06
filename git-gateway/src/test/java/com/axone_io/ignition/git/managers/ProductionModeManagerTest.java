package com.axone_io.ignition.git.managers;

import com.axone_io.ignition.git.dto.ProductionModeConfig;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class ProductionModeManagerTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private Git git;
    private Repository repository;

    @Before
    public void setUp() throws Exception {
        File repoDir = tempFolder.newFolder("test-repo");
        git = Git.init().setDirectory(repoDir).setInitialBranch("main").call();
        repository = git.getRepository();

        // Disable GPG signing for test operations (avoids UnsupportedSigningFormat errors)
        StoredConfig repoConfig = repository.getConfig();
        repoConfig.setBoolean("commit", null, "gpgSign", false);
        repoConfig.setBoolean("tag", null, "gpgSign", false);
        repoConfig.unset("gpg", null, "format");
        repoConfig.save();

        // Create an initial commit so HEAD exists
        File readme = new File(repoDir, "README.md");
        Files.writeString(readme.toPath(), "test");
        git.add().addFilepattern("README.md").call();
        git.commit().setMessage("Initial commit").call();
    }

    @After
    public void tearDown() {
        if (git != null) {
            git.close();
        }
    }

    // --- validatePull tests ---

    @Test
    public void validatePull_productionModeDisabled_returnsTrue() {
        ProductionModeConfig config = new ProductionModeConfig(false, "main", null);
        assertTrue(ProductionModeManager.validatePull(git, config));
    }

    @Test
    public void validatePull_onCorrectBranch_returnsTrue() {
        ProductionModeConfig config = new ProductionModeConfig(true, "main", null);
        assertTrue(ProductionModeManager.validatePull(git, config));
    }

    @Test
    public void validatePull_onWrongBranch_returnsFalse() throws Exception {
        // Create and checkout a feature branch
        git.branchCreate().setName("feature").call();
        git.checkout().setName("feature").call();

        ProductionModeConfig config = new ProductionModeConfig(true, "main", null);
        assertFalse(ProductionModeManager.validatePull(git, config));
        assertTrue(config.getWarningMessage().contains("does not match"));
    }

    @Test
    public void validatePull_noBranchConfigured_returnsTrue() {
        ProductionModeConfig config = new ProductionModeConfig(true, null, null);
        assertTrue(ProductionModeManager.validatePull(git, config));
    }

    @Test
    public void validatePull_emptyBranchConfigured_returnsTrue() {
        ProductionModeConfig config = new ProductionModeConfig(true, "", null);
        assertTrue(ProductionModeManager.validatePull(git, config));
    }

    @Test
    public void validatePull_withMatchingTagPattern_returnsTrue() throws Exception {
        // Create a tag that matches the pattern
        git.tag().setName("v1.0.0").setAnnotated(false).call();

        ProductionModeConfig config = new ProductionModeConfig(true, "main", "v*");
        assertTrue(ProductionModeManager.validatePull(git, config));
    }

    @Test
    public void validatePull_withNonMatchingTagPattern_returnsFalse() throws Exception {
        git.tag().setName("release-1.0").setAnnotated(false).call();

        ProductionModeConfig config = new ProductionModeConfig(true, "main", "v*");
        assertFalse(ProductionModeManager.validatePull(git, config));
        assertTrue(config.getWarningMessage().contains("do not match pattern"));
    }

    // --- validatePush tests ---

    @Test
    public void validatePush_productionModeDisabled_returnsTrue() {
        ProductionModeConfig config = new ProductionModeConfig(false, "main", null);
        assertTrue(ProductionModeManager.validatePush(git, config, "main"));
    }

    @Test
    public void validatePush_toProductionBranch_returnsTrue_withWarning() {
        ProductionModeConfig config = new ProductionModeConfig(true, "main", null);
        assertTrue(ProductionModeManager.validatePush(git, config, "main"));
        assertTrue(config.hasWarnings());
        assertTrue(config.getWarningMessage().contains("pushing directly to the production branch"));
    }

    @Test
    public void validatePush_toNonProductionBranch_returnsTrue_noWarning() {
        ProductionModeConfig config = new ProductionModeConfig(true, "release", null);
        assertTrue(ProductionModeManager.validatePush(git, config, "feature-branch"));
        assertFalse(config.hasWarnings());
    }

    @Test
    public void validatePush_toMasterBranch_returnsTrue_withWarning() {
        ProductionModeConfig config = new ProductionModeConfig(true, "master", null);
        assertTrue(ProductionModeManager.validatePush(git, config, "master"));
        assertTrue(config.hasWarnings());
    }

    @Test
    public void validatePush_toMainWhenProductionBranchIsRelease_noWarning() {
        // "main" should not trigger a warning when production branch is "release"
        ProductionModeConfig config = new ProductionModeConfig(true, "release", null);
        assertTrue(ProductionModeManager.validatePush(git, config, "main"));
        assertFalse(config.hasWarnings());
    }

    // --- validatePull/Push with unsafe repo ---

    @Test
    public void validatePull_unsafeRepo_returnsFalse() throws Exception {
        // Dirty the working tree
        File readme = new File(repository.getWorkTree(), "README.md");
        Files.writeString(readme.toPath(), "dirty content");

        ProductionModeConfig config = new ProductionModeConfig(true, "main", null);
        assertFalse(ProductionModeManager.validatePull(git, config));
        assertTrue(config.getWarningMessage().contains("uncommitted change(s)"));
    }

    @Test
    public void validatePush_unsafeRepo_returnsFalse() throws Exception {
        // Dirty the working tree
        File readme = new File(repository.getWorkTree(), "README.md");
        Files.writeString(readme.toPath(), "dirty content");

        ProductionModeConfig config = new ProductionModeConfig(true, "feature", null);
        assertFalse(ProductionModeManager.validatePush(git, config, "feature"));
        assertTrue(config.getWarningMessage().contains("uncommitted change(s)"));
    }

    // --- validateTagPattern tests ---

    @Test
    public void validateTagPattern_matchingWildcard_returnsTrue() {
        List<String> tags = Arrays.asList("v1.0.0", "v2.0.0");
        assertTrue(ProductionModeManager.validateTagPattern(tags, "v*"));
    }

    @Test
    public void validateTagPattern_noMatch_returnsFalse() {
        List<String> tags = Arrays.asList("release-1.0", "release-2.0");
        assertFalse(ProductionModeManager.validateTagPattern(tags, "v*"));
    }

    @Test
    public void validateTagPattern_emptyTags_returnsFalse() {
        assertFalse(ProductionModeManager.validateTagPattern(Collections.emptyList(), "v*"));
    }

    @Test
    public void validateTagPattern_regexPattern_returnsTrue() {
        List<String> tags = Arrays.asList("v1.2.3", "v10.20.30");
        assertTrue(ProductionModeManager.validateTagPattern(tags, "^v\\d+\\.\\d+\\.\\d+$"));
    }

    @Test
    public void validateTagPattern_regexNoMatch_returnsFalse() {
        List<String> tags = Arrays.asList("release-1.0", "build-42");
        assertFalse(ProductionModeManager.validateTagPattern(tags, "^v\\d+\\.\\d+\\.\\d+$"));
    }

    @Test
    public void validateTagPattern_questionMarkWildcard_returnsTrue() {
        List<String> tags = Arrays.asList("v1.0");
        assertTrue(ProductionModeManager.validateTagPattern(tags, "v?.0"));
    }

    @Test
    public void validateTagPattern_invalidRegex_returnsFalse() {
        List<String> tags = Arrays.asList("v1.0.0");
        // An unclosed group is invalid regex
        assertFalse(ProductionModeManager.validateTagPattern(tags, "^(unclosed"));
    }

    // --- isRepositorySafe tests ---

    @Test
    public void isRepositorySafe_cleanRepo_returnsTrue() {
        assertTrue(ProductionModeManager.isRepositorySafe(repository));
    }

    @Test
    public void isRepositorySafe_uncommittedChanges_returnsFalse() throws Exception {
        // Modify a tracked file without committing
        File readme = new File(repository.getWorkTree(), "README.md");
        Files.writeString(readme.toPath(), "modified content");

        assertFalse(ProductionModeManager.isRepositorySafe(repository));
    }

    @Test
    public void isRepositorySafe_untrackedFiles_returnsTrue() throws Exception {
        // Untracked files are ignored (Ignition may create temp files in the project dir)
        File newFile = new File(repository.getWorkTree(), "untracked.txt");
        Files.writeString(newFile.toPath(), "new file");

        assertTrue(ProductionModeManager.isRepositorySafe(repository));
    }

    @Test
    public void isRepositorySafe_stagedButUncommitted_returnsFalse() throws Exception {
        // Stage a change without committing
        File newFile = new File(repository.getWorkTree(), "staged.txt");
        Files.writeString(newFile.toPath(), "staged content");
        git.add().addFilepattern("staged.txt").call();

        assertFalse(ProductionModeManager.isRepositorySafe(repository));
    }

    // --- describeUnsafeState tests ---

    @Test
    public void describeUnsafeState_cleanRepo_returnsNull() {
        assertNull(ProductionModeManager.describeUnsafeState(repository));
    }

    @Test
    public void describeUnsafeState_untrackedFiles_returnsNull() throws Exception {
        File newFile = new File(repository.getWorkTree(), "untracked.txt");
        Files.writeString(newFile.toPath(), "new file");

        assertNull(ProductionModeManager.describeUnsafeState(repository));
    }

    @Test
    public void describeUnsafeState_namesTheOffendingPath() throws Exception {
        File readme = new File(repository.getWorkTree(), "README.md");
        Files.writeString(readme.toPath(), "modified content");

        String reason = ProductionModeManager.describeUnsafeState(repository);

        assertNotNull(reason);
        // The whole point of the message: it must name the file, not just say "dirty".
        assertTrue("expected the path in: " + reason, reason.contains("README.md"));
        assertTrue("expected a count in: " + reason, reason.contains("1 uncommitted change(s)"));
    }

    @Test
    public void describeUnsafeState_namesStagedPath() throws Exception {
        File staged = new File(repository.getWorkTree(), "staged.txt");
        Files.writeString(staged.toPath(), "staged content");
        git.add().addFilepattern("staged.txt").call();

        String reason = ProductionModeManager.describeUnsafeState(repository);

        assertNotNull(reason);
        assertTrue("expected the path in: " + reason, reason.contains("staged.txt"));
    }

    @Test
    public void describeUnsafeState_namesDeletedPath() throws Exception {
        File readme = new File(repository.getWorkTree(), "README.md");
        assertTrue(readme.delete());

        String reason = ProductionModeManager.describeUnsafeState(repository);

        assertNotNull(reason);
        assertTrue("expected the path in: " + reason, reason.contains("README.md"));
    }

    @Test
    public void describeUnsafeState_manyChanges_capsListAndReportsRemainder() throws Exception {
        // Commit 15 tracked files, then dirty all of them.
        for (int i = 0; i < 15; i++) {
            Files.writeString(new File(repository.getWorkTree(), "file" + i + ".txt").toPath(), "v1");
        }
        git.add().addFilepattern(".").call();
        git.commit().setMessage("add files").call();
        for (int i = 0; i < 15; i++) {
            Files.writeString(new File(repository.getWorkTree(), "file" + i + ".txt").toPath(), "v2");
        }

        String reason = ProductionModeManager.describeUnsafeState(repository);

        assertNotNull(reason);
        assertTrue("expected full count in: " + reason, reason.contains("15 uncommitted change(s)"));
        assertTrue("expected a truncation notice in: " + reason, reason.contains("and 5 more"));
        // Only MAX_REPORTED_PATHS bullets, so the dialog stays readable.
        assertEquals(10, reason.split("• ", -1).length - 1);
    }

    @Test
    public void validatePush_unsafeRepo_warningNamesTheOffendingPath() throws Exception {
        File readme = new File(repository.getWorkTree(), "README.md");
        Files.writeString(readme.toPath(), "modified content");

        ProductionModeConfig config = new ProductionModeConfig();
        config.setProductionMode(true);
        config.setProductionBranch("main");

        assertFalse(ProductionModeManager.validatePush(git, config, "main"));
        assertTrue("expected the path in: " + config.getWarningMessage(),
                config.getWarningMessage().contains("README.md"));
    }

    @Test
    public void validatePull_unsafeRepo_warningNamesTheOffendingPath() throws Exception {
        File readme = new File(repository.getWorkTree(), "README.md");
        Files.writeString(readme.toPath(), "modified content");

        ProductionModeConfig config = new ProductionModeConfig();
        config.setProductionMode(true);
        config.setProductionBranch("main");

        assertFalse(ProductionModeManager.validatePull(git, config));
        assertTrue("expected the path in: " + config.getWarningMessage(),
                config.getWarningMessage().contains("README.md"));
    }

    // --- listTags tests ---

    @Test
    public void listTags_noTags_returnsEmpty() throws Exception {
        List<String> tags = ProductionModeManager.listTags(git);
        assertTrue(tags.isEmpty());
    }

    @Test
    public void listTags_withTags_returnsStrippedNames() throws Exception {
        git.tag().setName("v1.0.0").setAnnotated(false).call();
        git.tag().setName("v2.0.0").setAnnotated(false).call();

        List<String> tags = ProductionModeManager.listTags(git);
        assertEquals(2, tags.size());
        assertTrue(tags.contains("v1.0.0"));
        assertTrue(tags.contains("v2.0.0"));
    }

    // --- buildConfig tests ---

    @Test
    public void generateWarningMessage_includesBranchAndPattern() {
        ProductionModeConfig config = new ProductionModeConfig(true, "main", "v*");
        String message = ProductionModeManager.generateWarningMessage(config, "PULL");

        assertTrue(message.contains("PRODUCTION MODE"));
        assertTrue(message.contains("PULL"));
        assertTrue(message.contains("main"));
        assertTrue(message.contains("v*"));
    }

    @Test
    public void generateWarningMessage_noBranchOrPattern() {
        ProductionModeConfig config = new ProductionModeConfig(true, null, null);
        String message = ProductionModeManager.generateWarningMessage(config, "PUSH");

        assertTrue(message.contains("PRODUCTION MODE"));
        assertTrue(message.contains("PUSH"));
        assertFalse(message.contains("Production Branch:"));
        assertFalse(message.contains("Required Tag Pattern:"));
    }

    // --- Hotfix branch awareness tests ---

    @Test
    public void isHotfixBranch_hotfixPrefix_returnsTrue() {
        assertTrue(ProductionModeManager.isHotfixBranch("hotfix/fix-pump-alarm"));
    }

    @Test
    public void isHotfixBranch_mainBranch_returnsFalse() {
        assertFalse(ProductionModeManager.isHotfixBranch("main"));
    }

    @Test
    public void isHotfixBranch_featureBranch_returnsFalse() {
        assertFalse(ProductionModeManager.isHotfixBranch("feature/new-widget"));
    }

    @Test
    public void validatePull_onHotfixBranch_blocksPull() throws Exception {
        git.branchCreate().setName("hotfix/fix-pump-alarm").call();
        git.checkout().setName("hotfix/fix-pump-alarm").call();

        ProductionModeConfig config = new ProductionModeConfig(true, "main", null);
        assertFalse(ProductionModeManager.validatePull(git, config));
        assertTrue(config.getWarningMessage().contains("disabled on hotfix branches"));
    }

    @Test
    public void validatePush_onHotfixBranch_allowsWithoutWarning() throws Exception {
        git.branchCreate().setName("hotfix/fix-pump-alarm").call();
        git.checkout().setName("hotfix/fix-pump-alarm").call();

        ProductionModeConfig config = new ProductionModeConfig(true, "main", null);
        assertTrue(ProductionModeManager.validatePush(git, config, "hotfix/fix-pump-alarm"));
        assertFalse(config.hasWarnings());
    }
}
