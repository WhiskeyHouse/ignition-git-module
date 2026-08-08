package com.axone_io.ignition.git.commissioning.utils;

import com.axone_io.ignition.git.commissioning.ProjectConfig;
import com.axone_io.ignition.git.commissioning.ProjectConfigs;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * End-to-end parse of a git.yaml that still declares a retired key.
 *
 * <p>Lives in the parser's own package because {@code parseYaml} is protected — widening it
 * just to be testable would be the wrong trade.</p>
 *
 * <p>This is the regression that matters most about retiring {@code initDefaultBranch}:
 * unmapped keys hard-fail commissioning by design, so simply deleting the backing field
 * would have thrown {@code "Invalid git.yaml: failed to apply key 'initDefaultBranch'"} on
 * every gateway whose git.yaml still declares it — turning a dead-config cleanup into an
 * outage.</p>
 */
public class RetiredYamlKeyParseTest {

    /** Deletes the fixtures afterwards even when an assertion or the parser throws. */
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private Path writeGitYaml(String name, String... lines) throws Exception {
        Path yaml = tempFolder.newFile(name).toPath();
        Files.writeString(yaml, String.join("\n", lines));
        return yaml;
    }

    @Test
    public void gitYamlStillDeclaringTheRetiredKeyParsesAndKeepsEverythingElse() throws Exception {
        Path yaml = writeGitYaml("git-retired-key.yaml",
                "- repo_uri: https://example.invalid/repo.git",
                "  repo_branch: main",
                "  ignition_projectName: Proj",
                "  ignition_userName: admin@example.com",
                "  user_name: someone",
                "  user_email: someone@example.com",
                "  user_password: placeholder",
                "  initDefaultBranch: main",
                "  production_mode: true",
                "  production_branch: main",
                "");

        ProjectConfigs configs = GitCommissioningUtils.parseYaml(yaml);

        assertNotNull("a git.yaml carrying the retired key must still parse", configs);
        assertEquals(1, configs.getProjects().size());

        ProjectConfig parsed = configs.getProjects().get(0);
        // The retired key is dropped silently-but-warned; keys on either side of it in the
        // document still apply, which is what proves the parse loop continued rather than
        // aborting the entry.
        assertEquals("Proj", parsed.getIgnition_projectName());
        assertEquals("main", parsed.getRepo_branch());
        assertEquals(Boolean.TRUE, parsed.getProduction_mode());
        assertEquals("main", parsed.getProduction_branch());
    }

    @Test
    public void genuinelyUnknownKeysStillHardFail() throws Exception {
        // Retiring a key must not weaken the TEC-3635 guarantee that typos surface loudly.
        Path yaml = writeGitYaml("git-unknown-key.yaml",
                "- repo_uri: https://example.invalid/repo.git",
                "  repo_branch: main",
                "  ignition_projectName: Proj",
                "  ignition_userName: admin@example.com",
                "  user_name: someone",
                "  user_email: someone@example.com",
                "  user_password: placeholder",
                "  totally_made_up_key: yes",
                "");

        // parseYaml only catches IOException, so the reflection failure propagates.
        try {
            GitCommissioningUtils.parseYaml(yaml);
            fail("Expected RuntimeException for an unmapped git.yaml key");
        } catch (RuntimeException expected) {
            assertTrue("should name the offending key: " + expected.getMessage(),
                    expected.getMessage().contains("totally_made_up_key"));
        }
    }
}
