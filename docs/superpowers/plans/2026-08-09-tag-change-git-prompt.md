# Tag / UDT / Project Change Git Prompt — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make any change that dirties the repository — tag, UDT, or project resource — lead the user into the existing Git commit workflow, in both production and non-production mode, without interrupting bulk editing.

**Architecture:** A gateway-side `ResourceListener` filtered to Ignition 8.3's tag config resource types debounces tag/UDT changes for 5 seconds, then exports tags to the working tree. Once the JSON is on disk, tag drift and project drift become one signal — "the working tree does not match HEAD" — which the Designer polls every 5 seconds through a new `getRepoDirtyState` RPC. A prompt appears (production checklist or lightweight dialog); dismissing it leaves a pulsing status-bar badge until a commit lands.

**Tech Stack:** Java 17, Ignition SDK 8.3.1, JGit, Swing (Designer UI), JUnit 4, Maven multi-module.

## Global Constraints

- **Ignition 8.3.1+ / Java 17.** Module scopes: `git-common` = CDG, `git-client` = C, `git-designer` = CD, `git-gateway` = G.
- **No overloaded methods in `GitScriptInterface`.** RPC does not handle overloading. Every interface change must be applied in all four places: `GitScriptInterface`, `AbstractScriptModule`, `GatewayScriptModule`, `ClientScriptModule`.
- **All DTOs** must implement `Serializable`, have a no-arg constructor, and use only primitives or standard Java collections.
- **No IntelliJ forms library** (`GridLayoutManager`, `GridConstraints`) in Designer code — it causes classloader conflicts. Use `BorderLayout`, `BoxLayout`, `FlowLayout`, `GridBagLayout`.
- **Test infrastructure exists only in `git-common` and `git-gateway`** (JUnit 4.13.2). `git-designer` and `git-client` have no `src/test` and no JUnit dependency. Do not add test infrastructure to the Designer module — put testable logic in `git-common` instead.
- **Testing idiom:** follow `StartupTagImporter` / `StartupTagImporterTest` — extract a static core method taking injected collaborators (suppliers, consumers) so tests need no Ignition runtime. No Mockito available.
- **Debounce interval:** 5000 ms. **Designer poll interval:** 5000 ms.
- Build: `mvn clean package -DskipTests`. Tests: `mvn test`.

## Design Refinements Discovered While Reading Code

Two departures from the spec, both simplifications. They are already reflected in the tasks below.

1. **`revision` is a hash of the change set, not a watcher counter.** The spec had the watcher increment a counter. But project saves don't go through the watcher, so a counter would never change on a project save — meaning after one dismissal, subsequent project saves would never re-prompt. Instead `getRepoDirtyState` computes `revision` as a 64-bit hash over the sorted set of `"type:resource"` strings. Any new, changed, or reverted file changes the revision uniformly, whatever its origin. The watcher needs no counter at all.

2. **Import suppression wraps `GitTagManager.importTagManager` itself**, not its three call sites. Every import path — pull, `importResources`, `importTags`, `StartupTagImporter`, `GitCommissioningUtils` — funnels through that one static method, so one wrap covers them all.

3. **`DirtyStatePolicy` lives in `git-common`**, not `git-designer`, because `git-designer` has no test infrastructure and the policy is pure logic over a `git-common` DTO.

## File Structure

**Created:**

| File | Responsibility |
|---|---|
| `git-common/src/main/java/com/axone_io/ignition/git/dto/RepoDirtyState.java` | DTO: working-tree drift summary |
| `git-common/src/main/java/com/axone_io/ignition/git/DirtyStatePolicy.java` | Pure decision logic: prompt / badge / nothing |
| `git-common/src/test/java/com/axone_io/ignition/git/DirtyStatePolicyTest.java` | Tests for the above |
| `git-gateway/src/main/java/com/axone_io/ignition/git/managers/ImportSuppression.java` | Re-entrant guard disabling the watcher during module imports |
| `git-gateway/src/main/java/com/axone_io/ignition/git/managers/TagChangeWatcher.java` | `ResourceListener` + 5s debounce + eligibility + export |
| `git-gateway/src/test/java/com/axone_io/ignition/git/managers/ImportSuppressionTest.java` | Tests for the guard |
| `git-gateway/src/test/java/com/axone_io/ignition/git/managers/TagChangeWatcherTest.java` | Tests for coalescing, eligibility, export isolation |
| `git-designer/src/main/java/com/axone_io/ignition/git/PendingChangesDialog.java` | Non-production "commit now?" dialog |
| `git-designer/src/main/java/com/axone_io/ignition/git/managers/GitWorkflowPrompter.java` | Routes prompt by production mode |

**Modified:**

| File | Change |
|---|---|
| `git-common/.../GitScriptInterface.java` | Add `getRepoDirtyState` |
| `git-common/.../AbstractScriptModule.java` | Add wrapper + abstract impl |
| `git-gateway/.../GatewayScriptModule.java` | Implement `getRepoDirtyStateImpl` |
| `git-client/.../ClientScriptModule.java` | Add RPC delegation |
| `git-gateway/.../managers/GitTagManager.java` | Wrap `importTagManager` body in suppression |
| `git-gateway/.../GatewayHook.java` | Register/unregister the watcher |
| `git-designer/.../DesignerHook.java` | Dirty-state poller, pulsing badge, save kick |
| `CLAUDE.md`, `docs/production-mode.md` | Documentation |

---

### Task 1: `RepoDirtyState` DTO and `DirtyStatePolicy`

Pure logic with no Ignition dependencies, in `git-common` where JUnit already exists. Everything downstream depends on these types, so this task comes first.

**Files:**
- Create: `git-common/src/main/java/com/axone_io/ignition/git/dto/RepoDirtyState.java`
- Create: `git-common/src/main/java/com/axone_io/ignition/git/DirtyStatePolicy.java`
- Test: `git-common/src/test/java/com/axone_io/ignition/git/DirtyStatePolicyTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `RepoDirtyState` — no-arg ctor; `long getRevision()/setRevision(long)`, `boolean isDirty()/setDirty(boolean)`, `int getProjectChangeCount()/setProjectChangeCount(int)`, `int getTagChangeCount()/setTagChangeCount(int)`, `boolean isProductionMode()/setProductionMode(boolean)`, `String describeChanges()`.
  - `DirtyStatePolicy.Action` — enum `NOTHING`, `PROMPT`, `BADGE_ONLY`.
  - `static Action DirtyStatePolicy.decide(RepoDirtyState state, long dismissedRevision, boolean promptOpen)`
  - `static boolean DirtyStatePolicy.shouldShowBadge(RepoDirtyState state, long dismissedRevision)`

- [ ] **Step 1: Write the DTO**

`git-common/src/main/java/com/axone_io/ignition/git/dto/RepoDirtyState.java`:

```java
package com.axone_io.ignition.git.dto;

import java.io.Serializable;

/**
 * Summary of how far a project's working tree has drifted from HEAD.
 *
 * <p>{@code revision} is a stable hash of the current change set, not a counter. Any file
 * appearing, changing, or reverting produces a different revision, whatever caused it —
 * which is what lets the Designer distinguish "new changes since the user dismissed the
 * prompt" from "the same changes they already declined to commit".</p>
 */
public class RepoDirtyState implements Serializable {
    private static final long serialVersionUID = 1L;

    private long revision;
    private boolean dirty;
    private int projectChangeCount;
    private int tagChangeCount;
    private boolean productionMode;

    /** Default constructor required for serialization. */
    public RepoDirtyState() {
        this.revision = 0L;
        this.dirty = false;
        this.projectChangeCount = 0;
        this.tagChangeCount = 0;
        this.productionMode = false;
    }

    public long getRevision() {
        return revision;
    }

