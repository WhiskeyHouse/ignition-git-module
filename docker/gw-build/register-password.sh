#!/usr/bin/env bash
set -euo pipefail
shopt -s inherit_errexit

###############################################################################
# Update an Ignition SQLite Configuration DB with a baseline username/password
# ----------------------------------------------------------------------------
# ref: https://gist.github.com/thirdgen88/c4257bd4c47b6cc7194d1f5e7cbd6444
###############################################################################
function main() {
  if [ ! -f "${SECRET_LOCATION}" ]; then
      echo "ERROR: Secret file not found at '${SECRET_LOCATION}'" >&2
      echo "  The Docker build secret may not have mounted correctly." >&2
      echo "  Ensure GATEWAY_ADMIN_PASSWORD is set in .env and Docker BuildKit is enabled." >&2
      exit 1
  elif [ ! -f "${DB_LOCATION}" ]; then
      echo "ERROR: ${DB_FILE} not found, cannot register admin password" >&2
      exit 1
  fi

  register_password
}

###############################################################################
# Updates the target Config DB with the target username and salted pw hash
###############################################################################
function register_password() {
  local SQLITE3=( sqlite3 "${DB_LOCATION}" ) password_hash

  echo "Registering Admin Password with Configuration DB"

  # Generate Salted PW Hash (strip \r for Windows compatibility)
  password_hash=$(generate_salted_hash "$(tr -d '\r' < "${SECRET_LOCATION}")")

  # Update INTERNALUSERTABLE
  echo "  Setting default admin user to USERNAME='${GATEWAY_ADMIN_USERNAME}'"
  "${SQLITE3[@]}" "UPDATE INTERNALUSERTABLE SET USERNAME='${GATEWAY_ADMIN_USERNAME}', PASSWORD='${password_hash}' WHERE PROFILEID=1 AND USERID=1"

  echo "  Admin credentials registered successfully (username=${GATEWAY_ADMIN_USERNAME})"
}

###############################################################################
# Processes password input and translates to salted hash
###############################################################################
function generate_salted_hash() {
  local -u auth_salt
  local auth_pwhash auth_pwsalthash auth_password password_input
  password_input="${1}"

  auth_salt=$(date +%s | sha256sum | head -c 8)
  debug "auth_salt is ${auth_salt}"
  auth_pwhash=$(printf %s "${password_input}" | sha256sum - | cut -c -64)
  debug "auth_pwhash is ${auth_pwhash}"
  auth_pwsalthash=$(printf %s "${password_input}${auth_salt}" | sha256sum - | cut -c -64)
  debug "auth_pwsalthash is ${auth_pwsalthash}"
  auth_password="[${auth_salt}]${auth_pwsalthash}"

  echo "${auth_password}"
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
  >&2 echo "Usage: $0 -u <string> -f <path/to/file> -d <path/to/db>"
}

# Argument Processing
while getopts ":hvu:f:d:" opt; do
  case "$opt" in
  v)
    verbose=1
    ;;
  u)
    GATEWAY_ADMIN_USERNAME="${OPTARG}"
    ;;
  f)
    SECRET_LOCATION="${OPTARG}"
    ;;
  d)
    DB_LOCATION="${OPTARG}"
    DB_FILE=$(basename "${DB_LOCATION}")
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

if [ -z "${GATEWAY_ADMIN_USERNAME:-}" ] || [ -z "${SECRET_LOCATION:-}" ] || [ -z "${DB_LOCATION:-}" ]; then
  usage
  exit 1
fi

main
