# Docker Compose Guide

### Prerequisites
- Docker: https://www.docker.com/
- Configure `.env` file (especially with the right `COMPOSE_FILE`)

### Standard Docker Compose
- Put the Git module in `./gw-build/modules/` folder
- Fill `./gw-init/git.yaml` with your repository configurations
- Fill `./gw-secrets/GATEWAY_ADMIN_PASSWORD` with the gateway admin password
- Fill `./gw-secrets/GATEWAY_GIT_USER_SECRET` with the Git user password or SSH key (not necessary if the password is directly set in `git.yaml`, but less secure)
- Modify the docker-compose to your liking
- Run: `docker compose up`

### Automated Docker Compose (Derived Image Solution)
Based on: https://github.com/thirdgen88/ignition-derived-example
- Set the Git module download URL in `./gw-build/Dockerfile` (`SUPPLEMENTAL_GIT_DOWNLOAD_URL`)
- Fill `./gw-init/git.yaml` with your repository configurations
- Fill `./gw-secrets/GATEWAY_ADMIN_PASSWORD` with the gateway admin password
- Fill `./gw-secrets/GATEWAY_GIT_USER_SECRET` with the Git user password or SSH key (not necessary if the password is directly set in `git.yaml`, but less secure)
- Modify the docker-compose to your liking
- Run: `docker compose up`
