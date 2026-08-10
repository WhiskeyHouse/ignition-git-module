#!/bin/sh
# Seeds the bare repositories the test rig commissions against.
#
# Idempotent: re-running leaves existing repos alone, so `docker compose restart git-server`
# does not wipe state you are mid-test on. Use `docker compose down -v` for a clean slate.
set -eu

ROOT=/srv/git
mkdir -p "$ROOT"

export GIT_AUTHOR_NAME="Test Rig"
export GIT_AUTHOR_EMAIL="rig@example.invalid"
export GIT_COMMITTER_NAME="$GIT_AUTHOR_NAME"
export GIT_COMMITTER_EMAIL="$GIT_AUTHOR_EMAIL"

seed_repo() {
    name="$1"
    project="$2"
    bare="$ROOT/$name.git"

    if [ -d "$bare" ]; then
        echo "[init-repos] $name.git already exists, leaving it alone"
        return
    fi

    echo "[init-repos] creating $name.git"
    git init --bare --initial-branch=main "$bare" >/dev/null
    # Anonymous push: the module still sends credentials, this server just does not check them.
    git -C "$bare" config http.receivepack true

    work=$(mktemp -d)
    git init --initial-branch=main "$work" >/dev/null
    printf '# %s\n\nSeeded by the ignition-git-module test rig.\n' "$name" > "$work/README.md"
    # Ignition needs a project manifest to import the checkout as a project. Without it,
    # GitProjectManager.loadProjectManifest throws NoSuchFileException and commissioning aborts
    # for every project that would have followed.
    cat > "$work/project.json" <<JSON
{
  "title": "${project}",
  "description": "ignition-git-module test rig",
  "parent": "",
  "enabled": true,
  "inheritable": false,
  "attributes": {}
}
JSON
    # A tags/ tree so the export has something to be compared against, and so the completeness
    # guard has a prior state to protect.
    mkdir -p "$work/tags"
    printf '{"includedProviders":[],"excludedTagPaths":[],"collisionPolicy":"a"}\n' > "$work/tags/.tag-config.json"
    git -C "$work" add -A >/dev/null
    git -C "$work" commit -q -m "chore: seed $name" >/dev/null
    git -C "$work" push -q "$bare" main >/dev/null
    rm -rf "$work"
}

seed_repo owner-project TestOwner
seed_repo other-project TestOther

chown -R www-data:www-data "$ROOT" 2>/dev/null || true
echo "[init-repos] done"
