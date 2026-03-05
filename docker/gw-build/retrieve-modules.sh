#!/usr/bin/env bash
set -euo pipefail
shopt -s inherit_errexit

###############################################################################
# Retrieves third-party modules and verifies their checksums
###############################################################################
function main() {
    if [ -z "${SUPPLEMENTAL_MODULES}" ]; then
        return 0  # Silently exit if there are no supplemental modules to target
    fi

    retrieve_modules
}

###############################################################################
# Download the modules
###############################################################################
function retrieve_modules() {
    IFS=', ' read -r -a module_install_key_arr <<< "${SUPPLEMENTAL_MODULES}"
    for module_install_key in "${module_install_key_arr[@]}"; do
        download_url_env="SUPPLEMENTAL_${module_install_key^^}_DOWNLOAD_URL"
        download_sha256_env="SUPPLEMENTAL_${module_install_key^^}_DOWNLOAD_SHA256"
        if [ -n "${!download_url_env:-}" ] && [ -n "${!download_sha256_env:-}" ]; then
            download_basename=$(basename "${!download_url_env}")

            # Skip download if file already exists (e.g., copied from local build context)
            if [ -f "${download_basename}" ]; then
                echo "Module ${download_basename} already exists, skipping download"
                continue
            fi

            # Check if URL is a local path (doesn't start with http)
            if [[ "${!download_url_env}" != http* ]]; then
                if [ -f "${!download_url_env}" ]; then
                    cp "${!download_url_env}" "./${download_basename}"
                    echo "Copied local module from ${!download_url_env}"
                else
                    echo "Error: Local module not found at ${!download_url_env}"
                    exit 1
                fi
            elif [[ "${!download_url_env}" == *"github.com"* ]]; then
                # GitHub release assets from private repos require token auth
                local token_file="${GITHUB_TOKEN_FILE:-/run/secrets/git-user-token}"
                local token=""
                local auth_header=""
                if [ -f "$token_file" ] && [ -s "$token_file" ]; then
                    token=$(tr -d '\r\n' < "$token_file")
                elif [ -n "${GIT_USER_TOKEN:-}" ]; then
                    token="${GIT_USER_TOKEN}"
                fi
                if [ -n "$token" ]; then
                    auth_header="Authorization: token ${token}"
                fi

                local resolved_url="${!download_url_env}"

                # Resolve /releases/latest → actual asset URL via GitHub API
                if [[ "$resolved_url" == */releases/latest ]]; then
                    # Extract owner/repo from URL: https://github.com/OWNER/REPO/releases/latest
                    local owner_repo
                    owner_repo=$(echo "$resolved_url" | sed -E 's|https://github\.com/([^/]+/[^/]+)/releases/latest|\1|')
                    local api_url="https://api.github.com/repos/${owner_repo}/releases/latest"

                    echo "Resolving latest release for ${owner_repo}..."
                    local release_json_file="/tmp/gh_release_$$.json"
                    # Try unauthenticated first (works for public repos), fall back to auth
                    if ! wget --header="Accept: application/vnd.github+json" "$api_url" -O "$release_json_file" 2>/dev/null; then
                        if [ -n "$auth_header" ]; then
                            echo "  Retrying with authentication..."
                            wget --header="Accept: application/vnd.github+json" --header="$auth_header" "$api_url" -O "$release_json_file" || {
                                echo "ERROR: GitHub API request failed for ${api_url}" >&2
                                exit 1
                            }
                        else
                            echo "ERROR: GitHub API request failed for ${api_url}" >&2
                            echo "  If this is a private repo, set GIT_USER_TOKEN in .env." >&2
                            exit 1
                        fi
                    fi
                    local release_json
                    release_json=$(<"$release_json_file")

                    # Find the first .modl asset
                    resolved_url=$(echo "$release_json" | jq -r '.assets[] | select(.name | endswith(".modl")) | .browser_download_url' | head -1)
                    if [ -z "$resolved_url" ] || [ "$resolved_url" = "null" ]; then
                        echo "Error: No .modl asset found in latest release of ${owner_repo}"
                        exit 1
                    fi

                    local tag_name
                    tag_name=$(echo "$release_json" | jq -r '.tag_name')
                    download_basename=$(basename "$resolved_url")
                    echo "Resolved to ${tag_name}: ${download_basename}"
                fi

                if [ -n "$auth_header" ]; then
                    wget --header="$auth_header" \
                         --header="Accept: application/octet-stream" \
                         "$resolved_url" -O "${download_basename}"
                else
                    echo "Warning: No GitHub token found at ${token_file}, attempting unauthenticated download"
                    wget "$resolved_url" -O "${download_basename}"
                fi
            else
                wget --ca-certificate=/etc/ssl/certs/ca-certificates.crt --referer https://inductiveautomation.com/* "${!download_url_env}"
            fi

            [[ "notused" == "${!download_sha256_env}" ]] || echo "${!download_sha256_env}" "${download_basename}" | sha256sum -c -
        else
            echo "Error finding specified module ${module_install_key} in build args, aborting..."
            exit 1
        fi
    done
}

###############################################################################
# Outputs to stderr
###############################################################################
function debug() {
  # shellcheck disable=SC2236
  if [ ! -z ${verbose+x} ]; then
    >&2 echo "  DEBUG: $*"
  fi
}

###############################################################################
# Print usage information
###############################################################################
function usage() {
  >&2 echo "Usage: $0 -m \"space-separated modules list\""
  >&2 echo "    -m: space-separated list of module identifiers to download"
}

# Argument Processing
while getopts ":hvm:" opt; do
  case "$opt" in
  v)
    verbose=1
    ;;
  m)
    SUPPLEMENTAL_MODULES="${OPTARG}"
    ;;
  h)
    usage
    exit 0
    ;;
  \?)
    usage
    echo "Invalid option: -${OPTARG}" >&2
    exit 1
    ;;
  :)
    usage
    echo "Invalid option: -${OPTARG} requires an argument" >&2
    exit 1
    ;;
  esac
done

# shift positional args based on number consumed by getopts
shift $((OPTIND-1))

# exit on missing required args - allow empty for optional modules
if [ -z "${SUPPLEMENTAL_MODULES:-}" ]; then
  echo "No supplemental modules specified, skipping module retrieval"
  exit 0
fi

main
