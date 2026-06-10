# Scripting API (`system.git.*`)

The Git module exposes a curated scripting API in all scopes: **Gateway**
(Gateway Event Scripts — Startup, Timer), **Designer** (Script Console), and
**Vision Client**.

Only tag import and read-only status functions are exposed. Write operations
(commit, push, branch switching, hotfix) are deliberately Designer-only, where
production-mode safeguards apply.

## Functions

### `system.git.importTags(projectName, [collisionPolicy])`

Imports git-tracked tags from the project repository's working tree into the
gateway tag providers. Does **not** pull — it imports whatever is on disk.
Arguments are positional (keyword-style calls are not supported).

> **Warning:** Tag providers are gateway-wide. Importing overwrites provider
> contents and affects **all** projects sharing those providers. If multiple
> projects track the same provider, only the authoritative project should
> import (last writer wins).
>
> A scripted import bypasses the production-mode Designer checklist. This is
> acceptable because it is a local working-tree → provider operation that never
> touches git history, but treat it with the same care as a Designer-side
> import.

| Parameter | Type | Description |
|---|---|---|
| `projectName` | str | Ignition project whose repo tags to import |
| `collisionPolicy` | str (optional) | `"a"` abort, `"m"` merge, `"o"` overwrite. Defaults to the repo's `tag_config` policy |

Returns `True` on completion.

```python
# Gateway Startup event script: re-import tags after a gateway restart
system.git.importTags("MyProject")

# Overwrite explicitly
system.git.importTags("MyProject", "o")
```

### Read-only status functions

| Function | Returns |
|---|---|
| `system.git.getUncommittedChanges(projectName, userName)` | List of UncommittedChange (path, type, actor) |
| `system.git.getCurrentBranch(projectName)` | Current branch name (str) |
| `system.git.getBranchStatus(projectName, userName)` | BranchStatus (changes, conflicts, merge state, ahead/behind) |
| `system.git.listBranches(projectName, userName)` | List of BranchInfo |
| `system.git.getCommitHistory(projectName, userName, maxCount)` | List of CommitInfo |
| `system.git.listRepositoryTags(projectName)` | List of git tag names (str) |
| `system.git.getGitTrackedProjectNames()` | List of git-tracked project names (str) |

```python
# Example: log repo status from a gateway timer script
for project in system.git.getGitTrackedProjectNames():
    branch = system.git.getCurrentBranch(project)
    changes = system.git.getUncommittedChanges(project, "admin")
    system.util.getLogger("git-status").info(
        "%s on %s: %d uncommitted change(s)" % (project, branch, len(changes)))
```

## Errors

- Calling any function before the gateway connection is ready raises
  `IllegalStateException` ("system.git is not ready...").
- A missing/blank `projectName` raises `IllegalArgumentException`.
- Gateway-side failures propagate to the calling script as exceptions.
