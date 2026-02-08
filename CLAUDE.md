# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Ignition module providing Git integration for Ignition projects. Users can manage Git repositories directly from Ignition Designer (commit, push, pull, branch management). Requires Ignition 8.3.1+ and Java 17+.

## Build Commands

```bash
# Full build (output: git-build/target/Git-unsigned.modl)
mvn clean package -DskipTests

# Run tests
mvn test

# Build frontend only (React/TypeScript)
cd web/packages/gateway && npm run build
```

## Hot Deployment to Docker

**Note:** Set the following environment variables for your local setup:
- `IGNITION_GIT_MODULE_PATH`: Path to your local ignition-git-module clone
- `DEPLOYMENTS_PATH`: Path to your whk-services-deployments directory

```bash
# Build, copy to Docker, restart gateway
cd $IGNITION_GIT_MODULE_PATH && \
mvn clean package -DskipTests && \
cp git-build/target/Git-unsigned.modl $DEPLOYMENTS_PATH/gw-build/modules/ && \
docker cp git-build/target/Git-unsigned.modl whk-services-ignition-1:/usr/local/bin/ignition/user-lib/modules/ && \
cd $DEPLOYMENTS_PATH && \
docker compose restart ignition

# Verify module loaded
docker logs whk-services-ignition-1 2>&1 | grep -i "git" | tail -10
```

**Important:** After deploying, you must **restart the Designer** to pick up the new module version.

## Architecture

### Module Structure

```
ignition-git-module/
├── git-common/          # Shared classes (interfaces, DTOs) - scope: CDG
├── git-client/          # Vision client implementation - scope: C
├── git-designer/        # Designer UI and actions - scope: CD
├── git-gateway/         # Gateway implementation and RPC - scope: G
├── git-build/           # Module packaging and web frontend
└── web/packages/gateway # React frontend for Gateway config pages
```

Scope Legend: **C** = Client (Vision), **D** = Designer, **G** = Gateway

### Key Classes

| Class | Module | Purpose |
|-------|--------|---------|
| `GitScriptInterface` | git-common | RPC interface defining all Git operations |
| `AbstractScriptModule` | git-common | Abstract base with wrapper methods |
| `GatewayScriptModule` | git-gateway | Gateway implementation using JGit |
| `ClientScriptModule` | git-client | Client RPC delegations |
| `GitActionManager` | git-designer | Designer UI actions and popups |
| `BranchPopup` | git-designer | Branch switching UI |
| `DesignerHook` | git-designer | Designer module lifecycle |
| `GatewayHook` | git-gateway | Gateway module lifecycle |

### Data Transfer Objects (DTOs)

- `BranchInfo` - Branch metadata (name, local/remote, current, ahead/behind)
- `BranchStatus` - Repository state (uncommitted changes, conflicts, merge state)
- `CommitInfo` - Commit metadata (hash, message, author, timestamp)
- `UncommittedChange` - Changed file info (path, type, actor)

## RPC Considerations

### Method Signatures

- **Do NOT use overloaded methods** in `GitScriptInterface` - RPC doesn't handle overloading well
- Always use explicit method names or include all parameters in a single method
- Changes to interface methods require updates in:
  1. `GitScriptInterface` (interface definition)
  2. `AbstractScriptModule` (wrapper method)
  3. `GatewayScriptModule` (implementation)
  4. `ClientScriptModule` (RPC delegation)

### Serialization

All DTOs must:
- Implement `Serializable`
- Have a default no-arg constructor
- Use primitive types or standard Java collections

## UI Development

### Designer Popups

**Do NOT use IntelliJ's forms library** (`GridLayoutManager`, `GridConstraints`) as it causes classloader conflicts in the Ignition Designer.

Use standard Swing layouts instead: `BorderLayout`, `BoxLayout`, `FlowLayout`, `GridBagLayout`

### Gateway Config Pages

Located in `web/packages/gateway/src/`. Built with React/TypeScript.

## Common Issues

### "Wrong number of arguments" RPC Error

Caused by method overloading in the RPC interface. Solution: Remove overloaded methods and use a single method with all parameters.

### ClassLoader / GridConstraints Error

```
class java.lang.String cannot be cast to class com.intellij.uiDesigner.core.GridConstraints
```

Caused by using IntelliJ's forms library in Designer code. Solution: Rewrite UI using standard Swing layouts.

### "Invalid remote: origin"

The git remote might not be named "origin". The module uses `getRemoteName()` to detect the actual remote name from the repository config.

### Merge Conflicts After Checkout

If conflicts appear after a branch switch, the repository was likely already in a merge state. Use the merge conflict resolution buttons (only shown when in MERGING state) or run:

```bash
docker exec -it whk-services-ignition-1 bash
cd /usr/local/bin/ignition/data/projects/<project-name>
git status  # Check state
git merge --abort  # If in merge state
```

## File Locations in Docker Container

- Module: `/usr/local/bin/ignition/user-lib/modules/Git-unsigned.modl`
- Projects: `/usr/local/bin/ignition/data/projects/<project-name>/`
- Git repos: `/usr/local/bin/ignition/data/projects/<project-name>/.git/`
