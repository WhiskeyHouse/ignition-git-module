#!/usr/bin/env bash
# Build the module and load it into the test rig.
#
# Copies rather than bind-mounting git-build/target directly: `mvn clean` deletes that file while
# the container holds its inode, leaving the gateway serving a module it can no longer read — which
# shows up as a 404 on the license during commissioning rather than as anything obviously wrong.
set -euo pipefail

RIG_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${RIG_DIR}/../.." && pwd)"
MODULE="${REPO_ROOT}/git-build/target/Git-unsigned.modl"

if [ "${1:-}" != "--no-build" ]; then
    echo "==> Building module"
    (cd "${REPO_ROOT}" && mvn -q package -DskipTests)
fi

if [ ! -f "${MODULE}" ]; then
    echo "ERROR: ${MODULE} not found. Run without --no-build, or 'mvn package -DskipTests' first." >&2
    exit 1
fi

mkdir -p "${RIG_DIR}/modules"
cp "${MODULE}" "${RIG_DIR}/modules/Git-unsigned.modl"
echo "==> Copied $(du -h "${MODULE}" | cut -f1) module into the rig"

if docker compose -f "${RIG_DIR}/docker-compose.yml" ps --status running --quiet gateway >/dev/null 2>&1; then
    echo "==> Restarting gateway"
    docker compose -f "${RIG_DIR}/docker-compose.yml" restart gateway
    echo "==> Done. Watch: docker compose logs -f gateway | grep -i git"
else
    echo "==> Gateway not running. Start it with: docker compose up -d"
fi
