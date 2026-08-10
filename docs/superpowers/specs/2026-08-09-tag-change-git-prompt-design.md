# Prompting the Git Workflow on Tag, UDT, and Project Changes

**Date:** 2026-08-09
**Status:** Approved design, not yet implemented

## Problem

Tag and UDT edits never reach Git unless someone remembers to click **Export** and then
**Commit**.

The module's only automatic entry point into the Git workflow is
`DesignerHook.notifyProjectSaveDone()`, which the Designer fires on *project* saves —
Perspective views, scripts, named queries. Tags are not project resources. Editing a tag
in the Tag Browser applies straight to the gateway's tag provider, so no project save
occurs and the hook never runs. Tags only reach the working tree when a user manually
triggers `exportConfig` (`GitBaseAction.java:245` → `GitTagManager.exportTag`).

The result is silent drift: a production gateway's tags diverge from the repository and
nothing signals it.

A second gap: outside production mode, `notifyProjectSaveDone` does nothing at all. Even
project saves produce no Git prompt on a non-production gateway.

## Goal

Any change that dirties the repository — tag, UDT, or project resource — leads the user
into the existing Git workflow, in both production and non-production mode, without
interrupting bulk editing.

## Key insight

In Ignition 8.3 tags became config resources: `TagResourceTypes.TAG_DEFINITION` and
`TagResourceTypes.TYPE_DEFINITION`. A gateway-side `ResourceListener` filtered to those
two types fires on every tag and UDT create, modify, and delete, from *any* source —
Designer tag editor, `system.tag.configure`, or the gateway web UI. That is strictly
better coverage than a Designer-side hook could achieve.

Because the module already exports tags to files and already computes Git status, the
listener's real job is narrow: **write tags to the working tree when they change.** Once
the JSON is on disk, the existing `getUncommitedChanges` machinery reports the drift for
free, and tag changes and project changes collapse into one signal — "the working tree
does not match HEAD".

## Decisions

| Question | Decision |
|---|---|
| Trigger timing | Debounced: 5 s of quiet after the last change |
| Non-production prompt | Lightweight "commit now?" dialog |
| Production prompt | Existing `ProductionModePopup` checklist, retitled |
| Dismissed prompt | Leaves a pulsing status-bar badge, in both modes |
| Badge cleared by | A successful commit only |
| Scope | Tag, UDT, **and** project changes |
| Whose changes prompt | Any uncommitted drift, in every open Designer session |
| Module's own imports | Suppressed explicitly |

## Architecture

### Gateway (`git-gateway`)

**`TagChangeWatcher`** (new, `managers/`), registered in `GatewayHook.startup()`:

```java
context.getConfigurationManager()
       .getConfigCollection()
       .addResourceListener(tagChangeWatcher);   // filter: TAG_DEFINITION, TYPE_DEFINITION
```

The `resourcesCreated` / `resourcesModified` / `resourcesDeleted` callbacks do almost
nothing — they reset a 5 s coalescing timer on a single-threaded
`ScheduledExecutorService`. Export runs on that executor, **never** on the listener
callback thread; blocking the resource system would stall the gateway.

When the timer fires the watcher runs `GitTagManager.exportTag(...)` for each eligible
git-tracked project and increments a monotonic `revision` counter. A failed export is
logged and does **not** increment `revision`, so a broken export cannot manufacture
prompts.

**Eligibility (multi-project safety).** `GitBaseAction.confirmExportMultiProject` exists
because on a multi-project gateway, exporting tags writes *every* provider's tags into
whichever repository the user happens to be in. Automatic export has no user present to
confirm. Therefore auto-export runs only for projects whose `.tag-config.json` declares
`includedProviders`. On a multi-project gateway a project without that setting is skipped
with a logged warning. The manual Export button is unchanged. Single-project gateways are
unaffected.

**`ImportSuppression`** — a gateway-scoped guard (an `AtomicInteger` depth).
`GitTagManager.importTagManager`, `StartupTagImporter`, and `GitCommissioningUtils` wrap
their imports in it. The watcher no-ops while depth > 0 and discards pending events on
exit. Without this, a pull would import tags, fire the listener, export, and prompt the
user to commit the changes it had just pulled.

### Common (`git-common`)

**`RepoDirtyState`** DTO — `Serializable`, no-arg constructor, primitives only, matching
the module's existing DTO rules:

- `long revision`
- `boolean dirty`
- `int projectChangeCount`
- `int tagChangeCount`
- `boolean productionMode`

**One new RPC method**, no overloads, threaded through all four required places
(`GitScriptInterface`, `AbstractScriptModule`, `GatewayScriptModule`,
`ClientScriptModule`):

