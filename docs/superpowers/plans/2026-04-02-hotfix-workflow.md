# Hotfix Workflow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement an automated hotfix pipeline that detects when an engineer commits on a production branch, creates a hotfix branch, pushes, creates a GitHub PR, and merges back locally — all with progress tracking and admin visibility.

**Architecture:** Gateway-side `HotfixManager` orchestrates the pipeline (branch/commit/push/PR/merge/cleanup). Designer-side detects hotfix scenarios during commit flow and polls for progress. `GitHubApiManager` handles PR creation via REST API. `HotfixResult` DTO travels over RPC to communicate pipeline state.

**Tech Stack:** Java 17, JGit, Java HttpClient (GitHub API), Swing (Designer UI), Ignition SDK 8.3+

---

## File Structure

### New Files

| File | Module | Responsibility |
|------|--------|----------------|
| `git-common/src/main/java/com/axone_io/ignition/git/dto/HotfixResult.java` | git-common | DTO for pipeline result — step statuses, PR URL, errors. Travels over RPC. |
| `git-gateway/src/main/java/com/axone_io/ignition/git/managers/HotfixManager.java` | git-gateway | Orchestrates the 8-step hotfix pipeline. Pure git logic + delegation to GitHubApiManager. |
| `git-gateway/src/main/java/com/axone_io/ignition/git/managers/GitHubApiManager.java` | git-gateway | GitHub REST API client. Parses repo URL, creates PR with labels. |
| `git-gateway/src/test/java/com/axone_io/ignition/git/managers/HotfixManagerTest.java` | git-gateway | Unit tests for HotfixManager — branch creation, commit, merge, rollback. |
| `git-gateway/src/test/java/com/axone_io/ignition/git/managers/GitHubApiManagerTest.java` | git-gateway | Unit tests for URL parsing, PR body generation. |
| `git-designer/src/main/java/com/axone_io/ignition/git/HotfixCommitDialog.java` | git-designer | Modal dialog collecting hotfix description, commit message, changes. |
| `git-designer/src/main/java/com/axone_io/ignition/git/HotfixProgressDialog.java` | git-designer | Modal progress dialog polling gateway for pipeline step status. |

### Modified Files

| File | What Changes |
|------|-------------|
| `git-common/.../GitScriptInterface.java` | Add 3 RPC methods: `executeHotfix`, `getHotfixProgress`, `getLastHotfixStatus` |
| `git-common/.../AbstractScriptModule.java` | Add abstract `Impl` methods + override wiring for the 3 new RPC methods |
| `git-client/.../ClientScriptModule.java` | Add RPC delegation for the 3 new methods |
| `git-gateway/.../GatewayScriptModule.java` | Implement the 3 new `Impl` methods, delegate to HotfixManager |
| `git-gateway/.../records/GitProjectsConfigRecord.java` | Add 5 hotfix status fields |
| `git-gateway/.../managers/ProductionModeManager.java` | Add `isHotfixBranch()`, block pull on hotfix branches |
| `git-designer/.../DesignerHook.java` | Cache production config, add commit prompt in `notifyProjectSaveDone` |
| `git-designer/.../managers/GitActionManager.java` | Detect hotfix scenario in commit flow, block pull on hotfix branches |

---

### Task 1: HotfixResult DTO

**Files:**
- Create: `git-common/src/main/java/com/axone_io/ignition/git/dto/HotfixResult.java`

- [ ] **Step 1: Create HotfixResult DTO**

```java
package com.axone_io.ignition.git.dto;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * DTO for hotfix pipeline result. Travels over RPC between gateway and designer.
 * Each step has a status that the Designer polls to update the progress dialog.
 */
public class HotfixResult implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum StepStatus { PENDING, IN_PROGRESS, COMPLETED, FAILED, SKIPPED }

    public enum Step {
        CREATE_BRANCH("Creating hotfix branch"),
        SWITCH_BRANCH("Switching to hotfix branch"),
        COMMIT("Committing changes"),
        PUSH("Pushing to remote"),
        CREATE_PR("Creating pull request"),
        SWITCH_BACK("Switching back to main"),
        MERGE_LOCAL("Merging hotfix into local main"),
        CLEANUP("Cleaning up hotfix branch");

        private final String displayName;
        Step(String displayName) { this.displayName = displayName; }
        public String getDisplayName() { return displayName; }
    }

    private StepStatus[] stepStatuses;
    private String[] stepMessages;
    private boolean pipelineComplete;
    private boolean pipelineSuccess;
    private String hotfixBranch;
    private String commitHash;
    private String prUrl;
    private int prNumber;
    private String errorMessage;

    public HotfixResult() {
        int stepCount = Step.values().length;
        this.stepStatuses = new StepStatus[stepCount];
        this.stepMessages = new String[stepCount];
        for (int i = 0; i < stepCount; i++) {
            this.stepStatuses[i] = StepStatus.PENDING;
            this.stepMessages[i] = "";
        }
        this.pipelineComplete = false;
        this.pipelineSuccess = false;
    }

    public void updateStep(Step step, StepStatus status, String message) {
        int idx = step.ordinal();
        this.stepStatuses[idx] = status;
        this.stepMessages[idx] = message != null ? message : "";
    }

    public StepStatus getStepStatus(Step step) {
        return stepStatuses[step.ordinal()];
    }

    public String getStepMessage(Step step) {
        return stepMessages[step.ordinal()];
    }

    public boolean hasFailures() {
        for (StepStatus status : stepStatuses) {
            if (status == StepStatus.FAILED) return true;
        }
        return false;
    }

    public boolean isPipelineComplete() { return pipelineComplete; }
    public void setPipelineComplete(boolean pipelineComplete) { this.pipelineComplete = pipelineComplete; }
    public boolean isPipelineSuccess() { return pipelineSuccess; }
    public void setPipelineSuccess(boolean pipelineSuccess) { this.pipelineSuccess = pipelineSuccess; }
    public String getHotfixBranch() { return hotfixBranch; }
    public void setHotfixBranch(String hotfixBranch) { this.hotfixBranch = hotfixBranch; }
    public String getCommitHash() { return commitHash; }
    public void setCommitHash(String commitHash) { this.commitHash = commitHash; }
    public String getPrUrl() { return prUrl; }
    public void setPrUrl(String prUrl) { this.prUrl = prUrl; }
    public int getPrNumber() { return prNumber; }
    public void setPrNumber(int prNumber) { this.prNumber = prNumber; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
```

- [ ] **Step 2: Verify compilation**

Run: `mvn compile -pl git-common -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add git-common/src/main/java/com/axone_io/ignition/git/dto/HotfixResult.java
git commit -m "feat(hotfix): add HotfixResult DTO for pipeline state"
```

---

### Task 2: RPC Interface Layer

**Files:**
- Modify: `git-common/src/main/java/com/axone_io/ignition/git/GitScriptInterface.java:53`
- Modify: `git-common/src/main/java/com/axone_io/ignition/git/AbstractScriptModule.java:225`
- Modify: `git-client/src/main/java/com/axone_io/ignition/git/ClientScriptModule.java:163`

- [ ] **Step 1: Add RPC methods to GitScriptInterface**

Add after line 53 (after `restoreTagsFromBackup`), before the closing `}`:

```java
    // Hotfix operations
    HotfixResult executeHotfix(String projectName, String userName,
                               String hotfixDescription, String commitMessage,
                               String[] changes) throws Exception;
    HotfixResult getHotfixProgress(String projectName) throws Exception;
    HotfixResult getLastHotfixStatus(String projectName) throws Exception;
```

Also add import at top of file:

```java
import com.axone_io.ignition.git.dto.HotfixResult;
```

- [ ] **Step 2: Add abstract methods and wiring to AbstractScriptModule**

Add after line 194 (after `restoreTagsFromBackup` override), before the abstract method declarations:

