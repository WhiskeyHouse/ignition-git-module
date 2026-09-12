# Link a development project

Start with a development gateway and a repository intended for that project. Install the module using the [installation guide](installation.md).

1. Open the gateway's Git Projects configuration page. Link the Ignition project to its remote repository.
2. Add the Git user credentials needed for that repository in Git Users.
3. Open the project in Designer and locate its Git operations.
4. Make a small project resource change, then open the commit dialog. Inspect the resource list before committing.
5. Review the commit in history. Push when the commit is ready to share.

Git credentials control access to the remote repository. An Ignition user identity alone does not grant permission to push.

## What the workflow covers

Project resource commits and gateway configuration exports are separate operations. Tags are shared gateway resources, so inspect export / import settings before using them across projects.

Read [production mode](production-mode.md) before enabling its branch restrictions or hotfix workflow. The [scripting API](scripting-api.md) exposes repository status and tag import, with its own scope and collision behavior.

For automated commissioning, see the [Docker guide](https://github.com/WhiskeyHouse/ignition-git-module/blob/main/docker/readme.md).