```java
RepoDirtyState getRepoDirtyState(String projectName, String userName);
```

The gateway implementation reuses the existing `getUncommitedChanges` internals,
classifying each changed path as tag-side or project-side by whether it sits under the
tags directory.

### Designer (`git-designer`)

**`DirtyStatePolicy`** — a plain, Swing-free class. Given the previous and current
`RepoDirtyState` plus the session's dismissed revision, it returns `PROMPT`,
`BADGE_ONLY`, or `NOTHING`. Isolated so it can be unit-tested without a Designer.

**`DesignerHook`** — a new 5 s `Timer` polls `getRepoDirtyState` on a `SwingWorker` and
feeds `DirtyStatePolicy`. `notifyProjectSaveDone` additionally kicks an immediate check
rather than waiting for the next tick. If `setupLocalRepo` failed at startup, the poller
disables itself.

**`PendingChangesDialog`** (new) — the non-production prompt: a one-line summary
("4 tag changes, 2 project changes") with **Commit** and **Not now**. Standard Swing
layouts only — IntelliJ's forms library causes classloader conflicts in the Designer.

**`GitWorkflowPrompter`** — routes by mode:

- Production → existing `ProductionModePopup`, titled *"Uncommitted Changes on Production
  Gateway"*; `onProceed` → `GitActionManager.showCommitWithHotfixDetection`.
- Non-production → `PendingChangesDialog`; **Commit** → the same
  `showCommitWithHotfixDetection`.

**Status bar** — a `pulsingBadge` `JLabel` beside the existing `productionBadge`, its
background animated by a Swing `Timer`. Shown once a prompt has been dismissed while
changes remain, in **both** production and non-production mode; hidden when `dirty` goes
false, i.e. after a successful commit. Clicking it reopens the prompt appropriate to the
current mode.

In production the badge sits alongside the permanent PRODUCTION badge, so a production
gateway with uncommitted drift shows both. That is intentional: production is where a
lingering reminder matters most.

## Flow

Non-production:

1. Engineer edits a UDT; Ignition pushes tag resource changes into the config collection.
2. `TagChangeWatcher` receives `resourcesModified` and resets its 5 s timer. Further edits
   keep resetting it.
3. After 5 s of quiet the executor runs `exportTag` and bumps `revision`.
4. The Designer's poll sees a new revision with `dirty=true` and shows
   `PendingChangesDialog`.
5. **Commit** opens the normal commit dialog. **Not now** leaves the pulsing badge and
   suppresses further popups until `revision` changes again.

Production mode is identical except that step 4 shows `ProductionModePopup`, and the
commit auto-pushes or enters the hotfix pipeline on the production branch — all existing
behavior.

## Edge cases

- **Prompt already open** — the poller never stacks dialogs; it skips while one is
  showing.
- **Dismissed** — the session records the dismissed revision and does not re-prompt until
  `revision` increases. The badge carries the signal meanwhile.
- **Multiple Designer sessions** — each polls and prompts independently. Each tracks its
  own dismissed revision, so one engineer dismissing does not silence another.
- **Designer restart** — nothing to persist. Badge state derives from Git status, so it
  reappears on its own.
- **Long edits** — the timer resets on every event, so a rename touching dozens of
  resources over more than 5 s still produces exactly one export and one prompt.

## Error handling

Poller RPC failures fail **quiet**: logged at debug, last known state retained, no prompt.
This deliberately differs from the save-time production warning at `DesignerHook.java:320`,
which fails **conservative** by assuming production and warning. A flapping connection
producing repeated popups from a background timer would be worse than a missed prompt, and
the save-time path — the moment a production gateway actually changes — remains guarded.

Gateway export failures are logged and do not bump `revision`.

## Testing

- **`TagChangeWatcherTest`** (gateway) — burst coalescing (20 events produce 1 export),
  suppression during import, `revision` increments only on successful export. Follows the
  existing `GitTagManagerTest` style.
- **`DirtyStatePolicyTest`** (designer) — the full prompt / badge / nothing transition
  table. No Swing required; this is why the policy is a separate class.
- **Manual** — the documented Docker hot-deploy loop, since the Swing prompts and badge
  animation cannot be unit tested.

## Documentation

- New rows in the CLAUDE.md key-classes table for `TagChangeWatcher`,
  `GitWorkflowPrompter`, `PendingChangesDialog`, and `DirtyStatePolicy`.
- `RepoDirtyState` added to the DTO list.
- A section in `docs/production-mode.md` covering tag-change prompting.
