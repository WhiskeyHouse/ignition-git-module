# Ignition with developer mode enabled, so the rig can load the unsigned module.
#
# `mvn package` produces Git-unsigned.modl, which carries no certificates.p7b. Ignition 8.3 gates
# it twice — "module is unsigned and developer mode not enabled" and "certificate not yet
# accepted" — and the commissioning UI can only clear the licence. The certificate step has
# nothing to accept, so an unsigned module cannot be admitted through the UI at all.
#
# The flag has to be baked into the image rather than appended to a running container: the data
# directory is a volume seeded from the image on first boot, and by the time you can edit
# ignition.conf the gateway has already scanned and quarantined the module. Setting it here means
# developer mode is on before the first scan.
#
# Development only. This disables module signature enforcement.
ARG IGNITION_VERSION=8.3.3
FROM inductiveautomation/ignition:${IGNITION_VERSION}

USER root

# Appended after the highest existing wrapper.java.additional.N — the wrapper stops reading at the
# first gap in the sequence, so a hardcoded index would silently drop this or shadow a real one.
RUN CONF=/usr/local/bin/ignition/data/ignition.conf \
    && NEXT=$(( $(grep -oE '^wrapper\.java\.additional\.[0-9]+=' "$CONF" \
                  | grep -oE '[0-9]+' | sort -n | tail -1) + 1 )) \
    && echo "wrapper.java.additional.${NEXT}=-Dignition.allowunsignedmodules=true" >> "$CONF" \
    && grep -q 'allowunsignedmodules' "$CONF"

USER ignition
