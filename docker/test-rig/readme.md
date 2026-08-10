# Test rig — export ownership and pull policy

A dedicated, self-contained gateway for exercising the four defects fixed in #49 and the watcher
gate in #50. Nothing here touches GitHub or any real repository.

**This is not `docker/docker-compose.yml`.** That stack commissions against live `WHK01/*`
repositories with a real token. These tests deliberately force branch divergence and refused
pulls, which is not something to point at production repos.

## What is in it

| Service | Purpose | Host port |
|---------|---------|-----------|
| `git-server` | Apache + `git-http-backend`, two seeded bare repos | `3300` |
| `gateway` | Ignition 8.3.3 with the module bind-mounted from `git-build/target/` | `9188` (http), `9143` (https) |

Two Ignition projects are commissioned from `gw-init/git.yaml`:

| Project | Repo | `gateway_exportResources` |
|---------|------|---------------------------|
| `TestOwner` | `owner-project.git` | `true` |
| `TestOther` | `other-project.git` | *absent* |

### Why an HTTP git server rather than a bare repo on a volume

`GitProjectsConfigRecord.isSSHAuthentication()` is `!uri.startsWith("http")`, so a `file://`
remote routes the module through its **SSH** transport callback — a different code path from the
one production gateways use. Serving over HTTP keeps `setAuthentication()` on the
username/password provider. The server accepts anonymous push; the module still resolves and
sends credentials, so the path under test is unchanged.

## Running it

```bash
cd docker/test-rig
./deploy.sh                       # build the module and copy it into the rig
docker compose up -d              # first run pulls the Ignition image
docker compose logs -f gateway    # watch commissioning
```

Startup is zero-touch — no commissioning clicks. That takes three separate settings,
because Ignition 8.3 gates a module three ways and the UI can only clear one of them:

| Gate | Cleared by | Why the UI cannot |
|------|-----------|-------------------|
| Unsigned module | `-Dignition.allowunsignedmodules=true` (`gateway/Dockerfile`) | not a commissioning step |
| Certificate acceptance | `ACCEPT_MODULE_CERTS` env var | `Git-unsigned.modl` has no `certificates.p7b`, so the UI shows "Trust Certificates: 0" — nothing to accept |
| Licence agreement | `ACCEPT_MODULE_LICENSES` env var | the UI step works, but does not persist to the `EULAS` table |

`ModuleUtil.certificateAccepted` / `.licenseAccepted` short-circuit to `true` when the module id
appears in those variables. Developer mode alone is **not** enough — it only waives the signature
check, and the module is still quarantined for "certificate not yet accepted".

> **This matters beyond the rig.** The same three gates apply to any freshly commissioned 8.3
> gateway. An existing gateway that already accepted the module keeps working, but rebuild it —
> or stand up a new one — and the module will not load without either signing it or setting these
> variables.

Gateway UI: <http://localhost:9188> — `admin` / `password`.

To test a new build of the module:

```bash
mvn clean package -DskipTests            # from the repo root
docker compose restart gateway           # the .modl is bind-mounted, no image rebuild
```

Clean slate (destroys both repos and gateway state):

```bash
docker compose down -v
```

## Test matrix

Each scenario has a deterministic trigger — none depend on winning a race.

### D — only the designated owner exports gateway resources

*Covers: `GatewayResourceExportPolicy`, `exportConfigImpl` gate.*

1. In the Designer for **`TestOther`**, make any change and commit through the module.
2. Expect in the gateway log:
   `Not exporting gateway-scoped resources for project 'TestOther': gateway-scoped resources
   (tags, themes, images) are owned by project 'TestOwner'`
3. Confirm `other-project.git` received **no** `tags/`, `themes/` or `images/` content:
   ```bash
   git clone http://localhost:3300/git/other-project.git /tmp/other && ls /tmp/other
   ```
4. Repeat from **`TestOwner`** — the export must run and `owner-project.git` must receive `tags/`.

