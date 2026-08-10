# Minimal HTTP git server for the test rig.
#
# Why not a bare repo on a shared volume: GitProjectsConfigRecord.isSSHAuthentication() is
# `!uri.startsWith("http")`, so a file:// remote would route the module through its SSH transport
# callback — a different code path from the one production gateways use. Serving over HTTP keeps
# setAuthentication() on the username/password provider, which is what we are trying to exercise.
#
# Why not Gitea: creating the first admin user requires a CLI call inside the container, which
# makes `docker compose up` non-deterministic. Anonymous push is fine here — the module still
# sends credentials, the server just does not check them.
FROM httpd:2.4

RUN apt-get update \
    && apt-get install -y --no-install-recommends git \
    && rm -rf /var/lib/apt/lists/*

# git-http-backend is a CGI script; the stock image ships the CGI modules commented out, and the
# LoadModule lines are tab-indented inside MPM <IfModule> blocks — hence the leading [[:space:]]*.
# Both are uncommented: the surrounding IfModule guards mean only the one matching the active MPM
# actually loads.
RUN sed -i -E 's|^[[:space:]]*#(LoadModule cgid?_module)|\1|' /usr/local/apache2/conf/httpd.conf \
    && grep -qE '^LoadModule cgid_module' /usr/local/apache2/conf/httpd.conf \
    && echo "Include conf/httpd-git.conf" >> /usr/local/apache2/conf/httpd.conf

COPY httpd-git.conf /usr/local/apache2/conf/httpd-git.conf
COPY init-repos.sh /usr/local/bin/init-repos.sh
RUN chmod +x /usr/local/bin/init-repos.sh

EXPOSE 80
CMD ["/bin/sh", "-c", "/usr/local/bin/init-repos.sh && httpd-foreground"]