```java
    @Override
    public HotfixResult executeHotfix(String projectName, String userName,
                                      String hotfixDescription, String commitMessage,
                                      String[] changes) throws Exception {
        return executeHotfixImpl(projectName, userName, hotfixDescription, commitMessage, changes);
    }

    @Override
    public HotfixResult getHotfixProgress(String projectName) throws Exception {
        return getHotfixProgressImpl(projectName);
    }

    @Override
    public HotfixResult getLastHotfixStatus(String projectName) throws Exception {
        return getLastHotfixStatusImpl(projectName);
    }
```

Add after line 225 (after the last abstract method declaration):

```java
    protected abstract HotfixResult executeHotfixImpl(String projectName, String userName,
                                                      String hotfixDescription, String commitMessage,
                                                      String[] changes) throws Exception;
    protected abstract HotfixResult getHotfixProgressImpl(String projectName) throws Exception;
    protected abstract HotfixResult getLastHotfixStatusImpl(String projectName) throws Exception;
```

Also add import at top:

```java
import com.axone_io.ignition.git.dto.HotfixResult;
```

- [ ] **Step 3: Add RPC delegation to ClientScriptModule**

Add after line 163 (after `restoreTagsFromBackupImpl`), before closing `}`:

```java
    @Override
    protected HotfixResult executeHotfixImpl(String projectName, String userName,
                                             String hotfixDescription, String commitMessage,
                                             String[] changes) throws Exception {
        return rpc.executeHotfix(projectName, userName, hotfixDescription, commitMessage, changes);
    }

    @Override
    protected HotfixResult getHotfixProgressImpl(String projectName) throws Exception {
        return rpc.getHotfixProgress(projectName);
    }

    @Override
    protected HotfixResult getLastHotfixStatusImpl(String projectName) throws Exception {
        return rpc.getLastHotfixStatus(projectName);
    }
```

Also add import at top:

```java
import com.axone_io.ignition.git.dto.HotfixResult;
```

- [ ] **Step 4: Verify compilation across modules**

Run: `mvn compile -pl git-common,git-client -am -q`
Expected: BUILD SUCCESS (git-gateway will fail — GatewayScriptModule doesn't implement the abstract methods yet, that's Task 5)

- [ ] **Step 5: Commit**

```bash
git add git-common/src/main/java/com/axone_io/ignition/git/GitScriptInterface.java
git add git-common/src/main/java/com/axone_io/ignition/git/AbstractScriptModule.java
git add git-client/src/main/java/com/axone_io/ignition/git/ClientScriptModule.java
git commit -m "feat(hotfix): add hotfix RPC interface methods"
```

---

### Task 3: GitProjectsConfigRecord Hotfix Status Fields

**Files:**
- Modify: `git-gateway/src/main/java/com/axone_io/ignition/git/records/GitProjectsConfigRecord.java:27`

- [ ] **Step 1: Add hotfix status fields**

Add after line 27 (after `ProductionTagPattern` field), before the comment on line 29:

```java
    public static final StringField LastHotfixStatus = new StringField(META, "LastHotfixStatus");
    public static final LongField LastHotfixTimestamp = new LongField(META, "LastHotfixTimestamp");
    public static final StringField LastHotfixUser = new StringField(META, "LastHotfixUser");
    public static final StringField LastHotfixBranch = new StringField(META, "LastHotfixBranch");
    public static final StringField LastHotfixPRUrl = new StringField(META, "LastHotfixPRUrl");
```

Add getters and setters after line 79 (after `setProductionTagPattern`), before the disabled comment block:

```java
    public String getLastHotfixStatus() {
        return this.getString(LastHotfixStatus);
    }

    public void setLastHotfixStatus(String status) {
        setString(LastHotfixStatus, status);
    }

    public long getLastHotfixTimestamp() {
        return this.getLong(LastHotfixTimestamp);
    }

    public void setLastHotfixTimestamp(long timestamp) {
        setLong(LastHotfixTimestamp, timestamp);
    }

    public String getLastHotfixUser() {
        return this.getString(LastHotfixUser);
    }

    public void setLastHotfixUser(String user) {
        setString(LastHotfixUser, user);
    }

    public String getLastHotfixBranch() {
        return this.getString(LastHotfixBranch);
    }

    public void setLastHotfixBranch(String branch) {
        setString(LastHotfixBranch, branch);
    }

    public String getLastHotfixPRUrl() {
        return this.getString(LastHotfixPRUrl);
    }

    public void setLastHotfixPRUrl(String prUrl) {
        setString(LastHotfixPRUrl, prUrl);
    }
```

- [ ] **Step 2: Commit**

```bash
git add git-gateway/src/main/java/com/axone_io/ignition/git/records/GitProjectsConfigRecord.java
git commit -m "feat(hotfix): add hotfix status fields to GitProjectsConfigRecord"
```

---

### Task 4: GitHubApiManager

**Files:**
- Create: `git-gateway/src/main/java/com/axone_io/ignition/git/managers/GitHubApiManager.java`
- Create: `git-gateway/src/test/java/com/axone_io/ignition/git/managers/GitHubApiManagerTest.java`

- [ ] **Step 1: Write tests for URL parsing and PR body generation**

```java
package com.axone_io.ignition.git.managers;

import org.junit.Test;
import static org.junit.Assert.*;

public class GitHubApiManagerTest {

    @Test
    public void parseOwnerRepo_httpsUrl() {
        String[] result = GitHubApiManager.parseOwnerRepo("https://github.com/WHK01/whk-distillery01-mes-ui.git");
        assertEquals("WHK01", result[0]);
        assertEquals("whk-distillery01-mes-ui", result[1]);
    }

    @Test
    public void parseOwnerRepo_httpsUrlNoGitSuffix() {
        String[] result = GitHubApiManager.parseOwnerRepo("https://github.com/WHK01/whk-distillery01-mes-ui");
        assertEquals("WHK01", result[0]);
        assertEquals("whk-distillery01-mes-ui", result[1]);
    }

    @Test
    public void parseOwnerRepo_sshUrl() {
        String[] result = GitHubApiManager.parseOwnerRepo("git@github.com:WHK01/whk-distillery01-mes-ui.git");
        assertEquals("WHK01", result[0]);
        assertEquals("whk-distillery01-mes-ui", result[1]);
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
            "jsmith", "whk-distillery01", "WHK-MES",
            "hotfix/fix-pump-alarm", "abc1234",
            "Fix pump alarm threshold",
            new String[]{"resource/script/alarm_handler", "resource/view/pump_detail"}
        );
        assertTrue(body.contains("Already Live"));
        assertTrue(body.contains("jsmith"));
        assertTrue(body.contains("whk-distillery01"));
        assertTrue(body.contains("WHK-MES"));
        assertTrue(body.contains("hotfix/fix-pump-alarm"));
        assertTrue(body.contains("abc1234"));
        assertTrue(body.contains("Fix pump alarm threshold"));
        assertTrue(body.contains("resource/script/alarm_handler"));
        assertTrue(body.contains("resource/view/pump_detail"));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl git-gateway -Dtest=GitHubApiManagerTest -Dsurefire.failIfNoSpecifiedTests=false -am -q`
Expected: Compilation failure — `GitHubApiManager` doesn't exist yet

- [ ] **Step 3: Implement GitHubApiManager**

