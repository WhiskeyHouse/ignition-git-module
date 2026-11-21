package com.axone_io.ignition.git.managers;

import org.junit.Test;
import static org.junit.Assert.*;

public class GitHubApiManagerTest {

    @Test
    public void parseOwnerRepo_httpsUrl() {
        String[] result = GitHubApiManager.parseOwnerRepo("https://github.com/example-org/example-mes-ui.git");
        assertEquals("example-org", result[0]);
        assertEquals("example-mes-ui", result[1]);
    }

    @Test
    public void parseOwnerRepo_httpsUrlNoGitSuffix() {
        String[] result = GitHubApiManager.parseOwnerRepo("https://github.com/example-org/example-mes-ui");
        assertEquals("example-org", result[0]);
        assertEquals("example-mes-ui", result[1]);
    }

    @Test
    public void parseOwnerRepo_sshUrl() {
        String[] result = GitHubApiManager.parseOwnerRepo("git@github.com:example-org/example-mes-ui.git");
        assertEquals("example-org", result[0]);
        assertEquals("example-mes-ui", result[1]);
    }

    @Test
    public void parseOwnerRepo_invalidUrl_returnsNull() {
        assertNull(GitHubApiManager.parseOwnerRepo("not-a-github-url"));
    }

    @Test
    public void parseOwnerRepo_nonGithubUrl_returnsNull() {
        assertNull(GitHubApiManager.parseOwnerRepo("https://gitlab.com/org/repo.git"));
    }

    @Test
    public void sanitizeBranchName_spaces() {
        assertEquals("fix-pump-alarm", GitHubApiManager.sanitizeBranchName("fix pump alarm"));
    }

    @Test
    public void sanitizeBranchName_specialChars() {
        assertEquals("fix-alarm-50-threshold", GitHubApiManager.sanitizeBranchName("Fix alarm >50% threshold!"));
    }

    @Test
    public void sanitizeBranchName_uppercase() {
        assertEquals("fix-pump-alarm", GitHubApiManager.sanitizeBranchName("Fix Pump Alarm"));
    }

    @Test
    public void sanitizeBranchName_consecutiveHyphens() {
        assertEquals("fix-alarm", GitHubApiManager.sanitizeBranchName("fix--alarm"));
    }

    @Test
    public void sanitizeBranchName_trailingHyphen() {
        assertEquals("fix-alarm", GitHubApiManager.sanitizeBranchName("fix-alarm-"));
    }

    @Test
    public void buildPrBody_includesAllFields() {
        String body = GitHubApiManager.buildPrBody(
            "jsmith", "example-site", "WHK-MES",
            "hotfix/fix-pump-alarm", "abc1234",
            "Fix pump alarm threshold",
            new String[]{"resource/script/alarm_handler", "resource/view/pump_detail"}
        );
        assertTrue(body.contains("Already Live"));
        assertTrue(body.contains("jsmith"));
        assertTrue(body.contains("example-site"));
        assertTrue(body.contains("WHK-MES"));
        assertTrue(body.contains("hotfix/fix-pump-alarm"));
        assertTrue(body.contains("abc1234"));
        assertTrue(body.contains("Fix pump alarm threshold"));
        assertTrue(body.contains("resource/script/alarm_handler"));
        assertTrue(body.contains("resource/view/pump_detail"));
    }
}