    public void setRevision(long revision) {
        this.revision = revision;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void setDirty(boolean dirty) {
        this.dirty = dirty;
    }

    public int getProjectChangeCount() {
        return projectChangeCount;
    }

    public void setProjectChangeCount(int projectChangeCount) {
        this.projectChangeCount = projectChangeCount;
    }

    public int getTagChangeCount() {
        return tagChangeCount;
    }

    public void setTagChangeCount(int tagChangeCount) {
        this.tagChangeCount = tagChangeCount;
    }

    public boolean isProductionMode() {
        return productionMode;
    }

    public void setProductionMode(boolean productionMode) {
        this.productionMode = productionMode;
    }

    /** Human-readable summary for dialogs, e.g. "4 tag changes, 2 project changes". */
    public String describeChanges() {
        StringBuilder sb = new StringBuilder();
        if (tagChangeCount > 0) {
            sb.append(tagChangeCount).append(tagChangeCount == 1 ? " tag change" : " tag changes");
        }
        if (projectChangeCount > 0) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(projectChangeCount)
              .append(projectChangeCount == 1 ? " project change" : " project changes");
        }
        return sb.length() == 0 ? "No changes" : sb.toString();
    }

    @Override
    public String toString() {
        return "RepoDirtyState{revision=" + revision +
                ", dirty=" + dirty +
                ", projectChangeCount=" + projectChangeCount +
                ", tagChangeCount=" + tagChangeCount +
                ", productionMode=" + productionMode + '}';
    }
}
```

- [ ] **Step 2: Write the failing tests**

`git-common/src/test/java/com/axone_io/ignition/git/DirtyStatePolicyTest.java`:

```java
package com.axone_io.ignition.git;