```java
package com.axone_io.ignition.git.managers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub REST API client for creating hotfix PRs.
 * Uses Java's built-in HttpClient — no external dependencies.
 */
public class GitHubApiManager {
    private static final Logger logger = LoggerFactory.getLogger(GitHubApiManager.class);
    private static final String GITHUB_API_BASE = "https://api.github.com";

    private static final Pattern HTTPS_PATTERN = Pattern.compile(
        "https?://github\\.com/([^/]+)/([^/.]+?)(?:\\.git)?/?$"
    );
    private static final Pattern SSH_PATTERN = Pattern.compile(
        "git@github\\.com:([^/]+)/([^/.]+?)(?:\\.git)?$"
    );

    /**
     * Parse owner and repo name from a GitHub URL.
     * Supports HTTPS and SSH formats.
     * Returns [owner, repo] or null if not a valid GitHub URL.
     */
    public static String[] parseOwnerRepo(String repoUri) {
        if (repoUri == null) return null;

        Matcher httpsMatcher = HTTPS_PATTERN.matcher(repoUri);
        if (httpsMatcher.matches()) {
            return new String[]{httpsMatcher.group(1), httpsMatcher.group(2)};
        }

        Matcher sshMatcher = SSH_PATTERN.matcher(repoUri);
        if (sshMatcher.matches()) {
            return new String[]{sshMatcher.group(1), sshMatcher.group(2)};
        }

        return null;
    }

    /**
     * Sanitize a hotfix description into a valid git branch name segment.
     * Lowercase, spaces to hyphens, strip special chars, collapse consecutive hyphens.
     */
    public static String sanitizeBranchName(String description) {
        return description.toLowerCase()
            .replaceAll("[^a-z0-9\\s-]", "")
            .trim()
            .replaceAll("\\s+", "-")
            .replaceAll("-{2,}", "-")
            .replaceAll("-$", "");
    }

    /**
     * Build the PR body markdown for a hotfix PR.
     */
    public static String buildPrBody(String userName, String gatewayName, String projectName,
                                     String hotfixBranch, String commitHash, String commitMessage,
                                     String[] changes) {
        String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now());

        StringBuilder body = new StringBuilder();
        body.append("## Production Hotfix \u2014 Already Live\n\n");
        body.append("This fix was applied directly to the production gateway and is **already running**.\n\n");
        body.append("| | |\n|---|---|\n");
        body.append("| **Applied by** | ").append(userName).append(" |\n");
        body.append("| **Applied at** | ").append(timestamp).append(" |\n");
        body.append("| **Gateway** | ").append(gatewayName).append(" |\n");
        body.append("| **Project** | ").append(projectName).append(" |\n");
        body.append("| **Branch** | ").append(hotfixBranch).append(" |\n");
        body.append("| **Commit** | ").append(commitHash).append(" |\n\n");

        if (changes != null && changes.length > 0) {
            body.append("### Changes\n");
            for (String change : changes) {
                body.append("- ").append(change).append("\n");
            }
            body.append("\n");
        }

        body.append("### Commit Message\n");
        body.append(commitMessage).append("\n\n");
        body.append("---\n");
        body.append("Auto-generated by Ignition Git Module (Production Hotfix Workflow)\n");

        return body.toString();
    }

    /**
     * Create a pull request on GitHub.
     * Returns the PR URL on success, null on failure.
     */
    public static CreatePrResult createPullRequest(String repoUri, String token,
                                                   String headBranch, String baseBranch,
                                                   String title, String body) {
        String[] ownerRepo = parseOwnerRepo(repoUri);
        if (ownerRepo == null) {
            logger.error("[Production Hotfix] Cannot parse GitHub owner/repo from URI: {}", repoUri);
            return new CreatePrResult(null, 0, "Cannot parse GitHub owner/repo from URI: " + repoUri);
        }

        String owner = ownerRepo[0];
        String repo = ownerRepo[1];

        try {
            // Escape JSON strings
            String jsonBody = String.format(
                "{\"title\":\"%s\",\"body\":\"%s\",\"head\":\"%s\",\"base\":\"%s\"}",
                escapeJson(title),
                escapeJson(body),
                escapeJson(headBranch),
                escapeJson(baseBranch)
            );

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GITHUB_API_BASE + "/repos/" + owner + "/" + repo + "/pulls"))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 201) {
                // Parse PR URL and number from response
                String responseBody = response.body();
                String prUrl = extractJsonString(responseBody, "html_url");
                int prNumber = extractJsonInt(responseBody, "number");

                logger.info("[Production Hotfix] PR #{} created: {}", prNumber, prUrl);

                // Try to add labels (best-effort — if this fails, the PR still exists)
                addLabels(client, token, owner, repo, prNumber);

                return new CreatePrResult(prUrl, prNumber, null);
            } else {
                String errorMsg = "GitHub API returned " + response.statusCode() + ": " + response.body();
                logger.error("[Production Hotfix] PR creation failed: {}", errorMsg);
                return new CreatePrResult(null, 0, errorMsg);
            }
        } catch (Exception e) {
            logger.error("[Production Hotfix] Error creating PR", e);
            return new CreatePrResult(null, 0, e.getMessage());
        }
    }

    private static void addLabels(HttpClient client, String token, String owner, String repo, int prNumber) {
        try {
            String labelJson = "{\"labels\":[\"hotfix\",\"production\"]}";
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GITHUB_API_BASE + "/repos/" + owner + "/" + repo + "/issues/" + prNumber + "/labels"))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(labelJson))
                .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                logger.warn("[Production Hotfix] Could not add labels to PR #{}: {} {}",
                    prNumber, response.statusCode(), response.body());
            }
        } catch (Exception e) {
            logger.warn("[Production Hotfix] Error adding labels to PR #{}: {}", prNumber, e.getMessage());
        }
    }

    /** Simple JSON string extraction — avoids adding a JSON library dependency. */
    static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return null;
        start += search.length();
        int end = json.indexOf("\"", start);
        if (end < 0) return null;
        return json.substring(start, end);
    }

    /** Simple JSON int extraction. */
    static int extractJsonInt(String json, String key) {
        String search = "\"" + key + "\":";
        int start = json.indexOf(search);
        if (start < 0) return 0;
        start += search.length();
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (Character.isDigit(c)) sb.append(c);
            else break;
        }
        return sb.length() > 0 ? Integer.parseInt(sb.toString()) : 0;
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    /** Result of a PR creation attempt. */
    public static class CreatePrResult {
        private final String prUrl;
        private final int prNumber;
        private final String error;

        public CreatePrResult(String prUrl, int prNumber, String error) {
            this.prUrl = prUrl;
            this.prNumber = prNumber;
            this.error = error;
        }

        public boolean isSuccess() { return prUrl != null; }
        public String getPrUrl() { return prUrl; }
        public int getPrNumber() { return prNumber; }
        public String getError() { return error; }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl git-gateway -Dtest=GitHubApiManagerTest -Dsurefire.failIfNoSpecifiedTests=false -am -q`
Expected: All 11 tests PASS

- [ ] **Step 5: Commit**

```bash
git add git-gateway/src/main/java/com/axone_io/ignition/git/managers/GitHubApiManager.java
git add git-gateway/src/test/java/com/axone_io/ignition/git/managers/GitHubApiManagerTest.java
git commit -m "feat(hotfix): add GitHubApiManager for PR creation via GitHub REST API"
```

---

### Task 5: HotfixManager — Core Pipeline

**Files:**
- Create: `git-gateway/src/main/java/com/axone_io/ignition/git/managers/HotfixManager.java`
- Create: `git-gateway/src/test/java/com/axone_io/ignition/git/managers/HotfixManagerTest.java`

- [ ] **Step 1: Write tests for hotfix pipeline (branch, commit, merge, cleanup)**

```java
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl git-gateway -Dtest=HotfixManagerTest -Dsurefire.failIfNoSpecifiedTests=false -am -q`
Expected: Compilation failure — `HotfixManager` doesn't exist yet

- [ ] **Step 3: Implement HotfixManager**

