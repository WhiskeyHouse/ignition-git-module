# Hotfix Workflow for Production Mode

**Date:** 2026-04-02
**Status:** Approved

## Problem

When a control engineer needs to make an emergency fix on a production Ignition gateway, production mode currently blocks or warns on git operations. There is no structured workflow for:
- Tracking the hotfix in git with proper branching
- Creating a PR for review/traceability
- Indicating the fix is already live on production
- Merging the fix back into the main branch without pulling in unrelated changes

## Solution

An automated hotfix pipeline triggered when a commit is made in production mode on the production branch. The module handles branching, committing, pushing, PR creation, and local merge-back — the engineer just saves, commits, and confirms.

## Trigger: Save Hook & Commit Prompt

### Save Detection

`DesignerHook.notifyProjectSaveDone()` triggers the commit prompt when production mode is enabled.

- Production mode config is **cached** in the Designer at startup to avoid RPC calls on every save
- Cache is refreshed: every 5 minutes, after branch switch, after pull, after hotfix completion
- The prompt is a lightweight notification-style dialog, not a full popup
- "Later" dismisses — the engineer can always use the normal Commit toolbar button

### notifyProjectSaveStart Fix

The current `notifyProjectSaveStart` implementation is not reliably capturing changes. The fix will investigate whether `context.getProject().getChanges()` returns correct data at this lifecycle point, and if not, use a `ProjectChangeListener` or the `SaveContext` parameter to capture the change set earlier.

### Commit Dialog Auto-Selection

When the commit dialog opens from the save prompt, resources from the just-completed save are auto-selected. This uses the existing mechanism where `DesignerHook.changes` (captured at save time) is correlated with uncommitted repo changes in `GitActionManager.getCommitPopupData()`.

## Hotfix Detection

When the engineer clicks "Commit Now" (from the save prompt) or uses the toolbar Commit button:

- **Condition:** production mode enabled AND current branch equals configured production branch
- **If true:** show the Hotfix Commit Dialog instead of the normal CommitPopup
- **If false:** normal commit flow (existing behavior)

If the engineer is already on a `hotfix/*` branch, no auto-detection occurs — they're already in the controlled workflow and the normal commit flow applies.

## Hotfix Commit Dialog

A modal dialog (`HotfixCommitDialog`) that collects:

- **Hotfix description** — used in branch name and PR title (e.g., "fix pump alarm threshold")
- **Commit message** — the actual git commit message (separate from description)
- **Changes table** — same as existing `CommitPopup`, pre-populated from the save with auto-selected resources

The dialog explains what will happen:
1. Create branch: `hotfix/<sanitized-description>`
2. Commit changes there
3. Push to remote
4. Create a PR to main (labeled hotfix)
5. Merge into local main
6. Switch back to main
7. Clean up hotfix branch

Branch name sanitization: lowercase, spaces to hyphens, strip special characters, prefix with `hotfix/`.

## Hotfix Pipeline

### Execution Model

The pipeline executes on the **gateway side** via `HotfixManager`. The Designer submits the request via RPC and polls for progress. This ensures:
- The gateway has direct filesystem access to the git repo
- If the Designer disconnects mid-pipeline, the gateway can complete
- Consistent with existing architecture where all git operations are gateway-side

### Pipeline Steps

| # | Step | Git Operation | Failure Handling |
|---|------|---------------|------------------|
| 1 | Create hotfix branch | `git branch hotfix/<name>` | Roll back, report error |
| 2 | Switch to hotfix branch | `git checkout hotfix/<name>` | Delete branch, report error |
| 3 | Commit changes | `git add <files> && git commit` | Switch back to main, delete branch, report error |
| 4 | Push hotfix branch | `git push -u <remote> hotfix/<name>` | Warn user, continue with local steps |
| 5 | Create PR | GitHub REST API | Warn user, continue with local steps |
| 6 | Switch back to main | `git checkout main` | Warn user |
| 7 | Merge hotfix into local main | `git merge hotfix/<name>` | Warn user |
| 8 | Delete local hotfix branch | `git branch -d hotfix/<name>` | Non-critical, log only |

**Failure philosophy:** Steps 1-3 are critical — failure triggers full rollback. Steps 4-8 are best-effort — the commit exists locally regardless. The progress dialog shows what failed and tells the engineer what to do manually.

### Local Merge (Not Remote Pull)

After the hotfix, the module merges the hotfix branch into **local** main rather than pulling from remote. This is critical: remote main may contain other merged changes not intended for this production gateway. The local merge only brings in the engineer's fix commit.

### Progress Dialog

`HotfixProgressDialog` — a modal dialog showing real-time pipeline progress:

```
  Created branch: hotfix/fix-pump-alarm
  Committed 2 changes
  Pushed to remote
  Creating pull request...
  Merging hotfix into local main
  Switching back to main
  Cleaning up hotfix branch
```

Each step shows a status icon (pending/in-progress/success/failed). On partial failure, the dialog shows which step failed and provides guidance.

## Hotfix Branches in Production Mode

When on a `hotfix/*` branch, production mode behavior changes:

| Operation | Behavior | Rationale |
|---|---|---|
| **Pull** | **Blocked** | Hotfix branch is a sealed environment — only the engineer's commits belong here |
| **Push** | Allowed, no warning | Expected operation — pushing the hotfix branch to remote |
| **Commit** | Normal flow | Already on hotfix branch, no auto-detection needed |