**Unconfigured variant.** Set `gateway_exportResources: false` on both projects in
`gw-init/git.yaml`, `docker compose restart gateway`, and confirm both projects log the
"no project on this gateway is designated" message and neither exports. This is the state every
gateway lands in immediately after upgrading.

### C — a partial export is refused, not published

*Covers: `describeProviderLoss`, `exportStaged`.*

The startup race is hard to hit on demand, so reproduce its *effect* — a provider present in the
repository but absent from the export:

1. Clone `owner-project.git`, add a provider directory that does not exist on the gateway, push:
   ```bash
   git clone http://localhost:3300/git/owner-project.git /tmp/owner && cd /tmp/owner
   mkdir -p tags/Ghost && echo '{}' > tags/Ghost/Phantom.json
   git add -A && git commit -m "test: provider the gateway does not have" && git push
   ```
2. Pull that into the gateway, then trigger an export (commit from `TestOwner`).
3. Expect a refusal naming `Ghost`, and — the point of the test — **`tags/` otherwise intact**:
   ```
   Refusing to publish tag export: the export contains no tags for provider(s) Ghost ...
   ```
4. Confirm no `tags.tmp/` or `tags.bak/` is left behind in the project directory:
   ```bash
   docker compose exec gateway ls /usr/local/bin/ignition/data/projects/TestOwner
   ```

### A + B — a diverged pull is refused and imports nothing

*Covers: `GitPullPolicy.applyTo`, `describeFailure`, both pull call sites.*

1. Force divergence — one commit on the remote, a different one on the gateway:
   ```bash
   git clone http://localhost:3300/git/owner-project.git /tmp/diverge && cd /tmp/diverge
   echo remote > remote.txt && git add -A && git commit -m "test: remote side" && git push
   ```
   Then, in the Designer for `TestOwner`, make a change and commit it (do not push).
2. **Set `pull.rebase=true` on the gateway repo** — this is the config the production gateway
   had, and the whole point of defect A:
   ```bash
   docker compose exec gateway git -C /usr/local/bin/ignition/data/projects/TestOwner \
       config pull.rebase true
   ```
3. Record the gateway's HEAD, then Pull from the Designer:
   ```bash
   docker compose exec gateway git -C /usr/local/bin/ignition/data/projects/TestOwner \
       rev-parse HEAD
   ```
4. Expect the pull to **fail** with `cannot fast-forward 'main' from its remote`, and:
   - HEAD **unchanged** — no rebase, no merge commit
   - repository state `SAFE` — not stranded mid-rebase
   - **nothing imported** into the gateway

   ```bash
   docker compose exec gateway git -C /usr/local/bin/ignition/data/projects/TestOwner status
   ```

**Restart variant (defect B, unattended path).** With the branch still diverged,
`docker compose restart gateway`. `GitCommissioningUtils` runs on every restart — confirm it logs
`Cannot sync project 'TestOwner'` and leaves the project on its existing revision rather than
importing.

### Watcher gate (#50)

*Covers: `TagChangeWatcher.isAutoExportEligible`.*

1. In the Designer for **`TestOther`**, edit a tag and wait past the 5s debounce.
2. Expect `Skipping automatic tag export for project 'TestOther': it is not the designated owner
   of gateway-scoped resources.` and no commit in `other-project.git`.
3. Repeat from `TestOwner` — the export runs.

### Designer UX (#50, untested by the suite)

`DesignerHook` has no automated coverage — Swing plus a live Designer context. This is the pass
that covers it:

1. Enable production mode on `TestOwner`, then save.
   Expect **one** safety checklist and **one** commit dialog — not two.
2. After the save completes, the pending-changes badge must **not** re-prompt for that same save.
3. Then edit a tag without saving. The ordinary "commit now?" prompt **must** still fire —
   suppression applies only to the production save that was just authorised.

## Notes

- The gateway takes 1–2 minutes to commission on first boot; watch
  `docker compose logs -f gateway | grep -i git`.
- `init-repos.sh` is idempotent, so restarting `git-server` will not wipe repos mid-test. Use
  `down -v` for a genuine reset.