```java
package com.axone_io.ignition.git.managers;

import com.axone_io.ignition.git.dto.HotfixResult;
import com.axone_io.ignition.git.dto.HotfixResult.Step;
import com.axone_io.ignition.git.dto.HotfixResult.StepStatus;
import com.axone_io.ignition.git.records.GitProjectsConfigRecord;
import com.axone_io.ignition.git.records.GitReposUsersRecord;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates the hotfix pipeline: branch, commit, push, PR, merge, cleanup.
 * Steps 1-3 are critical (rollback on failure). Steps 4-8 are best-effort.
 */
public class HotfixManager {
    private static final Logger logger = LoggerFactory.getLogger(HotfixManager.class);

    /** In-flight hotfix results, keyed by project name. Polled by Designer for progress. */
    private static final Map<String, HotfixResult> activeHotfixes = new ConcurrentHashMap<>();

    public static HotfixResult getActiveHotfix(String projectName) {
        return activeHotfixes.get(projectName);
    }

    /**
     * Execute the full hotfix pipeline. Called from GatewayScriptModule on a background thread.
     */
    public static HotfixResult execute(Git git, String projectName, String userName,
                                       String hotfixDescription, String commitMessage,
                                       String[] changes, String userEmail,
                                       String repoUri, String token, String gatewayName,
                                       String productionBranch,
                                       GitProjectsConfigRecord configRecord,
                                       com.inductiveautomation.ignition.gateway.localdb.persistence.PersistenceInterface persistence) {
        HotfixResult result = new HotfixResult();
        String branchName = "hotfix/" + GitHubApiManager.sanitizeBranchName(hotfixDescription);
        result.setHotfixBranch(branchName);
        activeHotfixes.put(projectName, result);

        logger.info("[Production Hotfix] INITIATED by '{}' on project '{}': {}", userName, projectName, branchName);

        try {
            // Step 1: Create hotfix branch
            result.updateStep(Step.CREATE_BRANCH, StepStatus.IN_PROGRESS, "");
            try {
                createHotfixBranch(git, GitHubApiManager.sanitizeBranchName(hotfixDescription));
                result.updateStep(Step.CREATE_BRANCH, StepStatus.COMPLETED, "Created " + branchName);
                logger.info("[Production Hotfix] Branch created: {}", branchName);
            } catch (Exception e) {
                result.updateStep(Step.CREATE_BRANCH, StepStatus.FAILED, e.getMessage());
                logger.error("[Production Hotfix] Failed to create branch: {}", branchName, e);
                failPipeline(result, "Failed to create hotfix branch: " + e.getMessage());
                return result;
            }

            // Step 2: Switch to hotfix branch (already done in createHotfixBranch)
            result.updateStep(Step.SWITCH_BRANCH, StepStatus.COMPLETED, "Switched to " + branchName);

            // Step 3: Commit changes
            result.updateStep(Step.COMMIT, StepStatus.IN_PROGRESS, "");
            try {
                String hash = commitHotfix(git, changes, commitMessage, userEmail);
                result.setCommitHash(hash);
                result.updateStep(Step.COMMIT, StepStatus.COMPLETED, "Committed " + changes.length + " changes (" + hash + ")");
                logger.info("[Production Hotfix] Committed: {} \"{}\"", hash, commitMessage);
            } catch (Exception e) {
                result.updateStep(Step.COMMIT, StepStatus.FAILED, e.getMessage());
                logger.error("[Production Hotfix] Commit failed, rolling back", e);
                rollback(git, branchName, productionBranch);
                failPipeline(result, "Commit failed: " + e.getMessage());
                return result;
            }

            // --- Beyond this point, failures are best-effort (commit exists locally) ---

            // Step 4: Push hotfix branch
            result.updateStep(Step.PUSH, StepStatus.IN_PROGRESS, "");
            boolean pushSucceeded = false;
            try {
                String remoteName = GitManager.getRemoteName(git);
                RefSpec refSpec = new RefSpec(
                    Constants.R_HEADS + branchName + ":" + Constants.R_HEADS + branchName
                );
                var pushCmd = git.push().setRemote(remoteName).setRefSpecs(refSpec);
                GitManager.setAuthentication(pushCmd, projectName, userName);
                Iterable<PushResult> pushResults = pushCmd.call();

                pushSucceeded = true;
                result.updateStep(Step.PUSH, StepStatus.COMPLETED, "Pushed to " + remoteName);
                logger.info("[Production Hotfix] Pushed to remote");
            } catch (Exception e) {
                result.updateStep(Step.PUSH, StepStatus.FAILED, e.getMessage());
                logger.error("[Production Hotfix] Push FAILED: {}", e.getMessage());
            }

            // Step 5: Create PR (only if push succeeded)
            if (pushSucceeded && repoUri != null && token != null) {
                result.updateStep(Step.CREATE_PR, StepStatus.IN_PROGRESS, "");
                try {
                    String prTitle = "\uD83D\uDE91 HOTFIX: " + hotfixDescription;
                    String prBody = GitHubApiManager.buildPrBody(
                        userName, gatewayName, projectName,
                        branchName, result.getCommitHash(), commitMessage, changes
                    );

                    GitHubApiManager.CreatePrResult prResult = GitHubApiManager.createPullRequest(
                        repoUri, token, branchName, productionBranch, prTitle, prBody
                    );

                    if (prResult.isSuccess()) {
                        result.setPrUrl(prResult.getPrUrl());
                        result.setPrNumber(prResult.getPrNumber());
                        result.updateStep(Step.CREATE_PR, StepStatus.COMPLETED,
                            "PR #" + prResult.getPrNumber() + " created");
                        logger.info("[Production Hotfix] PR #{} created: {}", prResult.getPrNumber(), prResult.getPrUrl());
                    } else {
                        result.updateStep(Step.CREATE_PR, StepStatus.FAILED, prResult.getError());
                        logger.error("[Production Hotfix] PR creation failed: {}", prResult.getError());
                    }
                } catch (Exception e) {
                    result.updateStep(Step.CREATE_PR, StepStatus.FAILED, e.getMessage());
                    logger.error("[Production Hotfix] PR creation error", e);
                }
            } else if (!pushSucceeded) {
                result.updateStep(Step.CREATE_PR, StepStatus.SKIPPED, "Push failed — skipping PR creation");
                logger.warn("[Production Hotfix] PR creation SKIPPED (push failed)");
            }

            // Step 6: Switch back to main
            result.updateStep(Step.SWITCH_BACK, StepStatus.IN_PROGRESS, "");
            try {
                git.checkout().setName(productionBranch).call();
                result.updateStep(Step.SWITCH_BACK, StepStatus.COMPLETED, "Switched to " + productionBranch);
            } catch (Exception e) {
                result.updateStep(Step.SWITCH_BACK, StepStatus.FAILED, e.getMessage());
                logger.error("[Production Hotfix] Failed to switch back to {}", productionBranch, e);
            }

            // Step 7: Merge hotfix into local main
            result.updateStep(Step.MERGE_LOCAL, StepStatus.IN_PROGRESS, "");
            try {
                mergeHotfixIntoMain(git, branchName, productionBranch);
                result.updateStep(Step.MERGE_LOCAL, StepStatus.COMPLETED, "Merged " + branchName + " into " + productionBranch);
                logger.info("[Production Hotfix] Local merge to {} completed", productionBranch);
            } catch (Exception e) {
                result.updateStep(Step.MERGE_LOCAL, StepStatus.FAILED, e.getMessage());
                logger.error("[Production Hotfix] Local merge failed", e);
            }

            // Step 8: Cleanup hotfix branch
            result.updateStep(Step.CLEANUP, StepStatus.IN_PROGRESS, "");
            try {
                cleanupHotfixBranch(git, branchName);
                result.updateStep(Step.CLEANUP, StepStatus.COMPLETED, "Deleted " + branchName);
            } catch (Exception e) {
                result.updateStep(Step.CLEANUP, StepStatus.FAILED, e.getMessage());
                logger.warn("[Production Hotfix] Branch cleanup failed (non-critical): {}", e.getMessage());
            }

            // Finalize
            boolean success = !result.hasFailures();
            result.setPipelineComplete(true);
            result.setPipelineSuccess(success);

            String status = success ? "COMPLETED" : "COMPLETED_WITH_WARNINGS";
            logger.info("[Production Hotfix] {} — {}", status,
                success ? "all steps succeeded" : "some steps failed, check logs");

            // Persist status to DB
            persistHotfixStatus(configRecord, persistence, result, userName);

        } finally {
            activeHotfixes.remove(projectName);
        }

        return result;
    }

    /** Create a hotfix branch from current HEAD and switch to it. */
    public static String createHotfixBranch(Git git, String sanitizedName) throws Exception {
        String branchName = "hotfix/" + sanitizedName;
        git.branchCreate().setName(branchName).call();
        git.checkout().setName(branchName).call();
        return branchName;
    }

    /** Commit specified files on the current branch. Returns short commit hash. */
    public static String commitHotfix(Git git, String[] files, String message, String email) throws Exception {
        var addCmd = git.add();
        for (String file : files) {
            addCmd.addFilepattern(file);
        }
        addCmd.call();

        RevCommit commit = git.commit()
            .setMessage(message)
            .setAuthor("", email)
            .call();
        return commit.abbreviate(7).name();
    }

    /** Merge hotfix branch into the target branch. Assumes we're already on the target branch. */
    public static void mergeHotfixIntoMain(Git git, String hotfixBranch, String targetBranch) throws Exception {
        // Ensure we're on the target branch
        String currentBranch = git.getRepository().getBranch();
        if (!currentBranch.equals(targetBranch)) {
            git.checkout().setName(targetBranch).call();
        }

        MergeResult mergeResult = git.merge()
            .include(git.getRepository().findRef(hotfixBranch))
            .call();

        if (!mergeResult.getMergeStatus().isSuccessful()) {
            throw new RuntimeException("Merge failed with status: " + mergeResult.getMergeStatus());
        }
    }

    /** Delete the hotfix branch locally. */
    public static void cleanupHotfixBranch(Git git, String branchName) throws Exception {
        git.branchDelete().setBranchNames(branchName).setForce(true).call();
    }

    /** Roll back a failed hotfix: switch to main, delete the hotfix branch. */
    public static void rollback(Git git, String hotfixBranch, String targetBranch) {
        try {
            git.checkout().setName(targetBranch).call();
            git.branchDelete().setBranchNames(hotfixBranch).setForce(true).call();
            logger.info("[Production Hotfix] Rollback completed: switched to {} and deleted {}", targetBranch, hotfixBranch);
        } catch (Exception e) {
            logger.error("[Production Hotfix] Rollback failed — manual cleanup may be needed", e);
        }
    }

    private static void failPipeline(HotfixResult result, String message) {
        result.setPipelineComplete(true);
        result.setPipelineSuccess(false);
        result.setErrorMessage(message);
    }

    private static void persistHotfixStatus(GitProjectsConfigRecord record,
                                            com.inductiveautomation.ignition.gateway.localdb.persistence.PersistenceInterface persistence,
                                            HotfixResult result, String userName) {
        try {
            record.setLastHotfixStatus(result.isPipelineSuccess() ? "COMPLETED" : "COMPLETED_WITH_WARNINGS");
            record.setLastHotfixTimestamp(System.currentTimeMillis());
            record.setLastHotfixUser(userName);
            record.setLastHotfixBranch(result.getHotfixBranch());
            record.setLastHotfixPRUrl(result.getPrUrl());
            persistence.save(record);
        } catch (Exception e) {
            logger.error("[Production Hotfix] Failed to persist hotfix status to DB", e);
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl git-gateway -Dtest=HotfixManagerTest -Dsurefire.failIfNoSpecifiedTests=false -am -q`
Expected: All 5 tests PASS

