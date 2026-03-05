# syntax=docker/dockerfile:1.5
ARG IGNITION_VERSION=${IGNITION_VERSION}
FROM inductiveautomation/ignition:${IGNITION_VERSION} AS prep

# Temporarily become root for system-level updates (required for 8.1.26+)
USER root

# Install prerequisite packages
RUN apt-get update && apt-get install -y wget ca-certificates jq zip unzip sqlite3

# Example supplemental modules — uncomment and add to SUPPLEMENTAL_MODULES to include
ARG SUPPLEMENTAL_MQTTTRANSMISSION_DOWNLOAD_URL="https://files.inductiveautomation.com/third-party/cirrus-link/5.0.2/MQTT-Transmission-signed.modl"
ARG SUPPLEMENTAL_MQTTTRANSMISSION_DOWNLOAD_SHA256="fa6d49d7c8fea016e46ed67115752e7ea961ef742809bae138a8cb80e788a006"
ARG SUPPLEMENTAL_MQTTENGINE_DOWNLOAD_URL="https://files.inductiveautomation.com/third-party/cirrus-link/5.0.2/MQTT-Engine-signed.modl"
ARG SUPPLEMENTAL_MQTTENGINE_DOWNLOAD_SHA256="d03a28436f7eaa454e667dc1e59fce203813d9f65bcefbed8aba2728e99ada1b"
ARG SUPPLEMENTAL_MQTTDISTRIBUTOR_DOWNLOAD_URL="https://files.inductiveautomation.com/third-party/cirrus-link/5.0.2/MQTT-Distributor-signed.modl"
ARG SUPPLEMENTAL_MQTTDISTRIBUTOR_DOWNLOAD_SHA256="fa1fbf2eed734f36f3f854d568f206d46c1929a26a387b7d117d502a34238f77"

# Git module — latest release from GitHub (resolved via API at build time)
ARG SUPPLEMENTAL_GIT_DOWNLOAD_URL="https://github.com/WhiskeyHouse/ignition-git-module/releases/latest"
ARG SUPPLEMENTAL_GIT_DOWNLOAD_SHA256="notused"
ARG SUPPLEMENTAL_MODULES
# Fallback for BuildKit secret — safe because prep stage is discarded (multi-stage build)
ARG GIT_USER_TOKEN=""

# Set working directory for this prep image and ensure that exits from sub-shells bubble up and report an error
WORKDIR /root
SHELL [ "/usr/bin/env", "-S", "bash", "-euo", "pipefail", "-O", "inherit_errexit", "-c" ]

# Retrieve all targeted modules and verify their integrity
COPY --chmod=0755 retrieve-modules.sh .
RUN --mount=type=secret,id=git-user-token \
    bash retrieve-modules.sh \
    -m "${SUPPLEMENTAL_MODULES:-}" && \
    # Normalize Git module filename to match base.gwbk registration
    for f in Git-*-unsigned.modl; do [ -f "$f" ] && mv "$f" Git-unsigned.modl; done

# Set CERTIFICATES/EULAS acceptance in gateway backup config db
COPY base.gwbk .
COPY --chmod=0755 register-module.sh register-password.sh ./
ARG GATEWAY_ADMIN_USERNAME="admin"
RUN --mount=type=secret,id=gateway-admin-password \
    unzip -q base.gwbk db_backup_sqlite.idb && \
    echo "==> Build context: GATEWAY_ADMIN_USERNAME=${GATEWAY_ADMIN_USERNAME}" && \
    if [ "${GATEWAY_ADMIN_USERNAME}" = "admin" ]; then \
      echo "WARNING: GATEWAY_ADMIN_USERNAME is 'admin' (the default). If this is unintended, check that .env is present and readable by Docker Compose." >&2; \
    fi && \
    shopt -s nullglob; \
    for module in *.modl; do \
    ./register-module.sh \
      -f "${module}" \
      -d db_backup_sqlite.idb; \
    done; \
    shopt -u nullglob && \
    ./register-password.sh \
      -u "${GATEWAY_ADMIN_USERNAME}" \
      -f /run/secrets/gateway-admin-password \
      -d db_backup_sqlite.idb && \
    zip -q -f base.gwbk db_backup_sqlite.idb || \
    if [[ ${ZIP_EXIT_CODE:=$?} == 12 ]]; then \
    echo "No changes to internal database needed during module registration."; \
    else \
    echo "Unknown error (${ZIP_EXIT_CODE}) encountered during re-packaging of config db, exiting." && \
    exit ${ZIP_EXIT_CODE}; \
    fi

# Final Image
FROM inductiveautomation/ignition:${IGNITION_VERSION} AS final

# Temporarily become root for system-level updates (required for 8.1.26+)
USER root

# Add supplemental packages
RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    git && \
    rm -rf /var/lib/apt/lists/*

# Embed modules and base gwbk from prep image as well as entrypoint shim
COPY --from=prep --chown=ignition:ignition /root/*.modl ${IGNITION_INSTALL_LOCATION}/user-lib/modules/
COPY --from=prep --chown=ignition:ignition /root/base.gwbk ${IGNITION_INSTALL_LOCATION}/base.gwbk
COPY --chmod=0755 --chown=root:root docker-entrypoint-shim.sh /usr/local/bin/

# Return to ignition user
USER ignition

# Supplement other default environment variables
ENV ACCEPT_IGNITION_EULA=Y \
    IGNITION_EDITION=standard \
    GATEWAY_MODULES_ENABLED=all

# Target the entrypoint shim for any custom logic prior to gateway launch
ENTRYPOINT [ "docker-entrypoint-shim.sh" ]