The block message for pull: "Pull is disabled on hotfix branches. Complete your hotfix first, then pull on main."

## GitHub PR Creation

### GitHubApiManager

A new class that handles GitHub REST API calls using Java's built-in `HttpClient`. No new dependencies.

### Repo URL Parsing

Extracts owner/repo from the existing `repo_uri` config:
- `https://github.com/WHK01/repo.git` -> `WHK01/repo`
- `git@github.com:WHK01/repo.git` -> `WHK01/repo`

### Authentication

Reuses the existing PAT from `GitReposUsersRecord` (the user's git password field). Sent as `Authorization: Bearer <token>` header.

### PR Content

**Title:** `HOTFIX: <hotfix description>`

**Labels:** `hotfix`, `production`

**Body:**
```markdown
## Production Hotfix — Already Live

This fix was applied directly to the production gateway and is **already running**.

| | |
|---|---|
| **Applied by** | <ignition username> |
| **Applied at** | <ISO 8601 timestamp> |
| **Gateway** | <gateway system name from GatewayContext.getSystemName()> |
| **Project** | <project name> |
| **Branch** | hotfix/<description> |
| **Commit** | <short hash> |

### Changes
- <list of changed resources with type>

### Commit Message
<full commit message>

---
Auto-generated by Ignition Git Module (Production Hotfix Workflow)
```

The gateway system name comes from `GatewayContext.getSystemName()` to identify which production instance the fix was applied to.

## Admin Visibility

### Gateway Logs

Every hotfix operation is logged at INFO/WARN level with the `[Production Hotfix]` prefix:

```
[Production Hotfix] INITIATED by 'jsmith' on project 'WHK-MES': hotfix/fix-pump-alarm
[Production Hotfix] Branch created: hotfix/fix-pump-alarm
[Production Hotfix] Committed: abc1234 "Fix pump alarm threshold..."
[Production Hotfix] Pushed to remote
[Production Hotfix] PR #42 created: https://github.com/WHK01/repo/pull/42
[Production Hotfix] Local merge to main completed
[Production Hotfix] COMPLETED SUCCESSFULLY
```

On failure:
```
[Production Hotfix] Push FAILED: authentication error
[Production Hotfix] PR creation SKIPPED (push failed)
[Production Hotfix] COMPLETED WITH WARNINGS — push failed, manual intervention needed
```

### Gateway Config Page Status

New fields on `GitProjectsConfigRecord`:
- `LastHotfixStatus` (String) — e.g., "COMPLETED", "COMPLETED_WITH_WARNINGS", "FAILED"
- `LastHotfixTimestamp` (Timestamp)
- `LastHotfixUser` (String)
- `LastHotfixBranch` (String)
- `LastHotfixPRUrl` (String, nullable)

Displayed on the gateway web config page so admins can see at a glance whether the last hotfix succeeded and if any manual follow-up is needed.

## New & Modified Files

### New Files

| File | Module | Purpose |
|---|---|---|
| `HotfixManager.java` | git-gateway | Orchestrates the hotfix pipeline |
| `GitHubApiManager.java` | git-gateway | GitHub REST API client for PR creation |
| `HotfixProgressDialog.java` | git-designer | Progress dialog showing pipeline steps |
| `HotfixCommitDialog.java` | git-designer | Commit dialog with hotfix description field |
| `HotfixResult.java` | git-common | DTO for pipeline result (step statuses, PR URL, errors) |

### Modified Files

| File | Change |
|---|---|
| `DesignerHook.java` | Cache production config, fix `notifyProjectSaveDone` to prompt commit |
| `GitActionManager.java` | Detect hotfix scenario in commit flow, block pull on hotfix branches |
| `ProductionModeManager.java` | Recognize `hotfix/*` branches — block pull, allow push |
| `GatewayScriptModule.java` | Implement `executeHotfix()`, `getHotfixProgress()`, `getLastHotfixStatus()` |
| `GitScriptInterface.java` | Add hotfix RPC methods |
| `AbstractScriptModule.java` | Abstract hotfix methods |
| `ClientScriptModule.java` | RPC delegation for hotfix methods |
| `GitProjectsConfigRecord.java` | Add LastHotfix* fields |

### RPC Interface Additions

```java
HotfixResult executeHotfix(String projectName, String userName,
                           String hotfixDescription, String commitMessage,
                           String[] changes) throws Exception;

HotfixResult getHotfixProgress(String projectName) throws Exception;

HotfixResult getLastHotfixStatus(String projectName) throws Exception;
```

`getHotfixProgress()` is polled by the Designer during pipeline execution to update the progress dialog. It returns the current step statuses from the in-flight `HotfixResult`. `getLastHotfixStatus()` reads the persisted result from the DB for admin visibility.

## Not In Scope

- Hotfix configuration in YAML (hotfix is a runtime behavior of production mode, not a config option)
- Multi-commit hotfixes (this workflow is for single-commit emergency fixes; complex fixes should use normal branching)
- Automatic PR merge (the PR exists for review/traceability — merging is a team decision)
- Gateway web UI for triggering hotfixes (hotfixes are initiated from Designer only)