- [ ] **Step 5: Commit**

```bash
git add git-gateway/src/main/java/com/axone_io/ignition/git/managers/HotfixManager.java
git add git-gateway/src/test/java/com/axone_io/ignition/git/managers/HotfixManagerTest.java
git commit -m "feat(hotfix): add HotfixManager pipeline orchestrator with tests"
```

---

### Task 6: GatewayScriptModule — Hotfix RPC Implementation

**Files:**
- Modify: `git-gateway/src/main/java/com/axone_io/ignition/git/GatewayScriptModule.java:1125`

- [ ] **Step 1: Add hotfix Impl methods to GatewayScriptModule**

Add before the closing `}` of the class (after `restoreTagsFromBackupImpl` around line 1125):

```java
    @Override
    protected HotfixResult executeHotfixImpl(String projectName, String userName,
                                             String hotfixDescription, String commitMessage,
                                             String[] changes) throws Exception {
        logger.info("[Production Hotfix] executeHotfix called for project '{}' by user '{}'", projectName, userName);

        GitProjectsConfigRecord configRecord = GitManager.getGitProjectConfigRecord(projectName);
        GitReposUsersRecord userRecord = GitManager.getGitReposUserRecord(configRecord, userName);
        ProductionModeConfig prodConfig = ProductionModeManager.buildConfig(configRecord);

        if (!prodConfig.isProductionMode()) {
            throw new RuntimeException("Hotfix workflow requires production mode to be enabled");
        }

        String repoUri = configRecord.getURI();
        // Use the PAT (password field) for GitHub API auth
        String token = configRecord.isSSHAuthentication() ? null : userRecord.getPassword();
        String gatewayName = context.getSystemName();
        String productionBranch = prodConfig.getProductionBranch();
        String userEmail = userRecord.getEmail();

        if (productionBranch == null || productionBranch.isEmpty()) {
            productionBranch = "main";
        }

        try (Git git = getGit(getProjectFolderPath(projectName))) {
            return HotfixManager.execute(
                git, projectName, userName, hotfixDescription, commitMessage, changes,
                userEmail, repoUri, token, gatewayName, productionBranch,
                configRecord, context.getPersistenceInterface()
            );
        }
    }

    @Override
    protected HotfixResult getHotfixProgressImpl(String projectName) throws Exception {
        HotfixResult active = HotfixManager.getActiveHotfix(projectName);
        if (active != null) {
            return active;
        }
        // No active hotfix — return last persisted status
        return getLastHotfixStatusImpl(projectName);
    }

    @Override
    protected HotfixResult getLastHotfixStatusImpl(String projectName) throws Exception {
        GitProjectsConfigRecord config = GitManager.getGitProjectConfigRecord(projectName);
        HotfixResult result = new HotfixResult();
        result.setPipelineComplete(true);

        String status = config.getLastHotfixStatus();
        if (status == null || status.isEmpty()) {
            return result; // No hotfix has ever run
        }

        result.setPipelineSuccess("COMPLETED".equals(status));
        result.setHotfixBranch(config.getLastHotfixBranch());
        result.setPrUrl(config.getLastHotfixPRUrl());
        return result;
    }
```

Add imports at the top of the file:

```java
import com.axone_io.ignition.git.dto.HotfixResult;
import com.axone_io.ignition.git.records.GitReposUsersRecord;
```

- [ ] **Step 2: Verify full project compiles**

Run: `mvn compile -pl git-common,git-client,git-gateway -am -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add git-gateway/src/main/java/com/axone_io/ignition/git/GatewayScriptModule.java
git commit -m "feat(hotfix): implement hotfix RPC methods in GatewayScriptModule"
```

---

### Task 7: ProductionModeManager — Hotfix Branch Awareness

**Files:**
- Modify: `git-gateway/src/main/java/com/axone_io/ignition/git/managers/ProductionModeManager.java:42`
- Modify: `git-gateway/src/test/java/com/axone_io/ignition/git/managers/ProductionModeManagerTest.java`

- [ ] **Step 1: Write tests for hotfix branch behavior**

Add to `ProductionModeManagerTest.java` after the existing tests (before the closing `}`):

```java
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
```

- [ ] **Step 2: Run tests to verify the new ones fail**