import com.axone_io.ignition.git.dto.RepoDirtyState;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DirtyStatePolicyTest {

    private static RepoDirtyState state(long revision, boolean dirty, int tags, int project) {
        RepoDirtyState s = new RepoDirtyState();
        s.setRevision(revision);
        s.setDirty(dirty);
        s.setTagChangeCount(tags);
        s.setProjectChangeCount(project);
        return s;
    }

    @Test
    public void nullState_doesNothing() {
        assertEquals(DirtyStatePolicy.Action.NOTHING,
                DirtyStatePolicy.decide(null, DirtyStatePolicy.NEVER_DISMISSED, false));
    }

    @Test
    public void cleanTree_doesNothing() {
        assertEquals(DirtyStatePolicy.Action.NOTHING,
                DirtyStatePolicy.decide(state(0L, false, 0, 0), DirtyStatePolicy.NEVER_DISMISSED, false));
    }

    @Test
    public void firstDirtyState_prompts() {
        assertEquals(DirtyStatePolicy.Action.PROMPT,
                DirtyStatePolicy.decide(state(77L, true, 3, 0), DirtyStatePolicy.NEVER_DISMISSED, false));
    }

    @Test
    public void alreadyDismissedSameRevision_badgeOnly() {
        assertEquals(DirtyStatePolicy.Action.BADGE_ONLY,
                DirtyStatePolicy.decide(state(77L, true, 3, 0), 77L, false));
    }

    @Test
    public void newRevisionAfterDismissal_promptsAgain() {
        assertEquals(DirtyStatePolicy.Action.PROMPT,
                DirtyStatePolicy.decide(state(99L, true, 4, 0), 77L, false));
    }

    @Test
    public void promptAlreadyOpen_neverStacksDialogs() {
        assertEquals(DirtyStatePolicy.Action.NOTHING,
                DirtyStatePolicy.decide(state(99L, true, 4, 0), 77L, true));
    }

    @Test
    public void badgeHidden_beforeAnyDismissal() {
        assertFalse(DirtyStatePolicy.shouldShowBadge(state(77L, true, 3, 0),
                DirtyStatePolicy.NEVER_DISMISSED));
    }

    @Test
    public void badgeShown_afterDismissalWhileStillDirty() {
        assertTrue(DirtyStatePolicy.shouldShowBadge(state(77L, true, 3, 0), 77L));
    }

    @Test
    public void badgeHidden_onceTreeIsClean() {
        assertFalse(DirtyStatePolicy.shouldShowBadge(state(0L, false, 0, 0), 77L));
    }

    @Test
    public void badgeHidden_whenStateUnknown() {
        assertFalse(DirtyStatePolicy.shouldShowBadge(null, 77L));
    }

    @Test
    public void describesTagAndProjectCountsTogether() {
        assertEquals("4 tag changes, 2 project changes", state(1L, true, 4, 2).describeChanges());
    }

    @Test
    public void describesSingularsCorrectly() {
        assertEquals("1 tag change, 1 project change", state(1L, true, 1, 1).describeChanges());
    }

    @Test
    public void describesProjectOnlyChanges() {
        assertEquals("3 project changes", state(1L, true, 0, 3).describeChanges());
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `mvn -q -pl git-common test`
Expected: FAIL — compilation error, `DirtyStatePolicy` does not exist.

- [ ] **Step 4: Write the policy**

`git-common/src/main/java/com/axone_io/ignition/git/DirtyStatePolicy.java`:

```java
package com.axone_io.ignition.git;

import com.axone_io.ignition.git.dto.RepoDirtyState;

/**
 * Decides what the Designer should do about working-tree drift. Pure logic, no Swing and
 * no Ignition types, so it can be unit-tested without a Designer — which is why it lives
 * in git-common rather than git-designer.
 */
public final class DirtyStatePolicy {

    /** Sentinel for "the user has not dismissed any prompt yet this session". */
    public static final long NEVER_DISMISSED = Long.MIN_VALUE;

    public enum Action {
        /** Leave the UI as it is. */
        NOTHING,
        /** Open the mode-appropriate prompt. */
        PROMPT,
        /** Changes remain but the user already declined them; show the badge only. */
        BADGE_ONLY
    }

    private DirtyStatePolicy() {
    }

    /**
     * @param state            latest known drift, or {@code null} if never successfully polled
     * @param dismissedRevision revision the user last dismissed, or {@link #NEVER_DISMISSED}
     * @param promptOpen       whether a prompt dialog is currently showing
     */
    public static Action decide(RepoDirtyState state, long dismissedRevision, boolean promptOpen) {
        if (state == null || !state.isDirty()) {
            return Action.NOTHING;
        }
        if (promptOpen) {
            return Action.NOTHING;
        }
        return dismissedRevision == NEVER_DISMISSED || state.getRevision() != dismissedRevision
                ? Action.PROMPT
                : Action.BADGE_ONLY;
    }

    /** The badge marks changes the user has seen and declined, and survives until committed. */
    public static boolean shouldShowBadge(RepoDirtyState state, long dismissedRevision) {
        return state != null
                && state.isDirty()
                && dismissedRevision != NEVER_DISMISSED
                && state.getRevision() == dismissedRevision;
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn -q -pl git-common test`
Expected: PASS — 13 tests.

- [ ] **Step 6: Commit**

```bash
git add git-common/src/main/java/com/axone_io/ignition/git/dto/RepoDirtyState.java \
        git-common/src/main/java/com/axone_io/ignition/git/DirtyStatePolicy.java \
        git-common/src/test/java/com/axone_io/ignition/git/DirtyStatePolicyTest.java
git commit -m "feat(common): add RepoDirtyState DTO and DirtyStatePolicy"
```

---

### Task 2: `ImportSuppression` guard

Stops the feedback loop where a pull imports tags, the watcher sees the resulting resource changes, exports them back out, and prompts the user to commit what was just pulled.

**Files:**
- Create: `git-gateway/src/main/java/com/axone_io/ignition/git/managers/ImportSuppression.java`
- Modify: `git-gateway/src/main/java/com/axone_io/ignition/git/managers/GitTagManager.java` (the `importTagManager` method, around line 220)
- Test: `git-gateway/src/test/java/com/axone_io/ignition/git/managers/ImportSuppressionTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `static boolean ImportSuppression.isSuppressed()`, `static void ImportSuppression.run(Runnable body)`.

- [ ] **Step 1: Write the failing tests**

`git-gateway/src/test/java/com/axone_io/ignition/git/managers/ImportSuppressionTest.java`:

```java
package com.axone_io.ignition.git.managers;

import org.junit.After;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ImportSuppressionTest {

    @After
    public void tearDown() {
        // Guard against a failing test leaking suppression into the next one.
        while (ImportSuppression.isSuppressed()) {
            ImportSuppression.exitForTest();
        }
    }

    @Test
    public void notSuppressedByDefault() {
        assertFalse(ImportSuppression.isSuppressed());
    }

    @Test
    public void suppressedInsideRun() {
        AtomicBoolean seen = new AtomicBoolean();
        ImportSuppression.run(() -> seen.set(ImportSuppression.isSuppressed()));
        assertTrue(seen.get());
    }

    @Test
    public void suppressionLiftedAfterRun() {
        ImportSuppression.run(() -> { });
        assertFalse(ImportSuppression.isSuppressed());
    }

    @Test
    public void suppressionLiftedAfterThrowingBody() {
        try {
            ImportSuppression.run(() -> {
                throw new IllegalStateException("import blew up");
            });
            fail("expected the body's exception to propagate");
        } catch (IllegalStateException expected) {
            // propagation is the point — callers must still see import failures
        }
        assertFalse(ImportSuppression.isSuppressed());
    }

    @Test
    public void nestedRunsStaySuppressedUntilOutermostExits() {
        AtomicBoolean innerExitStillSuppressed = new AtomicBoolean();
        ImportSuppression.run(() -> {
            ImportSuppression.run(() -> { });
            innerExitStillSuppressed.set(ImportSuppression.isSuppressed());
        });
        assertTrue("inner exit must not lift the outer suppression",
                innerExitStillSuppressed.get());
        assertFalse(ImportSuppression.isSuppressed());
    }

    @Test
    public void suppressionIsGlobal_notPerThread() throws Exception {
        // The watcher fires on Ignition's resource threads, not the importing thread,
        // so a ThreadLocal guard would not suppress anything.
        AtomicBoolean seenFromOtherThread = new AtomicBoolean();
        ImportSuppression.run(() -> {
            Thread t = new Thread(() -> seenFromOtherThread.set(ImportSuppression.isSuppressed()));
            t.start();
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(seenFromOtherThread.get());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -pl git-gateway -am test -Dtest=ImportSuppressionTest`
Expected: FAIL — compilation error, `ImportSuppression` does not exist.

- [ ] **Step 3: Write the guard**

`git-gateway/src/main/java/com/axone_io/ignition/git/managers/ImportSuppression.java`:

```java
package com.axone_io.ignition.git.managers;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Marks spans during which this module is itself writing tags into the gateway's tag
 * providers, so {@link TagChangeWatcher} can ignore the resulting resource events.
 *
 * <p>Without this, a pull would import tags, the watcher would see the changes, export
 * them straight back to the working tree, and prompt the user to commit what they had
 * just pulled.</p>
 *
 * <p>Deliberately global rather than thread-local: the resource events arrive on
 * Ignition's own threads, not on the thread doing the import.</p>
 */
public final class ImportSuppression {

    private static final AtomicInteger depth = new AtomicInteger();

    private ImportSuppression() {
    }

    public static boolean isSuppressed() {
        return depth.get() > 0;
    }

    /** Runs {@code body} with suppression active. Exceptions propagate; the guard always lifts. */
    public static void run(Runnable body) {
        depth.incrementAndGet();
        try {
            body.run();
        } finally {
            depth.decrementAndGet();
        }
    }

    /** Test-only escape hatch so a failing test cannot leak suppression into the next one. */
    static void exitForTest() {
        depth.decrementAndGet();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q -pl git-gateway -am test -Dtest=ImportSuppressionTest`
Expected: PASS — 6 tests.

- [ ] **Step 5: Wrap `importTagManager` in the guard**

In `git-gateway/src/main/java/com/axone_io/ignition/git/managers/GitTagManager.java`, the existing method starts:

```java
    public static void importTagManager(String projectName, String collisionPolicyOverride) {
        logger.warn("Importing tags for project '" + projectName + "'. WARNING: This overwrites tag providers " +
                "at the gateway level and will affect ALL projects sharing the same tag providers.");
        Path projectDir = getProjectFolderPath(projectName);
```

Rename it to `importTagManagerInternal` (make it `private static`), and add a new public entry point immediately above it. Every import path in the module — pull, `importResources`, `importTags`, `StartupTagImporter`, `GitCommissioningUtils` — already funnels through `importTagManager`, so this single wrap covers all of them:

```java
    /**
     * Imports tags for the given project from the repository's {@code tags/} directory.
     *
     * <p>Runs under {@link ImportSuppression} so {@link TagChangeWatcher} ignores the tag
     * resource events this import causes — otherwise a pull would immediately prompt the
     * user to commit the changes it had just pulled.</p>
     */
    public static void importTagManager(String projectName, String collisionPolicyOverride) {
        ImportSuppression.run(() -> importTagManagerInternal(projectName, collisionPolicyOverride));
    }

    private static void importTagManagerInternal(String projectName, String collisionPolicyOverride) {
        // ... existing body unchanged ...
    }
```

- [ ] **Step 6: Verify the whole module still compiles and all tests pass**

Run: `mvn -q -pl git-common,git-gateway -am test`
Expected: PASS — no compilation errors, existing `GitTagManagerTest` still green.

- [ ] **Step 7: Commit**

```bash
git add git-gateway/src/main/java/com/axone_io/ignition/git/managers/ImportSuppression.java \
        git-gateway/src/test/java/com/axone_io/ignition/git/managers/ImportSuppressionTest.java \
        git-gateway/src/main/java/com/axone_io/ignition/git/managers/GitTagManager.java
git commit -m "feat(gateway): suppress tag-change detection during module-initiated imports"
```

---

### Task 3: `TagChangeWatcher` core — debounce, eligibility, export

The testable heart of the watcher. The SDK listener wiring is deliberately left to Task 4 so this task can be verified without an Ignition runtime.

**Files:**
- Create: `git-gateway/src/main/java/com/axone_io/ignition/git/managers/TagChangeWatcher.java`
- Test: `git-gateway/src/test/java/com/axone_io/ignition/git/managers/TagChangeWatcherTest.java`

**Interfaces:**
- Consumes: `ImportSuppression.isSuppressed()` from Task 2; `TagExportConfig` from `git-common` (`List<String> getIncludedProviders()`).
- Produces:
  - `TagChangeWatcher(long debounceMillis, Runnable exportAction)` — constructor.
  - `void TagChangeWatcher.onTagResourceChanged()` — records an event, (re)arms the debounce.
  - `void TagChangeWatcher.shutdown()`
  - `static boolean TagChangeWatcher.isAutoExportEligible(TagExportConfig config, int trackedProjectCount)`
  - `static int TagChangeWatcher.runExport(List<String> projectNames, Predicate<String> eligible, Consumer<String> exporter)`
  - `static final long TagChangeWatcher.DEBOUNCE_MS`

- [ ] **Step 1: Write the failing tests**

`git-gateway/src/test/java/com/axone_io/ignition/git/managers/TagChangeWatcherTest.java`:

```java
package com.axone_io.ignition.git.managers;

import com.axone_io.ignition.git.TagExportConfig;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TagChangeWatcherTest {

    // ---------- debounce ----------

    @Test
    public void burstOfEventsProducesExactlyOneExport() throws Exception {
        AtomicInteger exports = new AtomicInteger();
        CountDownLatch fired = new CountDownLatch(1);
        TagChangeWatcher watcher = new TagChangeWatcher(60L, () -> {
            exports.incrementAndGet();
            fired.countDown();
        });
        try {
            for (int i = 0; i < 20; i++) {
                watcher.onTagResourceChanged();
            }
            assertTrue("export should fire after the burst settles", fired.await(5, TimeUnit.SECONDS));
            // Let any wrongly-scheduled extra runs land before asserting.
            Thread.sleep(300L);
            assertEquals(1, exports.get());
        } finally {
            watcher.shutdown();
        }
    }

    @Test
    public void separatedBurstsProduceOneExportEach() throws Exception {
        AtomicInteger exports = new AtomicInteger();
        CountDownLatch twice = new CountDownLatch(2);
        TagChangeWatcher watcher = new TagChangeWatcher(60L, () -> {
            exports.incrementAndGet();
            twice.countDown();
        });
        try {
            watcher.onTagResourceChanged();
            Thread.sleep(400L);
            watcher.onTagResourceChanged();
            assertTrue(twice.await(5, TimeUnit.SECONDS));
            assertEquals(2, exports.get());
        } finally {
            watcher.shutdown();
        }
    }

    @Test
    public void eventsDuringSuppressionNeverExport() throws Exception {
        AtomicInteger exports = new AtomicInteger();
        TagChangeWatcher watcher = new TagChangeWatcher(60L, exports::incrementAndGet);
        try {
            ImportSuppression.run(() -> {
                for (int i = 0; i < 5; i++) {
                    watcher.onTagResourceChanged();
                }
            });
            Thread.sleep(500L);
            assertEquals(0, exports.get());
        } finally {
            watcher.shutdown();
        }
    }

    @Test
    public void exportFailureDoesNotKillTheWatcher() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch twice = new CountDownLatch(2);
        TagChangeWatcher watcher = new TagChangeWatcher(60L, () -> {
            attempts.incrementAndGet();
            twice.countDown();
            throw new RuntimeException("export exploded");
        });
        try {
            watcher.onTagResourceChanged();
            Thread.sleep(400L);
            watcher.onTagResourceChanged();
            assertTrue("a throwing export must not cancel future runs", twice.await(5, TimeUnit.SECONDS));
            assertEquals(2, attempts.get());
        } finally {
            watcher.shutdown();
        }
    }

    // ---------- eligibility ----------

    @Test
    public void singleProjectGateway_alwaysEligible() {
        assertTrue(TagChangeWatcher.isAutoExportEligible(new TagExportConfig(), 1));
    }

    @Test
    public void multiProjectGateway_ineligibleWithoutIncludedProviders() {
        assertFalse("auto-export would write every provider's tags into one repo",
                TagChangeWatcher.isAutoExportEligible(new TagExportConfig(), 3));
    }

    @Test
    public void multiProjectGateway_eligibleWithIncludedProviders() {
        TagExportConfig config = new TagExportConfig(
                Collections.singletonList("PlantA"), new ArrayList<>(), "o");
        assertTrue(TagChangeWatcher.isAutoExportEligible(config, 3));
    }

    @Test
    public void nullConfig_ineligibleOnMultiProjectGateway() {
        assertFalse(TagChangeWatcher.isAutoExportEligible(null, 3));
    }

    // ---------- per-project export ----------

    @Test
    public void exportsOnlyEligibleProjects() {
        List<String> exported = new ArrayList<>();
        int count = TagChangeWatcher.runExport(
                Arrays.asList("A", "B", "C"),
                name -> !name.equals("B"),
                exported::add);
        assertEquals(Arrays.asList("A", "C"), exported);
        assertEquals(2, count);
    }

    @Test
    public void oneProjectFailureDoesNotStopTheRest() {
        List<String> exported = new ArrayList<>();
        int count = TagChangeWatcher.runExport(
                Arrays.asList("A", "B", "C"),
                name -> true,
                name -> {
                    if (name.equals("A")) {
                        throw new RuntimeException("export failed for A");
                    }
                    exported.add(name);
                });
        assertEquals(Arrays.asList("B", "C"), exported);
        assertEquals("failed projects must not be counted as exported", 2, count);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -pl git-gateway -am test -Dtest=TagChangeWatcherTest`
Expected: FAIL — compilation error, `TagChangeWatcher` does not exist.

- [ ] **Step 3: Write the watcher core**

`git-gateway/src/main/java/com/axone_io/ignition/git/managers/TagChangeWatcher.java`. Note this file gains its `ResourceListener` implementation in Task 4; for now it is a plain class:

```java
package com.axone_io.ignition.git.managers;

import com.axone_io.ignition.git.TagExportConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Coalesces tag and UDT resource changes and exports tags to the working tree once the
 * editing burst settles.
 *
 * <p>Renaming a UDT can touch dozens of tag resources, so every event re-arms a single
 * timer rather than triggering work of its own. The export runs on this class's own
 * executor and never on the thread delivering the resource event — blocking Ignition's
 * resource system would stall the gateway.</p>
 */
public class TagChangeWatcher {
    private static final Logger logger = LoggerFactory.getLogger(TagChangeWatcher.class);

    /** Quiet period after the last tag change before tags are exported. */
    public static final long DEBOUNCE_MS = 5_000L;

    private final long debounceMillis;
    private final Runnable exportAction;
    private final ScheduledExecutorService scheduler;

    private ScheduledFuture<?> pending;

    public TagChangeWatcher(long debounceMillis, Runnable exportAction) {
        this.debounceMillis = debounceMillis;
        this.exportAction = exportAction;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "git-tag-change-watcher");
            t.setDaemon(true);
            return t;
        });
    }

    /** Records a tag/UDT resource change and (re)arms the debounce timer. */
    public synchronized void onTagResourceChanged() {
        if (ImportSuppression.isSuppressed()) {
            logger.debug("Ignoring tag resource change: a module-initiated import is in progress.");
            return;
        }
        if (pending != null) {
            pending.cancel(false);
        }
        pending = scheduler.schedule(this::fire, debounceMillis, TimeUnit.MILLISECONDS);
    }

    private void fire() {
        // The burst may have been a module import that started after the timer was armed.
        if (ImportSuppression.isSuppressed()) {
            logger.debug("Skipping tag export: a module-initiated import is in progress.");
            return;
        }
        try {
            exportAction.run();
        } catch (Exception e) {
            // Swallowed deliberately: an escaping exception would cancel nothing here, but
            // logging it keeps a broken export from failing silently forever.
            logger.error("Automatic tag export failed; tag drift will not be visible until "
                    + "the next change or a manual Export.", e);
        }
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }

    /**
     * Whether a project may be auto-exported without a user present to confirm.
     *
     * <p>On a gateway tracking several projects, exporting tags writes <em>every</em>
     * provider's tags into whichever repository ran the export — which is why the manual
     * Export button asks for confirmation first. Automatic export has nobody to ask, so it
     * runs only where the project has narrowed its scope with {@code includedProviders}.</p>
     */
    public static boolean isAutoExportEligible(TagExportConfig config, int trackedProjectCount) {
        if (trackedProjectCount <= 1) {
            return true;
        }
        return config != null
                && config.getIncludedProviders() != null
                && !config.getIncludedProviders().isEmpty();
    }

    /**
     * Exports each eligible project, isolating per-project failures.
     *
     * @return the number of projects exported successfully
     */
    public static int runExport(List<String> projectNames,
                                Predicate<String> eligible,
                                Consumer<String> exporter) {
        int exported = 0;
        for (String projectName : projectNames) {
            if (!eligible.test(projectName)) {
                logger.debug("Skipping automatic tag export for project '" + projectName
                        + "': not eligible on a multi-project gateway without includedProviders.");
                continue;
            }
            try {
                exporter.accept(projectName);
                exported++;
            } catch (Exception e) {
                logger.warn("Automatic tag export failed for project '" + projectName
                        + "'; other projects are unaffected.", e);
            }
        }
        return exported;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q -pl git-gateway -am test -Dtest=TagChangeWatcherTest`
Expected: PASS — 10 tests. The debounce tests use real short timers with latches, so they take a few seconds.

- [ ] **Step 5: Commit**

```bash
git add git-gateway/src/main/java/com/axone_io/ignition/git/managers/TagChangeWatcher.java \
        git-gateway/src/test/java/com/axone_io/ignition/git/managers/TagChangeWatcherTest.java
git commit -m "feat(gateway): add debounced tag-change watcher core"
```

---

### Task 4: Wire the watcher to Ignition's resource system

SDK glue. Not unit-testable without a running gateway, so it is deliberately thin — every decision it could get wrong already lives in Task 3.

**Files:**
- Modify: `git-gateway/src/main/java/com/axone_io/ignition/git/managers/TagChangeWatcher.java`
- Modify: `git-gateway/src/main/java/com/axone_io/ignition/git/GatewayHook.java`

**Interfaces:**
- Consumes: `TagChangeWatcher` from Task 3; `GitTagManager.exportTag(Path)`, `GitTagManager.loadTagExportConfig(Path)` (package-private static, same package); `GatewayScriptModule.getGitTrackedProjectNamesImpl()`.
- Produces: `TagChangeWatcher.createDefault()`, `TagChangeWatcher.asResourceListener()`.

Verified SDK facts (checked against the 8.3.1 jars, do not re-derive):
- `com.inductiveautomation.ignition.gateway.tags.config.TagResourceTypes` exposes `public static final ResourceType TAG_DEFINITION` and `TYPE_DEFINITION`.
- `ConfigurationManager extends ResourceCollectionManager`, reached via `GatewayContext.getConfigurationManager()`, and `ConfigurationManager.getConfigCollection()` returns a `ResourceCollection`.
- `ResourceCollection.addResourceListener(ResourceListener)` exists.
- `com.inductiveautomation.ignition.common.resourcecollection.ResourceListener` declares `onBeforeChanges(ResourceChangeContext)`, `onAfterChanges()`, `manifestChanged(String, List<ChangeOperation.ManifestChangeOperation>)`, `resourcesCreated(String, List<ChangeOperation.CreateResourceOperation>)`, `resourcesModified(String, List<ChangeOperation.ModifyResourceOperation>)`, `resourcesDeleted(String, List<ChangeOperation.DeleteResourceOperation>)`, and `getResourceFilter()`.
- `ResourceFilter.newBuilder()` returns a `ResourceFilter.Builder`.

- [ ] **Step 1: Add the default factory and listener adapter to `TagChangeWatcher`**

Append these imports to `TagChangeWatcher.java`:

```java
import com.axone_io.ignition.git.GatewayHook;
import com.inductiveautomation.ignition.common.resourcecollection.ChangeOperation;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceChangeContext;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceFilter;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceListener;
import com.inductiveautomation.ignition.gateway.tags.config.TagResourceTypes;

import java.nio.file.Path;
import java.util.Arrays;
```

Append these members to the class:

```java
    /**
     * A watcher wired to the real export path: on each settled burst, export tags for every
     * git-tracked project that is eligible for unattended export.
     */
    public static TagChangeWatcher createDefault() {
        return new TagChangeWatcher(DEBOUNCE_MS, TagChangeWatcher::exportAllTrackedProjects);
    }

    private static void exportAllTrackedProjects() {
        List<String> projects = GatewayHook.getScriptModule().getGitTrackedProjectNames();
        int trackedCount = projects.size();

        int exported = runExport(
                projects,
                projectName -> {
                    Path tagsDir = GitManager.getProjectFolderPath(projectName).resolve("tags");
                    return isAutoExportEligible(GitTagManager.loadTagExportConfig(tagsDir), trackedCount);
                },
                projectName -> GitTagManager.exportTag(GitManager.getProjectFolderPath(projectName)));

        logger.debug("Automatic tag export finished for " + exported + " of " + trackedCount
                + " tracked project(s).");
    }

    /**
     * Adapts this watcher to Ignition's resource system. Every callback collapses to the same
     * thing — "some tag or UDT changed" — because the debounced export re-reads the live tag
     * configuration anyway and has no use for the individual operations.
     */
    public ResourceListener asResourceListener() {
        return new ResourceListener() {
            @Override
            public ResourceFilter getResourceFilter() {
                return ResourceFilter.newBuilder()
                        .setResourceTypes(Arrays.asList(
                                TagResourceTypes.TAG_DEFINITION,
                                TagResourceTypes.TYPE_DEFINITION))
                        .build();
            }

            @Override
            public void onBeforeChanges(ResourceChangeContext context) {
            }

            @Override
            public void onAfterChanges() {
            }

            @Override
            public void manifestChanged(String collectionName,
                                        List<ChangeOperation.ManifestChangeOperation> operations) {
            }

            @Override
            public void resourcesCreated(String collectionName,
                                         List<ChangeOperation.CreateResourceOperation> operations) {
                onTagResourceChanged();
            }

            @Override
            public void resourcesModified(String collectionName,
                                          List<ChangeOperation.ModifyResourceOperation> operations) {
                onTagResourceChanged();
            }

            @Override
            public void resourcesDeleted(String collectionName,
                                         List<ChangeOperation.DeleteResourceOperation> operations) {
                onTagResourceChanged();
            }
        };
    }
```

**If the build fails here**, the `ResourceFilter.Builder` setter name or the `ResourceListener` method set differs from the above. Do not guess — inspect the real API and adjust:

```bash
javap -cp ~/.m2/repository/com/inductiveautomation/ignition/common/8.3.1/common-8.3.1.jar \
  'com.inductiveautomation.ignition.common.resourcecollection.ResourceFilter$Builder' \
  com.inductiveautomation.ignition.common.resourcecollection.ResourceListener
```

- [ ] **Step 2: Expose the script module on `GatewayHook`**

`TagChangeWatcher` needs the tracked-project list. `GatewayHook` already holds `private GatewayScriptModule scriptModule` and a `public static GatewayContext context`. Add a static accessor, mirroring the existing static-context pattern. In `GatewayHook.java`, change the field and add the accessor:

```java
    private static GatewayScriptModule scriptModule;

    public static GatewayScriptModule getScriptModule() {
        return scriptModule;
    }
```

- [ ] **Step 3: Register the watcher on startup and release it on shutdown**

In `GatewayHook.java`, add the field:

```java
    private TagChangeWatcher tagChangeWatcher;
    private com.inductiveautomation.ignition.common.resourcecollection.ResourceListener tagResourceListener;
```

Replace the body of `startup(LicenseState)`:

```java
    @Override
    public void startup(LicenseState licenseState) {
        GitCommissioningUtils.loadConfiguration();
        GitCommissioningUtils.startTagImportOnStartup();
        startTagChangeWatcher();

        logger.info("startup()");
    }

    /**
     * Watches Ignition's config resources for tag and UDT changes. Tags are not project
     * resources, so the Designer's project-save hook never sees them — without this, tag
     * edits would silently never reach git.
     */
    private void startTagChangeWatcher() {
        try {
            tagChangeWatcher = TagChangeWatcher.createDefault();
            tagResourceListener = tagChangeWatcher.asResourceListener();
            context.getConfigurationManager().getConfigCollection()
                    .addResourceListener(tagResourceListener);
            logger.info("Tag change watcher registered.");
        } catch (Exception e) {
            logger.error("Could not register the tag change watcher. Tag and UDT edits will not "
                    + "automatically prompt for a commit; the Export button still works.", e);
            tagChangeWatcher = null;
            tagResourceListener = null;
        }
    }
```

Replace the body of `shutdown()`:

```java
    @Override
    public void shutdown() {
        if (tagChangeWatcher != null) {
            tagChangeWatcher.shutdown();
            tagChangeWatcher = null;
            tagResourceListener = null;
        }
        logger.info("shutdown()");
    }
```

- [ ] **Step 4: Verify the module builds and all tests still pass**

Run: `mvn -q clean package -DskipTests && mvn -q test`
Expected: BUILD SUCCESS, `git-build/target/Git-unsigned.modl` produced, all existing tests green.

- [ ] **Step 5: Commit**

```bash
git add git-gateway/src/main/java/com/axone_io/ignition/git/managers/TagChangeWatcher.java \
        git-gateway/src/main/java/com/axone_io/ignition/git/GatewayHook.java
git commit -m "feat(gateway): register the tag change watcher on the config resource collection"
```

---

### Task 5: `getRepoDirtyState` RPC

Threads one new method through all four RPC layers and computes the drift summary on the gateway.

**Files:**
- Modify: `git-common/src/main/java/com/axone_io/ignition/git/GitScriptInterface.java`
- Modify: `git-common/src/main/java/com/axone_io/ignition/git/AbstractScriptModule.java`
- Modify: `git-gateway/src/main/java/com/axone_io/ignition/git/GatewayScriptModule.java`
- Modify: `git-client/src/main/java/com/axone_io/ignition/git/ClientScriptModule.java`

**Interfaces:**
- Consumes: `RepoDirtyState` from Task 1; existing `getUncommitedChangesImpl(String, String)` returning `List<UncommittedChange>` (getters: `getResource()`, `getType()`, `getActor()`); existing `getProductionModeConfigImpl(String)`.
- Produces: `RepoDirtyState getRepoDirtyState(String projectName, String userName)` on `GitScriptInterface`.

- [ ] **Step 1: Add to the RPC interface**

In `GitScriptInterface.java`, add the import and the method (place it after `getUncommitedChanges` on line 15):

```java
import com.axone_io.ignition.git.dto.RepoDirtyState;
```

```java
    /**
     * Summarises how far the working tree has drifted from HEAD. Polled by the Designer to
     * decide whether to prompt for a commit; never throws, returning a clean state instead,
     * because a background poller must not surface transient errors as dialogs.
     */
    RepoDirtyState getRepoDirtyState(String projectName, String userName);
```

- [ ] **Step 2: Add the wrapper and abstract method**

In `AbstractScriptModule.java`, add the import, then the wrapper alongside the other wrappers:

```java
import com.axone_io.ignition.git.dto.RepoDirtyState;
```

```java
    @Override
    public RepoDirtyState getRepoDirtyState(String projectName,
                                            String userName) {
        return getRepoDirtyStateImpl(projectName, userName);
    }
```

And the abstract declaration alongside the others near line 234:

```java
    protected abstract RepoDirtyState getRepoDirtyStateImpl(String projectName, String userName);
```

- [ ] **Step 3: Add the client delegation**

In `ClientScriptModule.java`, add the import and the delegation:

```java
import com.axone_io.ignition.git.dto.RepoDirtyState;
```

```java
    @Override
    protected RepoDirtyState getRepoDirtyStateImpl(String projectName, String userName) {
        return rpc.getRepoDirtyState(projectName, userName);
    }
```

- [ ] **Step 4: Implement it on the gateway**

In `GatewayScriptModule.java`, add the import and the implementation next to `getUncommitedChangesImpl`:

```java
import com.axone_io.ignition.git.dto.RepoDirtyState;
```

```java
    @Override
    protected RepoDirtyState getRepoDirtyStateImpl(String projectName, String userName) {
        RepoDirtyState state = new RepoDirtyState();

        List<UncommittedChange> changes;
        try {
            changes = getUncommitedChangesImpl(projectName, userName);
        } catch (Exception e) {
            // A poller must never turn a transient git error into a popup. Report clean and
            // let the next poll correct it.
            logger.debug("Unable to read working tree status for '" + projectName + "'", e);
            return state;
        }

        List<String> keys = new ArrayList<>();
        int tagCount = 0;
        int projectCount = 0;
        for (UncommittedChange change : changes) {
            String resource = change.getResource();
            if (resource != null && resource.startsWith("tags/")) {
                tagCount++;
            } else {
                projectCount++;
            }
            keys.add(change.getType() + ":" + resource);
        }
        Collections.sort(keys);

        state.setDirty(!changes.isEmpty());
        state.setTagChangeCount(tagCount);
        state.setProjectChangeCount(projectCount);
        state.setRevision(changes.isEmpty() ? 0L : hashChangeSet(keys));

        try {
            ProductionModeConfig config = getProductionModeConfigImpl(projectName);
            state.setProductionMode(config != null && config.isProductionMode());
        } catch (Exception e) {
            // Fail conservative on mode: an unverifiable gateway is treated as production so
            // the Designer shows the safety checklist rather than the lightweight prompt.
            logger.debug("Unable to read production mode for '" + projectName
                    + "'; assuming production", e);
            state.setProductionMode(true);
        }

        return state;
    }

    /**
     * 64-bit FNV-1a over the sorted change set. The Designer treats a changed revision as
     * "new changes worth prompting about", so this must depend on which files changed and
     * how — not merely how many.
     */
    private static long hashChangeSet(List<String> sortedKeys) {
        long hash = 0xcbf29ce484222325L;
        for (String key : sortedKeys) {
            for (int i = 0; i < key.length(); i++) {
                hash ^= key.charAt(i);
                hash *= 0x100000001b3L;
            }
            hash ^= '\n';
            hash *= 0x100000001b3L;
        }
        return hash;
    }
```

Ensure `java.util.Collections` and `java.util.ArrayList` are imported in that file (`ArrayList` already is).

- [ ] **Step 5: Verify the build**

Run: `mvn -q clean package -DskipTests`
Expected: BUILD SUCCESS. A missing implementation in any of the four layers fails compilation here — that is the check.

- [ ] **Step 6: Commit**

```bash
git add git-common/src/main/java/com/axone_io/ignition/git/GitScriptInterface.java \
        git-common/src/main/java/com/axone_io/ignition/git/AbstractScriptModule.java \
        git-gateway/src/main/java/com/axone_io/ignition/git/GatewayScriptModule.java \
        git-client/src/main/java/com/axone_io/ignition/git/ClientScriptModule.java
git commit -m "feat(rpc): add getRepoDirtyState for working-tree drift polling"
```

---
### Task 6: Designer prompt — dialog, router, poller, and pulsing badge

The whole Designer-side surface lands in one task so the badge and the poller that drives it are never separated by a placeholder.

**Files:**
- Create: `git-designer/src/main/java/com/axone_io/ignition/git/PendingChangesDialog.java`
- Create: `git-designer/src/main/java/com/axone_io/ignition/git/managers/GitWorkflowPrompter.java`
- Modify: `git-designer/src/main/java/com/axone_io/ignition/git/DesignerHook.java`

**Interfaces:**
- Consumes: `RepoDirtyState` and `DirtyStatePolicy` (including `DirtyStatePolicy.NEVER_DISMISSED`, `decide(...)`, `shouldShowBadge(...)`) from Task 1; the `getRepoDirtyState` RPC from Task 5; existing `GitActionManager.showCommitWithHotfixDetection(String, String)`; existing `ProductionModePopup(Component parent, ProductionModeConfig config, String operation)` whose `public void onProceed()` is overridable.
- Produces:
  - `PendingChangesDialog(Window parent, RepoDirtyState state, Runnable onCommit)` — modal, disposes itself.
  - `static void GitWorkflowPrompter.prompt(RepoDirtyState state, String projectName, String userName, Runnable onDismissed)`
  - `static boolean GitWorkflowPrompter.isPromptOpen()`

- [ ] **Step 1: Write the non-production dialog**

`git-designer/src/main/java/com/axone_io/ignition/git/PendingChangesDialog.java`. Standard Swing layouts only — the IntelliJ forms library breaks the Designer's classloader:

```java
package com.axone_io.ignition.git;

import com.axone_io.ignition.git.dto.RepoDirtyState;

import javax.swing.*;
import java.awt.*;

/**
 * Non-production prompt shown once the working tree has drifted from HEAD. Deliberately
 * lighter than {@link ProductionModePopup}: on a development gateway the risk is forgetting
 * to commit, not committing something dangerous.
 */
public class PendingChangesDialog extends JDialog {

    public PendingChangesDialog(Window parent, RepoDirtyState state, Runnable onCommit) {
        super(parent, "Uncommitted Changes", ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setResizable(false);

        JPanel main = new JPanel();
        main.setLayout(new BoxLayout(main, BoxLayout.Y_AXIS));
        main.setBorder(BorderFactory.createEmptyBorder(15, 20, 15, 20));

        JLabel heading = new JLabel("This gateway has changes that are not in Git.");
        heading.setFont(new Font("Dialog", Font.BOLD, 13));
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        main.add(heading);

        main.add(Box.createVerticalStrut(8));

        JLabel summary = new JLabel(state.describeChanges() + ".");
        summary.setAlignmentX(Component.LEFT_ALIGNMENT);
        main.add(summary);

        main.add(Box.createVerticalStrut(15));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setAlignmentX(Component.LEFT_ALIGNMENT);

        JButton notNow = new JButton("Not now");
        notNow.addActionListener(e -> dispose());

        JButton commit = new JButton("Commit");
        commit.addActionListener(e -> {
            dispose();
            onCommit.run();
        });

        buttons.add(notNow);
        buttons.add(commit);
        main.add(buttons);

        getRootPane().setDefaultButton(commit);
        setContentPane(main);
        pack();
        setLocationRelativeTo(parent);
        setVisible(true);
    }
}
```

- [ ] **Step 2: Write the prompt router**

`git-designer/src/main/java/com/axone_io/ignition/git/managers/GitWorkflowPrompter.java`:

```java
package com.axone_io.ignition.git.managers;

import com.axone_io.ignition.git.DesignerHook;
import com.axone_io.ignition.git.PendingChangesDialog;
import com.axone_io.ignition.git.ProductionModePopup;
import com.axone_io.ignition.git.dto.ProductionModeConfig;
import com.axone_io.ignition.git.dto.RepoDirtyState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routes uncommitted-change prompts by gateway mode. Both paths land in the same commit
 * dialog; production adds the safety checklist first, exactly as project saves do.
 */
public final class GitWorkflowPrompter {
    private static final Logger logger = LoggerFactory.getLogger(GitWorkflowPrompter.class);

    private static volatile boolean promptOpen = false;

    private GitWorkflowPrompter() {
    }

    /** Whether a prompt is on screen. The poller uses this so dialogs never stack. */
    public static boolean isPromptOpen() {
        return promptOpen;
    }

    /**
     * Shows the prompt for the current mode. Must be called on the EDT. Both dialogs are
     * application-modal and call {@code setVisible(true)} from their constructors, so this
     * method does not return until the user has dismissed the prompt.
     *
     * @param onDismissed run after the dialog closes, whichever button was used — the caller
     *                    records the dismissed revision so the same changes do not re-prompt.
     */
    public static void prompt(RepoDirtyState state,
                              String projectName,
                              String userName,
                              Runnable onDismissed) {
        if (promptOpen) {
            return;
        }
        promptOpen = true;
        try {
            if (state.isProductionMode()) {
                promptProduction(state, projectName, userName);
            } else {
                new PendingChangesDialog(
                        DesignerHook.context.getFrame(),
                        state,
                        () -> GitActionManager.showCommitWithHotfixDetection(projectName, userName));
            }
        } catch (Exception e) {
            logger.warn("Unable to show the uncommitted-changes prompt", e);
        } finally {
            promptOpen = false;
            onDismissed.run();
        }
    }

    private static void promptProduction(RepoDirtyState state, String projectName, String userName) {
        ProductionModeConfig config = DesignerHook.getCachedProductionConfig();
        if (config == null) {
            // The cache is invalidated by pull, branch switch, and hotfix. Fall back to a
            // minimal config rather than skipping the checklist on a production gateway.
            config = new ProductionModeConfig(true, null, null);
        }
        new ProductionModePopup(DesignerHook.context.getFrame(), config,
                "Uncommitted Changes on Production Gateway") {
            @Override
            public void onProceed() {
                GitActionManager.showCommitWithHotfixDetection(projectName, userName);
            }
        };
    }
}
```

- [ ] **Step 3: Add the Designer fields**

In `DesignerHook.java`, add imports:

```java
import com.axone_io.ignition.git.DirtyStatePolicy;
import com.axone_io.ignition.git.dto.RepoDirtyState;
import com.axone_io.ignition.git.managers.GitWorkflowPrompter;
```

Add fields alongside the existing timers and `productionBadge`:

```java
    Timer repoDirtyTimer;
    JLabel pendingChangesBadge;
    Timer pendingBadgePulseTimer;
    private boolean pulseBright = false;
    private RepoDirtyState lastKnownDirtyState;
    private long dismissedRevision = DirtyStatePolicy.NEVER_DISMISSED;
    private boolean dirtyCheckInFlight = false;
    private boolean gitConfigured = true;

    private static final java.awt.Color BADGE_DIM = new java.awt.Color(191, 110, 0);
    private static final java.awt.Color BADGE_BRIGHT = new java.awt.Color(245, 158, 11);
```

`startup(...)` already tolerates an unconfigured project — it catches the `setupLocalRepo`
failure, logs a warning, and lets the module load anyway. Record that outcome so the poller
never nags on a project that has no repository. In the existing `catch` block around
`rpc.setupLocalRepo(projectName, userName)`, add as the last line:

```java
            gitConfigured = false;
```

- [ ] **Step 4: Build the badge in `initStatusBar()`**

Insert immediately after the existing `gitStatusBar.add(productionBadge);` line, so the badge sits beside the PRODUCTION badge. On a production gateway with drift both are visible — deliberately, since production is where a lingering reminder matters most:

```java
        // Pending-changes badge — shown after the user dismisses a commit prompt while the
        // working tree is still dirty. Pulses so it reads as an outstanding action rather
        // than decoration, and clears only when the changes are actually committed.
        pendingChangesBadge = new JLabel(" UNCOMMITTED ");
        pendingChangesBadge.setFont(new java.awt.Font("Dialog", java.awt.Font.BOLD, 10));
        pendingChangesBadge.setForeground(java.awt.Color.WHITE);
        pendingChangesBadge.setBackground(BADGE_DIM);
        pendingChangesBadge.setOpaque(true);
        pendingChangesBadge.setToolTipText("Uncommitted changes — click to commit");
        pendingChangesBadge.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        pendingChangesBadge.setBorder(javax.swing.BorderFactory.createCompoundBorder(
            javax.swing.BorderFactory.createLineBorder(new java.awt.Color(230, 145, 56), 1),
            javax.swing.BorderFactory.createEmptyBorder(2, 6, 2, 6)
        ));
        pendingChangesBadge.setVisible(false);
        pendingChangesBadge.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                // Clicking is an explicit request to see the prompt again for these changes.
                dismissedRevision = DirtyStatePolicy.NEVER_DISMISSED;
                applyDirtyState();
            }
        });
        gitStatusBar.add(pendingChangesBadge);

        pendingBadgePulseTimer = new Timer(700, e -> {
            pulseBright = !pulseBright;
            pendingChangesBadge.setBackground(pulseBright ? BADGE_BRIGHT : BADGE_DIM);
            pendingChangesBadge.repaint();
        });
```

- [ ] **Step 5: Add the poller and the badge updater**

Add these methods to `DesignerHook`:

```java
    /**
     * Polls working-tree drift. Tag and UDT edits never reach the project-save hook, so
     * polling is the only way the Designer learns about them.
     */
    private void initRepoDirtyPolling() {
        if (!gitConfigured) {
            logger.info("Git is not configured for project '{}'; not polling for uncommitted changes.",
                    projectName);
            return;
        }
        repoDirtyTimer = new Timer(5000, e -> checkRepoDirtyState());
        repoDirtyTimer.start();
    }

    private void checkRepoDirtyState() {
        if (!gitConfigured || dirtyCheckInFlight || GitWorkflowPrompter.isPromptOpen()) {
            return;
        }
        dirtyCheckInFlight = true;

        SwingWorker<RepoDirtyState, Void> worker = new SwingWorker<RepoDirtyState, Void>() {
            @Override
            protected RepoDirtyState doInBackground() {
                return rpc.getRepoDirtyState(projectName, userName);
            }

            @Override
            protected void done() {
                try {
                    RepoDirtyState state = get();
                    if (state != null) {
                        lastKnownDirtyState = state;
                    }
                } catch (Exception ex) {
                    // Fail quiet: a background poller must not turn a flapping connection into
                    // repeated dialogs. The save-time production warning still fails
                    // conservative, so the moment that changes a gateway stays guarded.
                    logger.debug("Unable to poll repository state: {}", ex.getMessage());
                } finally {
                    dirtyCheckInFlight = false;
                    applyDirtyState();
                }
            }
        };
        worker.execute();
    }

    private void applyDirtyState() {
        RepoDirtyState state = lastKnownDirtyState;

        DirtyStatePolicy.Action action =
                DirtyStatePolicy.decide(state, dismissedRevision, GitWorkflowPrompter.isPromptOpen());

        if (action == DirtyStatePolicy.Action.PROMPT) {
            long revision = state.getRevision();
            GitWorkflowPrompter.prompt(state, projectName, userName, () -> {
                dismissedRevision = revision;
                updatePendingBadge();
            });
        }
        updatePendingBadge();
    }

    private void updatePendingBadge() {
        if (pendingChangesBadge == null) {
            return;
        }
        boolean show = DirtyStatePolicy.shouldShowBadge(lastKnownDirtyState, dismissedRevision);
        SwingUtilities.invokeLater(() -> {
            if (show == pendingChangesBadge.isVisible()) {
                return;
            }
            pendingChangesBadge.setVisible(show);
            if (show) {
                pendingBadgePulseTimer.start();
            } else {
                pendingBadgePulseTimer.stop();
                pendingChangesBadge.setBackground(BADGE_DIM);
            }
        });
    }
```

Call `initRepoDirtyPolling();` at the end of `startup(...)`, immediately after `productionConfigRefreshTimer.start();`.

- [ ] **Step 6: Kick an immediate check after a project save**

In `notifyProjectSaveDone()`, a project save should not wait up to 5 seconds for the next tick. Add a kick immediately after `super.notifyProjectSaveDone();`:

```java
        // A project save dirties the tree immediately; don't make the user wait for the poll.
        SwingUtilities.invokeLater(this::checkRepoDirtyState);
```

The existing production save-warning logic below it is unchanged.

- [ ] **Step 7: Stop both timers on shutdown**

In `shutdown()`, alongside the existing timer cleanup:

```java
        if (repoDirtyTimer != null) {
            repoDirtyTimer.stop();
        }
        if (pendingBadgePulseTimer != null) {
            pendingBadgePulseTimer.stop();
        }
```

- [ ] **Step 8: Verify the build**

Run: `mvn -q clean package -DskipTests && mvn -q test`
Expected: BUILD SUCCESS, all tests green, `git-build/target/Git-unsigned.modl` produced.

- [ ] **Step 9: Commit**

```bash
git add git-designer/src/main/java/com/axone_io/ignition/git/PendingChangesDialog.java \
        git-designer/src/main/java/com/axone_io/ignition/git/managers/GitWorkflowPrompter.java \
        git-designer/src/main/java/com/axone_io/ignition/git/DesignerHook.java
git commit -m "feat(designer): prompt and pulsing badge for uncommitted working-tree changes"
```

---

### Task 7: Documentation

**Files:**
- Modify: `CLAUDE.md`
- Modify: `docs/production-mode.md`

**Interfaces:**
- Consumes: every class from Tasks 1–7.
- Produces: nothing.

- [ ] **Step 1: Add the new classes to the CLAUDE.md key-classes table**

Add these rows to the existing table, keeping its column order (`Class | Module | Purpose`):

```markdown
| `TagChangeWatcher` | git-gateway | Debounced tag/UDT resource listener; exports tags to the working tree |
| `ImportSuppression` | git-gateway | Guard disabling the watcher during module-initiated tag imports |
| `DirtyStatePolicy` | git-common | Decides prompt vs badge vs nothing for working-tree drift |
| `GitWorkflowPrompter` | git-designer | Routes the uncommitted-changes prompt by production mode |
| `PendingChangesDialog` | git-designer | Non-production "commit now?" dialog |
```

- [ ] **Step 2: Add the DTO to the CLAUDE.md DTO list**

Add to the bulleted DTO list:

```markdown
- `RepoDirtyState` - Working-tree drift summary (revision hash, dirty flag, change counts, production mode)
```

- [ ] **Step 3: Document the behaviour in `docs/production-mode.md`**

Append a new section:

```markdown
## Tag and UDT Change Prompting

Tags are not project resources, so editing one in the Designer's Tag Browser does not
trigger a project save — which means it would otherwise never enter the Git workflow.

The gateway watches Ignition's config resources for tag and UDT changes. Five seconds
after the last change in a burst, it exports tags to the working tree; the Designer polls
for working-tree drift every five seconds and prompts.

- **Production mode** — the same 4-checkbox safety checklist used for saves, titled
  "Uncommitted Changes on Production Gateway". Proceeding opens the commit dialog, which
  auto-pushes and triggers the hotfix workflow on the production branch.
- **Non-production** — a lightweight dialog naming what changed, with Commit and Not now.

Dismissing either prompt leaves a pulsing **UNCOMMITTED** badge in the status bar. It
clears only when the changes are committed, and clicking it reopens the prompt. The same
changes never prompt twice; editing something new prompts again.

The prompt covers project changes as well as tag changes — the signal is "the working tree
does not match HEAD", whatever caused it, including changes made by scripts, by the
gateway web UI, or in another engineer's Designer session.

### Automatic export on multi-project gateways

Exporting tags writes every included provider's tags into the repository running the
export, which is why the manual Export button asks for confirmation on a gateway tracking
several projects. Automatic export has nobody to ask, so on a multi-project gateway it
runs only for projects that have narrowed their scope via `includedProviders` in
`tags/.tag-config.json`. Other projects are skipped with a logged warning; their manual
Export button is unaffected. Single-project gateways always auto-export.

### Module-initiated imports

Pulls, gateway-startup tag imports, and commissioning imports write tags into the gateway's
providers. These run under a suppression guard so they do not trigger a prompt to commit
changes the module has just pulled.
```

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md docs/production-mode.md
git commit -m "docs: document tag change prompting and multi-project auto-export limits"
```

---

## Verification Checklist

Before considering the feature complete:

- [ ] `mvn clean package -DskipTests` succeeds and produces `git-build/target/Git-unsigned.modl`
- [ ] `mvn test` passes, including the new `DirtyStatePolicyTest`, `ImportSuppressionTest`, and `TagChangeWatcherTest`
- [ ] The gateway log shows the watcher registering at startup and no repeated export errors
- [ ] The manual Designer walkthrough below passes

### Manual Designer Walkthrough

The Swing prompts and the pulse animation cannot be unit tested. Deploy with the documented
loop (set `IGNITION_GIT_MODULE_PATH` and `DEPLOYMENTS_PATH` first):

```bash
cd $IGNITION_GIT_MODULE_PATH && \
mvn clean package -DskipTests && \
docker cp git-build/target/Git-unsigned.modl whk-services-ignition-1:/usr/local/bin/ignition/user-lib/modules/ && \
cd $DEPLOYMENTS_PATH && docker compose restart ignition
```

Check the watcher registered:

```bash
docker logs whk-services-ignition-1 2>&1 | grep -i "tag change watcher\|automatic tag export" | tail -20
```

Restart the Designer to pick up the new module version, then walk through:

1. Edit a single tag's value in the Tag Browser and click OK. Within ~10 s the prompt appears naming 1 tag change.
2. Click **Not now**. The pulsing UNCOMMITTED badge appears in the status bar.
3. Wait 30 s. No second prompt for the same changes.
4. Edit a second tag. A new prompt appears (the change set, and so the revision, changed).
5. Click **Commit** and complete the commit. The badge disappears within ~10 s.
6. Re-dirty, dismiss, then click the badge. The prompt reopens.
7. Rename a UDT so dozens of tags change. Exactly one prompt appears, not dozens.
8. Run a **Pull** that changes tags. No prompt appears afterwards — this is the import-suppression path.
9. Set `production_mode: true` in `git.yaml`, restart, and repeat step 1. The 4-checkbox `ProductionModePopup` appears instead, titled "Uncommitted Changes on Production Gateway", and the badge appears alongside the PRODUCTION badge after dismissal.
