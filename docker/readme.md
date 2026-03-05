# Docker Deployment Guide

This guide covers deploying the Ignition Git Module using Docker Compose, including project commissioning via `git.yaml`.

## Prerequisites

- [Docker](https://www.docker.com/) installed
- Configure the `.env` file (especially `COMPOSE_FILE` to select which compose variant to use)

## Quick Start

1. Place the Git module `.modl` file in `./modules/` (standard) or configure the download URL in `./gw-build/Dockerfile` (automated)
2. Configure `./gw-init/git.yaml` with your project repositories (see [YAML Configuration](#yaml-configuration))
3. Set the gateway admin password in `./gw-secrets/GATEWAY_ADMIN_PASSWORD`
4. Set `GATEWAY_GIT_USER_SECRET` in `.env` with the Git user password or PAT
5. Run: `docker compose up`

## Compose Variants

### Standard (`docker-compose.yml`)

Mounts a pre-built `.modl` file directly into the container. Requires you to place the module file in `./modules/`.

### Automated (`docker-compose-automated.yml`)

Builds a derived Ignition image that downloads and installs the module at build time. Set the download URL via `SUPPLEMENTAL_GIT_DOWNLOAD_URL` in `./gw-build/Dockerfile`.

Select the variant by setting `COMPOSE_FILE` in `.env`:

```env
# Standard
COMPOSE_FILE=docker-compose.yml

# Automated
COMPOSE_FILE=docker-compose-automated.yml
```

## YAML Configuration

The `git.yaml` file defines which Git repositories to clone and sync into Ignition projects. It is mounted into the container at `/usr/local/bin/ignition/data/git.yaml`.

### Format

The file is a YAML list. Each entry configures one project:

```yaml
- repo_uri: https://github.com/your-org/your-repo.git
  repo_branch: main
  ignition_projectName: MyProject
  ignition_userName: admin
  ignition_inheritable: false
  ignition_parentName: null
  user_name: git-username
  user_email: user@example.com
  user_password: <set via env var instead>
  commissioning_importThemes: true
  commissioning_importTags: true
  commissioning_importImages: true
  initDefaultBranch: main
```

### Field Reference

| Field | Required | Type | Description |
|-------|----------|------|-------------|
| `repo_uri` | Yes | String | Git repository URL (HTTPS or SSH) |
| `repo_branch` | Yes | String | Branch to clone/track |
| `ignition_projectName` | Yes | String | Name of the Ignition project to create |
| `ignition_userName` | Yes | String | Ignition user that owns the project config |
| `ignition_inheritable` | No | Boolean | Whether the project is inheritable (default: `false`) |
| `ignition_parentName` | No | String | Parent project name for project inheritance |
| `user_name` | Yes | String | Git username for authentication |
| `user_email` | Yes | String | Git user email (used for commit authoring) |
| `user_password` | Yes* | String | Git password or Personal Access Token. *Prefer using `GATEWAY_GIT_USER_SECRET` env var instead |
| `commissioning_importThemes` | No | Boolean | Import theme resources on commissioning (default: `false`) |
| `commissioning_importTags` | No | Boolean | Import tag provider resources on commissioning (default: `false`) |
| `commissioning_importImages` | No | Boolean | Import image resources on commissioning (default: `false`) |
| `initDefaultBranch` | No | String | Default branch name when initializing a new repository |

### Multiple Projects

You can commission multiple projects by adding entries to the YAML list:

```yaml
- repo_uri: https://github.com/your-org/global-project.git
  repo_branch: main
  ignition_projectName: Global
  ignition_userName: admin
  user_name: git-user
  user_email: user@example.com
  user_password: <via env>
  commissioning_importThemes: true
  commissioning_importTags: true
  commissioning_importImages: true

- repo_uri: https://github.com/your-org/hmi-project.git
  repo_branch: main
  ignition_projectName: HMI
  ignition_userName: admin
  ignition_inheritable: false
  ignition_parentName: Global
  user_name: git-user
  user_email: user@example.com
  user_password: <via env>
  commissioning_importThemes: true
  commissioning_importTags: false
  commissioning_importImages: true
```

## Authentication

Git credentials can be provided in three ways, listed from most to least recommended:

### 1. Environment Variable (Recommended)

Set `GATEWAY_GIT_USER_SECRET` in `.env` or your container orchestrator. The value is used directly as the password/PAT for all configured projects:

```env
GATEWAY_GIT_USER_SECRET=ghp_xxxxxxxxxxxxxxxxxxxx
```

This overrides any `user_password` value in `git.yaml`.

### 2. Secret File

Set `GATEWAY_GIT_USER_SECRET_FILE` to a file path inside the container. The file contents are read as the secret:

```yaml
# docker-compose.yml
environment:
  - GATEWAY_GIT_USER_SECRET_FILE=/run/secrets/git-password
secrets:
  git-password:
    file: ./gw-secrets/GIT_PASSWORD
```

This is useful with Docker Swarm secrets or Kubernetes secret volume mounts.

### 3. Inline in YAML (Not Recommended)

Setting `user_password` directly in `git.yaml` works but is less secure since the file may be committed to version control.

### SSH Authentication

For SSH-based repos, configure the SSH private key via the same env var mechanisms (`GATEWAY_GIT_USER_SECRET` or `GATEWAY_GIT_USER_SECRET_FILE`). The module auto-detects SSH vs. HTTPS based on the repository configuration in the gateway database.

## Commissioning Behavior

### First Start (New Project)

When the gateway starts and the project **does not exist**:

1. Creates the Ignition project
2. Clones the repository from `repo_uri` on branch `repo_branch`
3. Imports project resources into Ignition
4. Optionally imports tags, themes, and images (per `commissioning_import*` flags)

### Subsequent Starts (Existing Project — Idempotent Sync)

When the gateway starts and the project **already exists** with a `.git` directory:

1. **Stashes** any uncommitted local changes (safety net — no work is lost)
2. **Fetches** the latest refs from the remote
3. **Switches branch** if the current branch differs from `repo_branch` in `git.yaml`
4. **Pulls** the latest changes from the configured branch
5. **Re-imports** project resources (and optionally tags/themes/images)

This ensures that restarting a container converges the project to the desired state defined in `git.yaml`.

If the project exists but has **no `.git` directory** (a non-git project that happens to share the name), sync is skipped with a warning.

### Error Handling

- Sync failures are logged but **do not prevent the gateway from starting**
- If stashing fails, sync continues (there may be nothing to stash)
- If pull fails (e.g., merge conflict), the project remains in its current state
- If branch switching fails, pull is skipped to avoid leaving the repo in a broken state

### Monitoring Sync

Check the gateway logs for commissioning activity:

```bash
docker logs <container-name> 2>&1 | grep -i "git\|stash\|sync\|pull\|commission"
```

Example log output for a successful sync:

```
Syncing existing project 'MyProject' to configured state...
Project 'MyProject' has uncommitted changes, stashing...
Stashed uncommitted changes for project 'MyProject': abc1234...
Fetching from remote 'my-repo' for project 'MyProject'...
Pulling latest changes for project 'MyProject' on branch 'main'...
Pull result for project 'MyProject': success
Sync complete for project 'MyProject'.
```

## File Locations Inside the Container

| Path | Purpose |
|------|---------|
| `/usr/local/bin/ignition/data/git.yaml` | Commissioning configuration |
| `/usr/local/bin/ignition/user-lib/modules/` | Module installation directory |
| `/usr/local/bin/ignition/data/projects/<name>/` | Ignition project directories |
| `/usr/local/bin/ignition/data/projects/<name>/.git/` | Git repository data |

## Troubleshooting

### Project not syncing on restart

Verify the project has a `.git` directory inside the container:

```bash
docker exec <container> ls -la /usr/local/bin/ignition/data/projects/<project-name>/.git
```

If missing, the project was likely created manually (not via commissioning). Remove the project and restart to let commissioning clone it fresh.

### "Invalid remote: origin"

The module uses the repository's "humanish name" (derived from the URL) as the remote name, not `origin`. This is normal. The sync code auto-detects the actual remote name.

### Authentication failures during sync

Ensure `GATEWAY_GIT_USER_SECRET` is set in the container's environment:

```bash
docker exec <container> env | grep GATEWAY_GIT
```

If using a PAT, verify it hasn't expired.

### Merge conflicts after sync

If a pull results in merge conflicts, the project remains in its pre-pull state and the error is logged. To resolve manually:

```bash
docker exec -it <container> bash
cd /usr/local/bin/ignition/data/projects/<project-name>
git status
git merge --abort   # Reset to pre-merge state
```

### Stashed changes

If you need to recover auto-stashed changes after a sync:

```bash
docker exec -it <container> bash
cd /usr/local/bin/ignition/data/projects/<project-name>
git stash list      # View stashed changes
git stash pop       # Restore most recent stash
```