Run: `mvn test -pl git-gateway -Dtest=ProductionModeManagerTest -Dsurefire.failIfNoSpecifiedTests=false -am -q`
Expected: `isHotfixBranch` tests fail (method doesn't exist), pull/push behavior tests may fail

- [ ] **Step 3: Add isHotfixBranch method to ProductionModeManager**

Add after the `buildConfig` method (after line 33):

```java
    /**
     * Check if a branch name is a hotfix branch.
     */
    public static boolean isHotfixBranch(String branchName) {
        return branchName != null && branchName.startsWith("hotfix/");
    }
```

- [ ] **Step 4: Update validatePull to block on hotfix branches**

Add at the beginning of the `try` block in `validatePull` (after line 48, before the `isRepositorySafe` check):

```java
            // Block pull on hotfix branches — hotfix is a sealed environment
            String currentBranch = git.getRepository().getBranch();
            if (isHotfixBranch(currentBranch)) {
                String warning = "Pull is disabled on hotfix branches. Complete your hotfix first, then pull on main.";
                logger.warn(warning);
                config.setWarningMessage(warning);
                config.setValid(false);
                return false;
            }
```

Note: the existing `String currentBranch = git.getRepository().getBranch();` on line 59 should be removed since we now declare it earlier. Use the earlier declaration for both the hotfix check and the production branch check.

- [ ] **Step 5: Update validatePush to allow hotfix branches without warning**

Add at the beginning of the `try` block in `validatePush` (after line 115, before the `isRepositorySafe` check):

```java
            // Hotfix branches are expected to be pushed — no warning needed
            if (isHotfixBranch(targetBranch)) {
                logger.info("Production mode: allowing push on hotfix branch '{}'", targetBranch);
                return true;
            }
```

- [ ] **Step 6: Run tests to verify all pass**

Run: `mvn test -pl git-gateway -Dtest=ProductionModeManagerTest -Dsurefire.failIfNoSpecifiedTests=false -am -q`
Expected: All tests PASS (including the 5 new ones)

- [ ] **Step 7: Commit**

```bash
git add git-gateway/src/main/java/com/axone_io/ignition/git/managers/ProductionModeManager.java
git add git-gateway/src/test/java/com/axone_io/ignition/git/managers/ProductionModeManagerTest.java
git commit -m "feat(hotfix): add hotfix branch awareness to ProductionModeManager"
```

---

### Task 8: HotfixCommitDialog (Designer)

**Files:**
- Create: `git-designer/src/main/java/com/axone_io/ignition/git/HotfixCommitDialog.java`

- [ ] **Step 1: Create the hotfix commit dialog**

This is a modal JDialog (not JFrame — per our earlier fix) that collects hotfix description, commit message, and shows the changes table. Uses standard Swing layouts (no IntelliJ forms — per CLAUDE.md).

```java
package com.axone_io.ignition.git;

import com.axone_io.ignition.git.components.SelectAllHeader;
import com.inductiveautomation.ignition.designer.gui.CommonUI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Hotfix commit dialog for production mode. Collects hotfix description,
 * commit message, and change selection. Explains the full pipeline that
 * will execute after confirmation.
 *
 * <p>Uses standard Swing layouts only (no IntelliJ forms library).</p>
 */
public class HotfixCommitDialog extends JDialog {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final JTextField descriptionField;
    private final JTextArea messageArea;
    private final JTable changesTable;
    private boolean confirmed = false;

    public HotfixCommitDialog(Component parent, Object[][] changeData, String productionBranch) {
        super(parent instanceof Window ? (Window) parent : SwingUtilities.getWindowAncestor(parent),
              "\uD83D\uDE91 Production Hotfix", ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setResizable(true);

        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(15, 20, 15, 20));

        // Header
        JPanel headerPanel = new JPanel();
        headerPanel.setLayout(new BoxLayout(headerPanel, BoxLayout.X_AXIS));
        headerPanel.setBackground(new Color(255, 235, 238));
        headerPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(244, 67, 54), 2),
            BorderFactory.createEmptyBorder(10, 10, 10, 10)
        ));
        headerPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel headerLabel = new JLabel("HOTFIX — Production Commit Detected");
        headerLabel.setFont(new Font("Dialog", Font.BOLD, 14));
        headerPanel.add(headerLabel);

        mainPanel.add(headerPanel);
        mainPanel.add(Box.createVerticalStrut(10));

        // Pipeline explanation
        JTextArea pipelineInfo = new JTextArea(
            "You are committing on the production branch '" + productionBranch + "'.\n" +
            "This will automatically:\n" +
            "  1. Create a hotfix branch\n" +
            "  2. Commit your changes there\n" +
            "  3. Push to remote\n" +
            "  4. Create a PR \u2192 " + productionBranch + " (labeled hotfix)\n" +
            "  5. Merge into local " + productionBranch + "\n" +
            "  6. Switch you back to " + productionBranch
        );
        pipelineInfo.setEditable(false);
        pipelineInfo.setBackground(mainPanel.getBackground());
        pipelineInfo.setFont(new Font("Dialog", Font.PLAIN, 11));
        pipelineInfo.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(pipelineInfo);
        mainPanel.add(Box.createVerticalStrut(10));

        // Hotfix description
        JLabel descLabel = new JLabel("Hotfix description (used in branch name + PR title):");
        descLabel.setFont(new Font("Dialog", Font.BOLD, 12));
        descLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(descLabel);
        mainPanel.add(Box.createVerticalStrut(3));

        descriptionField = new JTextField();
        descriptionField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        descriptionField.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(descriptionField);
        mainPanel.add(Box.createVerticalStrut(10));

        // Changes table
        JLabel changesLabel = new JLabel("Changes:");
        changesLabel.setFont(new Font("Dialog", Font.BOLD, 12));
        changesLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(changesLabel);
        mainPanel.add(Box.createVerticalStrut(3));

        String[] columnNames = {"", "Resource Name", "Type", "Author"};
        DefaultTableModel model = new DefaultTableModel(changeData, columnNames) {
            public Class<?> getColumnClass(int column) {
                return column == 0 ? Boolean.class : String.class;
            }
        };
        changesTable = new JTable(model);
        changesTable.getColumn("").setPreferredWidth(20);
        changesTable.getColumn("Resource Name").setPreferredWidth(330);
        changesTable.getColumn("Type").setPreferredWidth(100);
        changesTable.getColumn("Author").setPreferredWidth(100);

        TableColumn tc = changesTable.getColumnModel().getColumn(0);
        tc.setHeaderRenderer(new SelectAllHeader(changesTable, 0));

        JScrollPane tableScroll = new JScrollPane(changesTable);
        tableScroll.setPreferredSize(new Dimension(500, 150));
        tableScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(tableScroll);
        mainPanel.add(Box.createVerticalStrut(10));

        // Commit message
        JLabel msgLabel = new JLabel("Commit message:");
        msgLabel.setFont(new Font("Dialog", Font.BOLD, 12));
        msgLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(msgLabel);
        mainPanel.add(Box.createVerticalStrut(3));

        messageArea = new JTextArea(4, 40);
        messageArea.setLineWrap(true);
        messageArea.setWrapStyleWord(true);
        JScrollPane msgScroll = new JScrollPane(messageArea);
        msgScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(msgScroll);
        mainPanel.add(Box.createVerticalStrut(15));

        // Buttons
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        btnPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JButton cancelBtn = new JButton("Cancel");
        cancelBtn.addActionListener(e -> {
            confirmed = false;
            dispose();
        });

        JButton proceedBtn = new JButton("Proceed with Hotfix");
        proceedBtn.setBackground(new Color(244, 67, 54));
        proceedBtn.setForeground(Color.WHITE);
        proceedBtn.setOpaque(true);
        proceedBtn.addActionListener(e -> {
            if (descriptionField.getText().trim().isEmpty()) {
                JOptionPane.showMessageDialog(this, "Hotfix description is required.",
                    "Missing Description", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (messageArea.getText().trim().isEmpty()) {
                JOptionPane.showMessageDialog(this, "Commit message is required.",
                    "Missing Message", JOptionPane.WARNING_MESSAGE);
                return;
            }
            confirmed = true;
            dispose();
        });

        btnPanel.add(cancelBtn);
        btnPanel.add(proceedBtn);
        mainPanel.add(btnPanel);

        setContentPane(mainPanel);
        pack();
        setMinimumSize(new Dimension(550, getHeight()));
        CommonUI.centerComponent(this, parent);
    }

    public boolean isConfirmed() { return confirmed; }
    public String getHotfixDescription() { return descriptionField.getText().trim(); }
    public String getCommitMessage() { return messageArea.getText().trim(); }

    public List<String> getSelectedChanges() {
        List<String> selected = new ArrayList<>();
        DefaultTableModel model = (DefaultTableModel) changesTable.getModel();
        for (int row = 0; row < model.getRowCount(); row++) {
            if ((Boolean) model.getValueAt(row, 0)) {
                selected.add((String) model.getValueAt(row, 1));
            }
        }
        return selected;
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `mvn compile -pl git-designer -am -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add git-designer/src/main/java/com/axone_io/ignition/git/HotfixCommitDialog.java
git commit -m "feat(hotfix): add HotfixCommitDialog for Designer"
```

---

### Task 9: HotfixProgressDialog (Designer)

**Files:**
- Create: `git-designer/src/main/java/com/axone_io/ignition/git/HotfixProgressDialog.java`

- [ ] **Step 1: Create the progress dialog**

```java
package com.axone_io.ignition.git;

import com.axone_io.ignition.git.dto.HotfixResult;
import com.axone_io.ignition.git.dto.HotfixResult.Step;
import com.axone_io.ignition.git.dto.HotfixResult.StepStatus;
import com.inductiveautomation.ignition.designer.gui.CommonUI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;

/**
 * Progress dialog that polls the gateway for hotfix pipeline status.
 * Shows each step with a status icon, updates in real time.
 *
 * <p>Uses standard Swing layouts only (no IntelliJ forms library).</p>
 */
public class HotfixProgressDialog extends JDialog {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final JLabel[] stepLabels;
    private final JLabel[] statusIcons;
    private final JLabel summaryLabel;
    private final JButton closeButton;
    private final Timer pollTimer;
    private final GitScriptInterface rpc;
    private final String projectName;

    private static final String ICON_PENDING = "\u2B1C";     // white square
    private static final String ICON_IN_PROGRESS = "\u23F3"; // hourglass
    private static final String ICON_COMPLETED = "\u2705";   // green check
    private static final String ICON_FAILED = "\u274C";      // red X
    private static final String ICON_SKIPPED = "\u23ED";     // skip forward

    public HotfixProgressDialog(Component parent, GitScriptInterface rpc, String projectName) {
        super(parent instanceof Window ? (Window) parent : SwingUtilities.getWindowAncestor(parent),
              "\uD83D\uDE91 Hotfix in Progress", ModalityType.APPLICATION_MODAL);
        this.rpc = rpc;
        this.projectName = projectName;
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setResizable(false);

        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(15, 20, 15, 20));

        Step[] steps = Step.values();
        stepLabels = new JLabel[steps.length];
        statusIcons = new JLabel[steps.length];

        for (int i = 0; i < steps.length; i++) {
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
            row.setAlignmentX(Component.LEFT_ALIGNMENT);

            statusIcons[i] = new JLabel(ICON_PENDING);
            statusIcons[i].setFont(new Font("Dialog", Font.PLAIN, 14));
            row.add(statusIcons[i]);

            stepLabels[i] = new JLabel(steps[i].getDisplayName());
            stepLabels[i].setFont(new Font("Dialog", Font.PLAIN, 12));
            row.add(stepLabels[i]);

            mainPanel.add(row);
        }

        mainPanel.add(Box.createVerticalStrut(10));

        summaryLabel = new JLabel(" ");
        summaryLabel.setFont(new Font("Dialog", Font.BOLD, 12));
        summaryLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(summaryLabel);
        mainPanel.add(Box.createVerticalStrut(10));

        closeButton = new JButton("Close");
        closeButton.setEnabled(false);
        closeButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        closeButton.addActionListener(e -> dispose());
        mainPanel.add(closeButton);

        setContentPane(mainPanel);
        pack();
        setMinimumSize(new Dimension(400, getHeight()));
        CommonUI.centerComponent(this, parent);

        // Poll every 500ms for progress updates
        pollTimer = new Timer(500, e -> pollProgress());
        pollTimer.start();
    }

    private void pollProgress() {
        try {
            HotfixResult result = rpc.getHotfixProgress(projectName);
            if (result == null) return;

            Step[] steps = Step.values();
            for (int i = 0; i < steps.length; i++) {
                StepStatus status = result.getStepStatus(steps[i]);
                String message = result.getStepMessage(steps[i]);
                statusIcons[i].setText(iconFor(status));

                String label = steps[i].getDisplayName();
                if (message != null && !message.isEmpty()) {
                    label += " \u2014 " + message;
                }
                stepLabels[i].setText(label);
            }

            if (result.isPipelineComplete()) {
                pollTimer.stop();
                closeButton.setEnabled(true);
                setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

                if (result.isPipelineSuccess()) {
                    String prInfo = result.getPrUrl() != null
                        ? "PR created: " + result.getPrUrl()
                        : "PR was not created (check logs)";
                    summaryLabel.setText("Hotfix complete. " + prInfo);
                    summaryLabel.setForeground(new Color(56, 142, 60)); // green
                } else {
                    summaryLabel.setText("Hotfix completed with warnings \u2014 check steps above.");
                    summaryLabel.setForeground(new Color(244, 67, 54)); // red
                }
            }
        } catch (Exception e) {
            logger.error("Error polling hotfix progress", e);
        }
    }

    private String iconFor(StepStatus status) {
        return switch (status) {
            case PENDING -> ICON_PENDING;
            case IN_PROGRESS -> ICON_IN_PROGRESS;
            case COMPLETED -> ICON_COMPLETED;
            case FAILED -> ICON_FAILED;
            case SKIPPED -> ICON_SKIPPED;
        };
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `mvn compile -pl git-designer -am -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add git-designer/src/main/java/com/axone_io/ignition/git/HotfixProgressDialog.java
git commit -m "feat(hotfix): add HotfixProgressDialog for pipeline progress tracking"
```

---

### Task 10: GitActionManager — Hotfix Detection in Commit Flow

**Files:**
- Modify: `git-designer/src/main/java/com/axone_io/ignition/git/managers/GitActionManager.java`

- [ ] **Step 1: Add hotfix detection to the commit action**

The existing commit flow goes through `GitBaseAction.handleAction(COMMIT)` which calls `GitActionManager.showCommitPopup()`. We need to intercept before the commit popup appears.

Add a new method to `GitActionManager` that wraps the commit flow with hotfix detection. Add after `showPullPopupInternal()` (around line 234):

```java
    /**
     * Check if current commit should trigger the hotfix workflow.
     * Called from the toolbar Commit action and from the save-prompt.
     */
    public static void showCommitWithHotfixDetection(String projectName, String userName) {
        // Use cached production config if available, otherwise fetch
        SwingWorker<Object[], Void> worker = new SwingWorker<Object[], Void>() {
            @Override
            protected Object[] doInBackground() throws Exception {
                ProductionModeConfig config = rpc.getProductionModeConfig(projectName);
                String currentBranch = rpc.getCurrentBranch(projectName);
                Object[][] changeData = getCommitPopupData();
                return new Object[]{config, currentBranch, changeData};
            }

            @Override
            protected void done() {
                try {
                    Object[] results = get();
                    ProductionModeConfig config = (ProductionModeConfig) results[0];
                    String currentBranch = (String) results[1];
                    Object[][] changeData = (Object[][]) results[2];

                    // Detect hotfix scenario: production mode + on production branch
                    boolean isHotfix = config.isProductionMode()
                        && config.getProductionBranch() != null
                        && config.getProductionBranch().equals(currentBranch);

                    if (isHotfix) {
                        logger.info("Hotfix scenario detected: production mode on branch '{}'", currentBranch);
                        showHotfixCommitDialog(projectName, userName, config, changeData);
                    } else {
                        // Normal commit flow
                        showCommitPopup(projectName, userName);
                    }
                } catch (Exception e) {
                    logger.error("Error checking hotfix scenario", e);
                    // Fall back to normal commit flow
                    showCommitPopup(projectName, userName);
                }
            }
        };
        worker.execute();
    }

    private static void showHotfixCommitDialog(String projectName, String userName,
                                                ProductionModeConfig config, Object[][] changeData) {
        String productionBranch = config.getProductionBranch();

        HotfixCommitDialog dialog = new HotfixCommitDialog(
            context.getFrame(), changeData, productionBranch
        );
        dialog.setVisible(true); // Blocks (modal)

        if (!dialog.isConfirmed()) {
            return;
        }

        String description = dialog.getHotfixDescription();
        String message = dialog.getCommitMessage();
        List<String> changes = dialog.getSelectedChanges();

        if (changes.isEmpty()) {
            JOptionPane.showMessageDialog(context.getFrame(),
                "No changes selected for hotfix.", "No Changes", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Launch progress dialog and execute hotfix on gateway
        executeHotfixWithProgress(projectName, userName, description, message, changes.toArray(new String[0]));
    }

    private static void executeHotfixWithProgress(String projectName, String userName,
                                                   String description, String message, String[] changes) {
        // Start the hotfix on the gateway in background
        SwingWorker<HotfixResult, Void> executor = new SwingWorker<HotfixResult, Void>() {
            @Override
            protected HotfixResult doInBackground() throws Exception {
                return rpc.executeHotfix(projectName, userName, description, message, changes);
            }

            @Override
            protected void done() {
                // Progress dialog handles completion via polling — nothing to do here
            }
        };
        executor.execute();

        // Show progress dialog (modal — blocks until pipeline completes)
        HotfixProgressDialog progressDialog = new HotfixProgressDialog(
            context.getFrame(), rpc, projectName
        );
        progressDialog.setVisible(true);
    }
```

Add imports at the top of `GitActionManager.java`:

```java
import com.axone_io.ignition.git.dto.HotfixResult;
import com.axone_io.ignition.git.HotfixCommitDialog;
import com.axone_io.ignition.git.HotfixProgressDialog;
import java.util.List;
```

- [ ] **Step 2: Update the COMMIT action in GitBaseAction to use the new detection flow**

Find the COMMIT case in `GitBaseAction.handleAction()` and update it to call `showCommitWithHotfixDetection` instead of `showCommitPopup`. Read `GitBaseAction.java` to find the exact location, then make the change.

- [ ] **Step 3: Wire cache invalidation into pull, branch switch, and hotfix completion**

In `GitActionManager`, after the pull completes successfully (in the `handlePullAction` success path), add:

```java
DesignerHook.invalidateProductionConfigCache();
```

In the branch switch success path (in `showBranchPopup`'s switch completion handler), add the same call.

In `executeHotfixWithProgress`, after the progress dialog closes, add the same call.

- [ ] **Step 4: Add pull blocking for hotfix branches in the pull flow**

In `GitActionManager.showPullPopup()`, after the production mode check succeeds (inside `done()`, around line 187), add a hotfix branch check:

```java
                    // Block pull on hotfix branches
                    String currentBranch = rpc.getCurrentBranch(projectName);
                    if (currentBranch != null && currentBranch.startsWith("hotfix/")) {
                        JOptionPane.showMessageDialog(
                            context.getFrame(),
                            "Pull is disabled on hotfix branches.\nComplete your hotfix first, then pull on main.",
                            "Hotfix Branch — Pull Blocked",
                            JOptionPane.WARNING_MESSAGE
                        );
                        return;
                    }
```

This goes before the production mode popup check, so it catches both production and non-production mode pulls on hotfix branches.

- [ ] **Step 5: Verify compilation**

Run: `mvn compile -pl git-designer -am -q`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add git-designer/src/main/java/com/axone_io/ignition/git/managers/GitActionManager.java
git add git-designer/src/main/java/com/axone_io/ignition/git/actions/GitBaseAction.java
git commit -m "feat(hotfix): add hotfix detection to commit flow and pull blocking"
```

---

### Task 11: DesignerHook — Save Prompt & Config Caching

**Files:**
- Modify: `git-designer/src/main/java/com/axone_io/ignition/git/DesignerHook.java`

- [ ] **Step 1: Add cached production config and refresh timer**

Add fields after line 43 (after `sharedPopupMenuRef`):

```java
    private static ProductionModeConfig cachedProductionConfig;
    private Timer productionConfigRefreshTimer;
```

Add import at top:

```java
import com.axone_io.ignition.git.dto.ProductionModeConfig;
```

- [ ] **Step 2: Initialize the cache during startup**

Add at the end of `startup()` (after `initDocsContextMenu()` around line 103):

```java
        // Cache production mode config for save-hook detection
        refreshProductionConfig();
        productionConfigRefreshTimer = new Timer(300000, e -> refreshProductionConfig()); // 5 minutes
        productionConfigRefreshTimer.start();
```

Add the refresh method:

```java
    private void refreshProductionConfig() {
        try {
            cachedProductionConfig = rpc.getProductionModeConfig(projectName);
            logger.debug("Refreshed cached production config: {}", cachedProductionConfig);
        } catch (Exception e) {
            logger.debug("Unable to refresh production config cache: {}", e.getMessage());
        }
    }

    public static ProductionModeConfig getCachedProductionConfig() {
        return cachedProductionConfig;
    }

    /** Called after operations that may change production state (pull, branch switch, hotfix). */
    public static void invalidateProductionConfigCache() {
        cachedProductionConfig = null;
        // Will be re-fetched on next save or by the timer
    }
```

- [ ] **Step 3: Add commit prompt to notifyProjectSaveDone**

Replace the existing `notifyProjectSaveDone()` (lines 242-244):

```java
    @Override
    public void notifyProjectSaveDone() {
        super.notifyProjectSaveDone();

        // In production mode, prompt the engineer to commit after saving
        if (cachedProductionConfig != null && cachedProductionConfig.isProductionMode()) {
            SwingUtilities.invokeLater(() -> {
                int result = JOptionPane.showConfirmDialog(
                    context.getFrame(),
                    "You've saved changes on a production gateway.\nWould you like to commit and track these changes?",
                    "Production Mode — Commit Changes?",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE
                );
                if (result == JOptionPane.YES_OPTION) {
                    GitActionManager.showCommitWithHotfixDetection(projectName, userName);
                }
            });
        }
    }
```

Add import:

```java
import com.axone_io.ignition.git.managers.GitActionManager;
```

- [ ] **Step 4: Stop the timer in shutdown**

Add before the existing `sharedPopupMenuRef` cleanup in `shutdown()` (around line 256):

```java
        if (productionConfigRefreshTimer != null) {
            productionConfigRefreshTimer.stop();
        }
```

- [ ] **Step 5: Verify full project compiles**

Run: `mvn clean package -DskipTests -q`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add git-designer/src/main/java/com/axone_io/ignition/git/DesignerHook.java
git commit -m "feat(hotfix): add save-prompt and production config caching to DesignerHook"
```

---

### Task 12: Run All Tests & Final Build

**Files:** None (verification only)

- [ ] **Step 1: Run all gateway tests**

Run: `mvn test -pl git-gateway -Dsurefire.failIfNoSpecifiedTests=false -am`
Expected: All tests pass — ProductionModeManagerTest, HotfixManagerTest, GitHubApiManagerTest

- [ ] **Step 2: Full project build**

Run: `mvn clean package -DskipTests`
Expected: BUILD SUCCESS, `Git-unsigned.modl` generated at `git-build/target/Git-unsigned.modl`

- [ ] **Step 3: Commit any test fixes if needed**

If any tests fail, fix and commit.

- [ ] **Step 4: Final commit with all changes verified**

```bash
git log --oneline -12
```

Expected commit history (newest first):
```
feat(hotfix): add save-prompt and production config caching to DesignerHook
feat(hotfix): add hotfix detection to commit flow and pull blocking
feat(hotfix): add HotfixProgressDialog for pipeline progress tracking
feat(hotfix): add HotfixCommitDialog for Designer
feat(hotfix): add hotfix branch awareness to ProductionModeManager
feat(hotfix): implement hotfix RPC methods in GatewayScriptModule
feat(hotfix): add HotfixManager pipeline orchestrator with tests
feat(hotfix): add GitHubApiManager for PR creation via GitHub REST API
feat(hotfix): add hotfix status fields to GitProjectsConfigRecord
feat(hotfix): add hotfix RPC interface methods
feat(hotfix): add HotfixResult DTO for pipeline state
```
