# TEC-3757: Auto-Import Tags on Gateway Startup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Opt-in per-project `git.yaml` flag `tags_importOnStartup: true` that re-imports git-tracked tags into gateway tag providers automatically after a gateway restart — zero Designer interaction.

**Architecture:** A new YAML key flows through the existing reflection-based parser (`YAML_KEY_TO_FIELD` whitelist → `ProjectConfig` field → `GitCommissioningConfig`). A new `StartupTagImporter` runs on one daemon background thread spawned from `GatewayHook.startup()`: it polls tag-provider readiness (5s interval, 2-minute cap), then calls the existing `GitTagManager.importTagManager(project, null)` per flagged project with per-project error isolation. The polling/import loop takes its collaborators (readiness check, importer, sleeper) as injected functions so it is fully unit-testable without an Ignition runtime.

**Tech Stack:** Java 17, Ignition 8.3 Module SDK, SnakeYAML (existing parser), Lombok (`@Getter`/`@Setter` on config classes), JUnit 4.13.2, Maven.

**Linear ticket:** [TEC-3757](https://linear.app/whiskey-house-eandt/issue/TEC-3757)

**Key codebase facts (verified):**
- `GitCommissioningUtils.YAML_KEY_TO_FIELD` (GitCommissioningUtils.java:47-68) is the single source of truth for git.yaml keys; the parser throws on unknown keys (`yamlKeyToFieldName`, line 401-409). Any new key MUST be added there AND as a real field on `ProjectConfig`, or parsing **fails loudly** for yaml files using it.
- `ProjectConfig` uses Lombok `@Getter @Setter` on snake_case fields (`Boolean commissioning_importTags` → `getCommissioning_importTags()`).
- `GitCommissioningConfig.loadFromProjectConfig` (lines 72-92) maps `Boolean` → primitive `boolean` via `Boolean.TRUE.equals(...)`.
- `GitTagManager.importTagManager(projectName, null)` already: warns about gateway-wide overwrite, skips gracefully when no `tags/` dir, and falls back to the repo's `tag_config` collision policy on null.
- `GatewayHook.startup()` currently only calls `GitCommissioningUtils.loadConfiguration()` (GatewayHook.java:111-115). `GatewayHook.context` is a public static set in `setup()`.
- Distinction from existing behavior: `commissioning_importTags` imports tags as part of the full commissioning clone/sync (which mutates the working tree: stash/fetch/pull). `tags_importOnStartup` imports ONLY tags, from whatever is on disk, with no git operations — and waits for provider readiness.
- Multi-project caution (TEC-3647): multiple projects importing into a shared provider is last-writer-wins; docs must say only the authoritative project should enable this flag.

---

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `git-gateway/src/main/java/com/axone_io/ignition/git/commissioning/ProjectConfig.java` | Modify | New `Boolean tags_importOnStartup` field |
| `git-gateway/src/main/java/com/axone_io/ignition/git/commissioning/GitCommissioningConfig.java` | Modify | New `boolean importTagsOnStartup` + mapping |
| `git-gateway/src/main/java/com/axone_io/ignition/git/commissioning/utils/GitCommissioningUtils.java` | Modify | Whitelist entry; `projectsWithTagImportOnStartup` filter; `startTagImportOnStartup()` entry point |
| `git-gateway/src/main/java/com/axone_io/ignition/git/managers/StartupTagImporter.java` | Create | Daemon thread + injectable poll/import loop |
| `git-gateway/src/main/java/com/axone_io/ignition/git/GatewayHook.java` | Modify | One call in `startup()` |
| `git-gateway/src/test/java/com/axone_io/ignition/git/commissioning/GitCommissioningUtilsTest.java` | Modify | New key-mapping + filter tests |
| `git-gateway/src/test/java/com/axone_io/ignition/git/commissioning/GitCommissioningConfigTest.java` | Create | Boolean→boolean mapping tests |
| `git-gateway/src/test/java/com/axone_io/ignition/git/managers/StartupTagImporterTest.java` | Create | Poll/import loop behavior tests |
| `README.md` | Modify | Document the new key |
| `CLAUDE.md` | Modify | Key Classes row + git.yaml example line |

---

### Task 1: YAML key + config plumbing (TDD)

**Files:**
- Modify: `git-gateway/src/main/java/com/axone_io/ignition/git/commissioning/ProjectConfig.java`
- Modify: `git-gateway/src/main/java/com/axone_io/ignition/git/commissioning/GitCommissioningConfig.java`
- Modify: `git-gateway/src/main/java/com/axone_io/ignition/git/commissioning/utils/GitCommissioningUtils.java` (whitelist only)
- Test: `git-gateway/src/test/java/com/axone_io/ignition/git/commissioning/GitCommissioningUtilsTest.java`
- Test (create): `git-gateway/src/test/java/com/axone_io/ignition/git/commissioning/GitCommissioningConfigTest.java`

- [ ] **Step 1: Write the failing tests**

In `GitCommissioningUtilsTest.java`, add after the `enforceBranchKeyMapsToActualField` test:

```java
    @Test
    public void tagsImportOnStartupKeyMapsToActualBooleanField() throws NoSuchFieldException {
        String fieldName = GitCommissioningUtils.yamlKeyToFieldName("tags_importOnStartup");
        Field field = ProjectConfig.class.getDeclaredField(fieldName);
        assertEquals(Boolean.class, field.getType());
    }
```

Create `git-gateway/src/test/java/com/axone_io/ignition/git/commissioning/GitCommissioningConfigTest.java`:

```java
package com.axone_io.ignition.git.commissioning;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Locks the ProjectConfig -> GitCommissioningConfig mapping for the
 * tags_importOnStartup flag (TEC-3757). Null and false must both disable
 * the feature; only an explicit true enables it.
 */
public class GitCommissioningConfigTest {

    private static GitCommissioningConfig loaded(Boolean flag) {
        ProjectConfig pc = new ProjectConfig();
        pc.setTags_importOnStartup(flag);
        GitCommissioningConfig config = new GitCommissioningConfig();
        config.loadFromProjectConfig(pc);
        return config;
    }

    @Test
    public void importTagsOnStartup_defaultsToFalse() {
        assertFalse(new GitCommissioningConfig().isImportTagsOnStartup());
    }

    @Test
    public void importTagsOnStartup_nullMeansDisabled() {
        assertFalse(loaded(null).isImportTagsOnStartup());
    }

    @Test
    public void importTagsOnStartup_falseMeansDisabled() {
        assertFalse(loaded(Boolean.FALSE).isImportTagsOnStartup());
    }

    @Test
    public void importTagsOnStartup_trueEnables() {
        assertTrue(loaded(Boolean.TRUE).isImportTagsOnStartup());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q test -pl git-gateway`
Expected: COMPILATION ERROR — `setTags_importOnStartup` / `isImportTagsOnStartup` do not exist yet, and `tagsImportOnStartupKeyMapsToActualBooleanField` would throw `IllegalArgumentException` (unknown key). That is the expected red state.

- [ ] **Step 3: Implement the plumbing**

(a) `ProjectConfig.java` — after the `production_tagPattern` field (line 59-61), add:

```java
    @Getter
    @Setter
    private Boolean tags_importOnStartup;
```

(b) `GitCommissioningConfig.java` — after the `productionTagPattern` field (lines 68-70), add:

```java
    @Getter
    @Setter
    private boolean importTagsOnStartup = false;
```

And in `loadFromProjectConfig`, after the `this.productionTagPattern = ...` line (line 91), add:

```java
        this.importTagsOnStartup = Boolean.TRUE.equals(projectConfig.getTags_importOnStartup());
```

(c) `GitCommissioningUtils.java` — in the `YAML_KEY_TO_FIELD` static block, after `m.put("production_tagPattern", "production_tagPattern");` (line 65), add:

```java
        m.put("tags_importOnStartup", "tags_importOnStartup");
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q test -pl git-gateway`
Expected: BUILD SUCCESS. The pre-existing `everyMappedYamlKeyResolvesToARealProjectConfigField` test now also covers the new whitelist entry automatically.

- [ ] **Step 5: Commit**

```bash
git add git-gateway/src/main/java/com/axone_io/ignition/git/commissioning/ProjectConfig.java \
        git-gateway/src/main/java/com/axone_io/ignition/git/commissioning/GitCommissioningConfig.java \
        git-gateway/src/main/java/com/axone_io/ignition/git/commissioning/utils/GitCommissioningUtils.java \
        git-gateway/src/test/java/com/axone_io/ignition/git/commissioning/GitCommissioningUtilsTest.java \
        git-gateway/src/test/java/com/axone_io/ignition/git/commissioning/GitCommissioningConfigTest.java
git commit -m "feat(commissioning): add tags_importOnStartup git.yaml key (TEC-3757)"
```

---

### Task 2: Project-filter helper (TDD)

**Files:**
- Modify: `git-gateway/src/main/java/com/axone_io/ignition/git/commissioning/utils/GitCommissioningUtils.java`
- Test: `git-gateway/src/test/java/com/axone_io/ignition/git/commissioning/GitCommissioningUtilsTest.java`

- [ ] **Step 1: Write the failing test**

Add to `GitCommissioningUtilsTest.java` (also add imports `java.util.List`, `com.axone_io.ignition.git.commissioning.ProjectConfigs` is same package — only `List` import needed):

```java
    @Test
    public void projectsWithTagImportOnStartup_filtersCorrectly() {
        ProjectConfigs configs = new ProjectConfigs();

        ProjectConfig enabled = new ProjectConfig();
        enabled.setIgnition_projectName("ProjA");
        enabled.setTags_importOnStartup(Boolean.TRUE);
        configs.addProject(enabled);

        ProjectConfig disabled = new ProjectConfig();
        disabled.setIgnition_projectName("ProjB");
        disabled.setTags_importOnStartup(Boolean.FALSE);
        configs.addProject(disabled);

        ProjectConfig unset = new ProjectConfig();
        unset.setIgnition_projectName("ProjC");
        configs.addProject(unset);

        ProjectConfig noName = new ProjectConfig();
        noName.setTags_importOnStartup(Boolean.TRUE);
        configs.addProject(noName);

        List<String> result = GitCommissioningUtils.projectsWithTagImportOnStartup(configs);
        assertEquals(List.of("ProjA"), result);
    }

    @Test
    public void projectsWithTagImportOnStartup_emptyAndNullSafe() {
        assertEquals(List.of(), GitCommissioningUtils.projectsWithTagImportOnStartup(new ProjectConfigs()));
        assertEquals(List.of(), GitCommissioningUtils.projectsWithTagImportOnStartup(null));
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q test -pl git-gateway`
Expected: COMPILATION ERROR — `projectsWithTagImportOnStartup` does not exist.

- [ ] **Step 3: Implement the filter**

In `GitCommissioningUtils.java`, after `yamlKeyToFieldName` (line ~409), add:

```java
    /**
     * Returns the names of projects whose git.yaml entry sets tags_importOnStartup: true.
     * Pure function so the filtering is unit-testable without a gateway.
     */
    public static List<String> projectsWithTagImportOnStartup(ProjectConfigs projectConfigs) {
        List<String> names = new ArrayList<>();
        if (projectConfigs == null || projectConfigs.getProjects() == null) {
            return names;
        }
        for (ProjectConfig pc : projectConfigs.getProjects()) {
            if (Boolean.TRUE.equals(pc.getTags_importOnStartup())
                    && pc.getIgnition_projectName() != null
                    && !pc.getIgnition_projectName().trim().isEmpty()) {
                names.add(pc.getIgnition_projectName());
            }
        }
        return names;
    }
```

(`List`, `ArrayList`, `ProjectConfig`, `ProjectConfigs` are already imported in this file.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q test -pl git-gateway`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add git-gateway/src/main/java/com/axone_io/ignition/git/commissioning/utils/GitCommissioningUtils.java \
        git-gateway/src/test/java/com/axone_io/ignition/git/commissioning/GitCommissioningUtilsTest.java
git commit -m "feat(commissioning): add projectsWithTagImportOnStartup filter (TEC-3757)"
```

---

### Task 3: StartupTagImporter (TDD)

**Files:**
- Test (create): `git-gateway/src/test/java/com/axone_io/ignition/git/managers/StartupTagImporterTest.java`
- Create: `git-gateway/src/main/java/com/axone_io/ignition/git/managers/StartupTagImporter.java`

- [ ] **Step 1: Write the failing test**

Create `StartupTagImporterTest.java`:

```java
package com.axone_io.ignition.git.managers;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests the injectable poll/import loop. The readiness check, the importer,
 * and the sleeper are all passed in, so no Ignition runtime and no real
 * sleeping is needed (TEC-3757).
 */
public class StartupTagImporterTest {

    @Test
    public void importsAllProjectsInOrder_whenProvidersReadyImmediately() {
        List<String> imported = new ArrayList<>();
        StartupTagImporter.runImport(
                Arrays.asList("A", "B"),
                () -> true,
                imported::add,
                millis -> { throw new AssertionError("must not sleep when ready"); },
                3);
        assertEquals(Arrays.asList("A", "B"), imported);
    }

    @Test
    public void waitsForReadiness_thenImports() {
        List<String> imported = new ArrayList<>();
        List<Long> sleeps = new ArrayList<>();
        AtomicInteger polls = new AtomicInteger();
        StartupTagImporter.runImport(
                Arrays.asList("A"),
                () -> polls.incrementAndGet() >= 3, // ready on 3rd poll
                imported::add,
                sleeps::add,
                10);
        assertEquals(Arrays.asList("A"), imported);
        assertEquals(2, sleeps.size()); // slept twice before ready
        assertEquals(Long.valueOf(StartupTagImporter.POLL_INTERVAL_MS), sleeps.get(0));
    }

    @Test
    public void givesUpAfterMaxPolls_neverImports() {
        List<String> imported = new ArrayList<>();
        List<Long> sleeps = new ArrayList<>();
        StartupTagImporter.runImport(
                Arrays.asList("A", "B"),
                () -> false,
                imported::add,
                sleeps::add,
                4);
        assertTrue(imported.isEmpty());
        assertEquals(4, sleeps.size()); // polled max times, slept between each
    }

    @Test
    public void oneFailingImportDoesNotStopOthers() {
        List<String> imported = new ArrayList<>();
        StartupTagImporter.runImport(
                Arrays.asList("A", "BOOM", "C"),
                () -> true,
                p -> {
                    if (p.equals("BOOM")) {
                        throw new RuntimeException("simulated import failure");
                    }
                    imported.add(p);
                },
                millis -> { },
                3);
        assertEquals(Arrays.asList("A", "C"), imported);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q test -pl git-gateway`
Expected: COMPILATION ERROR — `StartupTagImporter` does not exist.

- [ ] **Step 3: Implement**

Create `StartupTagImporter.java`:

```java
package com.axone_io.ignition.git.managers;

import com.axone_io.ignition.git.GatewayHook;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * Imports git-tracked tags for flagged projects once tag providers are ready,
 * on a single daemon background thread spawned from GatewayHook.startup().
 *
 * <p>Never blocks or fails gateway startup: readiness is polled with a hard
 * timeout, and each project import is individually try/caught (TEC-3757).</p>
 *
 * <p>Distinct from commissioning_importTags: this imports ONLY tags, from
 * whatever is on disk, with no git operations (no stash/fetch/pull).</p>
 */
public class StartupTagImporter {
    private final static LoggerEx logger = LoggerEx.newBuilder().build(StartupTagImporter.class);

    static final long POLL_INTERVAL_MS = 5_000L;
    static final long TIMEOUT_MS = 120_000L;

    /** Spawns the daemon import thread. Returns the thread (for tests/diagnostics). */
    public static Thread start(List<String> projectNames) {
        Thread t = new Thread(
                () -> runImport(
                        projectNames,
                        StartupTagImporter::providersReady,
                        project -> GitTagManager.importTagManager(project, null),
                        StartupTagImporter::sleep,
                        TIMEOUT_MS / POLL_INTERVAL_MS),
                "git-startup-tag-import");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /**
     * Poll/import loop with injected collaborators so it is unit-testable.
     * Polls {@code providersReady} up to {@code maxPolls} times (sleeping
     * POLL_INTERVAL_MS between polls), then imports each project, isolating
     * per-project failures.
     */
    static void runImport(List<String> projectNames,
                          BooleanSupplier providersReady,
                          Consumer<String> importer,
                          LongConsumer sleeper,
                          long maxPolls) {
        long polls = 0;
        while (!providersReady.getAsBoolean()) {
            polls++;
            if (polls > maxPolls) {
                logger.warn("Tag providers not ready after " + (maxPolls * POLL_INTERVAL_MS / 1000)
                        + "s; skipping startup tag import for projects: " + projectNames);
                return;
            }
            sleeper.accept(POLL_INTERVAL_MS);
        }
        for (String project : projectNames) {
            try {
                logger.info("Startup tag import (tags_importOnStartup) for project '" + project + "'.");
                importer.accept(project);
            } catch (Exception e) {
                logger.error("Startup tag import failed for project '" + project + "', continuing.", e);
            }
        }
    }

    static boolean providersReady() {
        try {
            GatewayContext ctx = GatewayHook.context;
            return ctx != null && !ctx.getTagManager().getTagProviders().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

Note on the give-up test arithmetic: with `maxPolls = 4` and a never-ready supplier, the loop sleeps after polls 1-4 and returns when `polls` reaches 5 — wait, re-check against the implementation: poll fails → `polls++` (1..4) → each ≤ maxPolls so sleeper runs → 5th failure → `polls=5 > 4` → return. That yields **4 sleeps**, matching the test's `assertEquals(4, sleeps.size())`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q test -pl git-gateway`
Expected: BUILD SUCCESS, all 4 new tests green.

- [ ] **Step 5: Commit**

```bash
git add git-gateway/src/main/java/com/axone_io/ignition/git/managers/StartupTagImporter.java \
        git-gateway/src/test/java/com/axone_io/ignition/git/managers/StartupTagImporterTest.java
git commit -m "feat(tags): add StartupTagImporter with provider-readiness polling (TEC-3757)"
```

---

### Task 4: Wire into gateway startup

**Files:**
- Modify: `git-gateway/src/main/java/com/axone_io/ignition/git/commissioning/utils/GitCommissioningUtils.java`
- Modify: `git-gateway/src/main/java/com/axone_io/ignition/git/GatewayHook.java:111-115`

- [ ] **Step 1: Add the entry point to `GitCommissioningUtils`**

After `projectsWithTagImportOnStartup` (added in Task 2), add:

```java
    /**
     * Entry point called from GatewayHook.startup(). Parses git.yaml, finds
     * projects flagged tags_importOnStartup: true, and spawns the background
     * importer. Never throws — gateway startup must not fail because of this.
     */
    public static void startTagImportOnStartup() {
        try {
            Path yamlConfigPath = getDataFolderPath().resolve("git.yaml");
            if (!yamlConfigPath.toFile().isFile()) {
                return;
            }
            List<String> projects = projectsWithTagImportOnStartup(parseYaml(yamlConfigPath));
            if (projects.isEmpty()) {
                return;
            }
            logger.info("Scheduling startup tag import for projects: " + projects);
            StartupTagImporter.start(projects);
        } catch (Exception e) {
            logger.error("Failed to schedule startup tag import; continuing gateway startup.", e);
        }
    }
```

Add the import at the top of the file with the other manager imports (it sits alphabetically after `GitTagManager`):

```java
import com.axone_io.ignition.git.managers.StartupTagImporter;
```

- [ ] **Step 2: Call it from `GatewayHook.startup()`**

In `GatewayHook.java`, change the `startup` method (currently lines 111-115):

```java
    @Override
    public void startup(LicenseState licenseState) {
        GitCommissioningUtils.loadConfiguration();
        GitCommissioningUtils.startTagImportOnStartup();

        logger.info("startup()");
    }
```

- [ ] **Step 3: Verify compile + full test suite**

Run: `mvn -q compile`
Expected: BUILD SUCCESS.

Run: `mvn -q test`
Expected: BUILD SUCCESS, no regressions (72 baseline tests + 7 new from Tasks 1-2 + 4 from Task 3 = 83 total).

- [ ] **Step 4: Commit**

```bash
git add git-gateway/src/main/java/com/axone_io/ignition/git/commissioning/utils/GitCommissioningUtils.java \
        git-gateway/src/main/java/com/axone_io/ignition/git/GatewayHook.java
git commit -m "feat(tags): wire startup tag import into GatewayHook.startup (TEC-3757)"
```

---

### Task 5: Documentation + full module build

**Files:**
- Modify: `README.md` (after the YAML example block, ~line 180)
- Modify: `CLAUDE.md` (Key Classes table + Production Mode yaml example)

- [ ] **Step 1: Document the key in README.md**

In `README.md`, the "YAML Automated Commissioning" section ends with this paragraph (after the yaml example block, ~line 180):

```markdown
This supports multi-project import with project inheritance. See the [Docker example](docker/) in this repo for a working setup.
```

Insert BETWEEN the closing ``` of the yaml example and that paragraph:

````markdown
### Automatic tag re-import on restart

Ignition stores tags in the gateway's internal database, so a gateway restart
restores tags from gateway state — not from the git working tree. To re-sync
git-tracked tags into the live providers automatically after every restart,
set the per-project flag:

```yaml
  tags_importOnStartup: true
```

When enabled, the module waits for tag providers to come up (polling every 5s,
giving up after 2 minutes with a logged warning) and then imports that
project's `tags/` directory from disk — no pull, no git operations, no
Designer interaction. Failures are logged per project and never block gateway
startup.

> **Warning:** Tag providers are gateway-wide and imports are last-writer-wins.
> If multiple projects track the same provider, enable `tags_importOnStartup`
> only on the authoritative project (same guidance as `commissioning_importTags`).

Unlike `commissioning_importTags` (which imports tags as part of the full
commissioning clone/sync and mutates the working tree), `tags_importOnStartup`
imports **only tags**, from whatever is currently on disk.
````

- [ ] **Step 2: Update CLAUDE.md**

(a) In the Key Classes table, after the `HotfixManager` row, add:

```markdown
| `StartupTagImporter` | git-gateway | Background tag re-import on gateway startup (`tags_importOnStartup`) |
```

(b) In the "Production Mode" section's `git.yaml` example, no change needed (the flag is not production-specific). Instead, verify CLAUDE.md has no other git.yaml key list to update — if the Production Mode yaml example is the only one, leave it alone.

- [ ] **Step 3: Full build verification**

Run: `mvn clean package -DskipTests`
Expected: BUILD SUCCESS; `ls git-build/target/*.modl` shows `Git-unsigned.modl`.

Run: `mvn -q test`
Expected: BUILD SUCCESS, all tests green.

- [ ] **Step 4: Commit**

```bash
git add README.md CLAUDE.md
git commit -m "docs: document tags_importOnStartup flag (TEC-3757)"
```

---

### Task 6: Manual verification (hot deploy — requires Docker gateway)

Cannot be automated in this repo; perform against the local `whk-services` stack and record results in the PR.

- [ ] **Step 1:** Set `tags_importOnStartup: true` for ONE project in the stack's `git.yaml`; hot-deploy the module (build, `docker cp`, `docker compose restart ignition`).
- [ ] **Step 2:** Watch logs: `docker logs -f whk-services-ignition-1 2>&1 | grep -iE "StartupTagImporter|GitTagManager"` — expect "Scheduling startup tag import", then per-project import logs once providers are up.
- [ ] **Step 3:** Verify tags resolve in the provider WITHOUT opening Designer.
- [ ] **Step 4:** Confirm projects without the flag are untouched, and gateway startup time is not materially affected.
- [ ] **Step 5:** Negative test: remove the flag, restart, confirm no import runs.

---

## Self-Review Notes

- **Spec coverage vs TEC-3757:** yaml key + whitelist + ProjectConfig field ✔ (Task 1); null→config-fallback collision policy (passes `null` to `importTagManager`) ✔ (Task 3); single daemon thread, 5s poll, 2-min cap, clear give-up log ✔ (Task 3); per-project try/catch, never blocks startup ✔ (Tasks 3-4); missing tags/ dir handled by existing `importTagManager` ✔; docs incl. shared-provider last-writer-wins caution ✔ (Task 5); unit tests for YAML parsing round-trip ✔ (Task 1) plus loop behavior tests beyond ticket minimum (Task 3).
- **Type consistency:** `projectsWithTagImportOnStartup(ProjectConfigs) -> List<String>` used identically in Tasks 2 and 4; `runImport(List<String>, BooleanSupplier, Consumer<String>, LongConsumer, long)` signature matches between test (Task 3 Step 1) and impl (Step 3); Lombok accessor names (`setTags_importOnStartup`, `getTags_importOnStartup`, `isImportTagsOnStartup`) consistent across tasks.
- **Judgment calls:** readiness check is "any tag provider present" (`getTagProviders()` non-empty) — a pragmatic proxy; per-provider readiness would require knowing which providers each project's tags dir targets before import (YAGNI). `parseYaml` is `protected static` and same-package callers are fine (`startTagImportOnStartup` lives in the same class). The give-up sleep-count arithmetic is documented inline in Task 3 Step 3.
- **Merge-conflict awareness:** this branch and PR #34 (TEC-3756) both touch `GatewayHook.java` in different methods (`startup()` here, `initializeScriptManager()` there) and both add rows near each other in CLAUDE.md — whichever merges second will have a small, mechanical conflict.
