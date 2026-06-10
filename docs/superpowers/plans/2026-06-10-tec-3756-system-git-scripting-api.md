# TEC-3756: `system.git.*` Scripting API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose a curated `system.git.*` scripting API (tag import + read-only repo status) in Gateway, Designer, and Vision Client scopes.

**Architecture:** A new `GitScriptFunctions` facade class in `git-common` wraps a lazily-resolved `GitScriptInterface` delegate and exposes only 8 curated functions. The gateway registers it around its local `GatewayScriptModule`; Designer and Client register it around their RPC proxy. One new RPC method (`importTags`) is added through the standard 4-class chain (interface → abstract → gateway impl → client delegation), with **no overloads in the RPC interface** (RPC cannot handle overloading; the facade itself MAY overload because Jython resolves Java overloads).

**Tech Stack:** Java 17, Ignition 8.3 Module SDK (`ScriptManager.addScriptModule`, `@ScriptFunction`, `PropertiesFileDocProvider`), JUnit 4.13.2, Maven.

**Linear ticket:** [TEC-3756](https://linear.app/whiskey-house-eandt/issue/TEC-3756)

**Critical codebase rules (from CLAUDE.md):**
- Never overload methods in `GitScriptInterface` — RPC breaks.
- Interface changes require updating all 4 classes: `GitScriptInterface`, `AbstractScriptModule`, `GatewayScriptModule`, `ClientScriptModule`.
- Build: `mvn clean package -DskipTests` (full), `mvn test` (tests).

---

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `git-common/src/main/java/com/axone_io/ignition/git/GitScriptInterface.java` | Modify | Add `importTags` RPC method definition |
| `git-common/src/main/java/com/axone_io/ignition/git/AbstractScriptModule.java` | Modify | Add `importTags` wrapper + abstract `importTagsImpl` |
| `git-gateway/src/main/java/com/axone_io/ignition/git/GatewayScriptModule.java` | Modify | Implement `importTagsImpl` via `GitTagManager` |
| `git-client/src/main/java/com/axone_io/ignition/git/ClientScriptModule.java` | Modify | RPC delegation for `importTagsImpl` |
| `git-common/src/main/java/com/axone_io/ignition/git/GitScriptFunctions.java` | Create | Curated script-function facade (the only class exposed as `system.git`) |
| `git-common/src/main/resources/com/axone_io/ignition/git/GitScriptFunctions.properties` | Create | Autocomplete doc bundle for the facade |
| `git-common/src/test/java/com/axone_io/ignition/git/GitScriptFunctionsTest.java` | Create | Facade unit tests (delegation, validation, lazy resolution) |
| `git-common/pom.xml` | Modify | Add JUnit test dependency |
| `git-gateway/src/main/java/com/axone_io/ignition/git/GatewayHook.java` | Modify | Register `system.git` in gateway scope |
| `git-designer/src/main/java/com/axone_io/ignition/git/DesignerHook.java` | Modify | Register `system.git` in designer scope (replace commented block) |
| `git-client/src/main/java/com/axone_io/ignition/git/ClientHook.java` | Modify | Register `system.git` in Vision client scope (replace commented block) |
| `docs/scripting-api.md` | Create | User-facing function reference |
| `CLAUDE.md` | Modify | Add `GitScriptFunctions` to Key Classes table |

---

### Task 1: Add `importTags` RPC method through the 4-class chain

**Files:**
- Modify: `git-common/src/main/java/com/axone_io/ignition/git/GitScriptInterface.java`
- Modify: `git-common/src/main/java/com/axone_io/ignition/git/AbstractScriptModule.java`
- Modify: `git-gateway/src/main/java/com/axone_io/ignition/git/GatewayScriptModule.java`
- Modify: `git-client/src/main/java/com/axone_io/ignition/git/ClientScriptModule.java`

There is no practical unit test for the RPC chain itself (it needs a running gateway); compilation is the verification for this task. The behavior gets tested through the facade in Task 2.

- [ ] **Step 1: Add the method to `GitScriptInterface`**

In `GitScriptInterface.java`, directly after the `importResources` declaration (the line `boolean importResources(String projectName, boolean importTags, boolean importTheme, boolean importImages, String collisionPolicy) throws Exception;`), add:

```java
    // Import only tags from the working tree into gateway tag providers (no pull).
    // collisionPolicy may be null/empty -> falls back to the repo's tag_config policy.
    boolean importTags(String projectName, String collisionPolicy) throws Exception;
```

Do NOT name it `importResources` or reuse any existing method name — RPC does not support overloading.

- [ ] **Step 2: Add wrapper + abstract method to `AbstractScriptModule`**

In `AbstractScriptModule.java`, after the `importResources` override (ends around line 151), add:

```java
    @Override
    public boolean importTags(String projectName,
                              String collisionPolicy) throws Exception {
        return importTagsImpl(projectName, collisionPolicy);
    }
```

And in the block of `protected abstract` declarations at the bottom, after the `importResourcesImpl` line, add:

```java
    protected abstract boolean importTagsImpl(String projectName, String collisionPolicy) throws Exception;
```

- [ ] **Step 3: Implement in `GatewayScriptModule`**

In `GatewayScriptModule.java`, after the `importResourcesImpl` method (around line 895 — the method that calls `GitTagManager.importTagManager(projectName, collisionPolicy)` when its `importTags` flag is true), add:

```java
    @Override
    protected boolean importTagsImpl(String projectName, String collisionPolicy) throws Exception {
        GitTagManager.importTagManager(projectName, collisionPolicy);
        return true;
    }
```

`GitTagManager` is already imported in this file. `importTagManager` handles a missing `tags/` directory gracefully (logs and returns) and resolves a null/empty collision policy from the repo's `tag_config`.

- [ ] **Step 4: Delegate in `ClientScriptModule`**

In `ClientScriptModule.java`, after the `importResourcesImpl` method (around line 123), add:

```java
    @Override
    protected boolean importTagsImpl(String projectName, String collisionPolicy) throws Exception {
        return rpc.importTags(projectName, collisionPolicy);
    }
```

- [ ] **Step 5: Verify it compiles**

Run: `mvn -q compile`
Expected: BUILD SUCCESS (no abstract-method errors in `GatewayScriptModule` or `ClientScriptModule` — if either fails to compile with "is not abstract and does not override", a chain class was missed).

- [ ] **Step 6: Commit**

```bash
git add git-common/src/main/java/com/axone_io/ignition/git/GitScriptInterface.java \
        git-common/src/main/java/com/axone_io/ignition/git/AbstractScriptModule.java \
        git-gateway/src/main/java/com/axone_io/ignition/git/GatewayScriptModule.java \
        git-client/src/main/java/com/axone_io/ignition/git/ClientScriptModule.java
git commit -m "feat(rpc): add importTags method through the 4-class RPC chain (TEC-3756)"
```

---

### Task 2: `GitScriptFunctions` facade with tests (TDD)

**Files:**
- Modify: `git-common/pom.xml`
- Test: `git-common/src/test/java/com/axone_io/ignition/git/GitScriptFunctionsTest.java`
- Create: `git-common/src/main/java/com/axone_io/ignition/git/GitScriptFunctions.java`
- Create: `git-common/src/main/resources/com/axone_io/ignition/git/GitScriptFunctions.properties`

- [ ] **Step 1: Add JUnit to `git-common/pom.xml`**

`git-common` currently has no test dependencies. In `git-common/pom.xml`, inside `<dependencies>`, add (same version as `git-gateway/pom.xml`):

```xml
        <dependency>
            <groupId>junit</groupId>
            <artifactId>junit</artifactId>
            <version>4.13.2</version>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 2: Write the failing test**

Create `git-common/src/test/java/com/axone_io/ignition/git/GitScriptFunctionsTest.java`. The test uses a `java.lang.reflect.Proxy` to record every delegate invocation — no mocking library needed, and no need to hand-implement the ~35-method interface:

```java
package com.axone_io.ignition.git;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class GitScriptFunctionsTest {

    private List<String> calls;
    private GitScriptInterface recordingDelegate;

    @Before
    public void setUp() {
        calls = new ArrayList<>();
        recordingDelegate = (GitScriptInterface) Proxy.newProxyInstance(
                GitScriptInterface.class.getClassLoader(),
                new Class<?>[]{GitScriptInterface.class},
                (proxy, method, args) -> {
                    calls.add(method.getName() + Arrays.toString(args == null ? new Object[0] : args));
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return true;
                    if (rt == List.class) return Collections.emptyList();
                    if (rt == String.class) return "stub";
                    return null;
                });
    }

    private GitScriptFunctions functions() {
        return new GitScriptFunctions(() -> recordingDelegate);
    }

    // ---- importTags ----

    @Test
    public void importTags_singleArg_delegatesWithNullPolicy() throws Exception {
        assertTrue(functions().importTags("MyProject"));
        assertEquals(Collections.singletonList("importTags[MyProject, null]"), calls);
    }

    @Test
    public void importTags_withPolicy_delegatesPolicy() throws Exception {
        assertTrue(functions().importTags("MyProject", "o"));
        assertEquals(Collections.singletonList("importTags[MyProject, o]"), calls);
    }

    @Test
    public void importTags_rejectsBlankProjectName_withoutCallingDelegate() throws Exception {
        for (String bad : new String[]{null, "", "   "}) {
            try {
                functions().importTags(bad);
                fail("expected IllegalArgumentException for projectName=" + bad);
            } catch (IllegalArgumentException expected) {
                // expected
            }
        }
        assertTrue("delegate must not be called for invalid input", calls.isEmpty());
    }

    // ---- read-only delegation ----

    @Test
    public void getUncommittedChanges_delegatesToTypoRpcName() throws Exception {
        functions().getUncommittedChanges("P", "user");
        // The RPC interface method is (sic) getUncommitedChanges; the facade fixes
        // the spelling at the script layer but must call the existing RPC name.
        assertEquals(Collections.singletonList("getUncommitedChanges[P, user]"), calls);
    }

    @Test
    public void getCurrentBranch_delegates() throws Exception {
        assertEquals("stub", functions().getCurrentBranch("P"));
        assertEquals(Collections.singletonList("getCurrentBranch[P]"), calls);
    }

    @Test
    public void getBranchStatus_delegates() throws Exception {
        functions().getBranchStatus("P", "user");
        assertEquals(Collections.singletonList("getBranchStatus[P, user]"), calls);
    }

    @Test
    public void listBranches_delegates() throws Exception {
        assertEquals(Collections.emptyList(), functions().listBranches("P", "user"));
        assertEquals(Collections.singletonList("listBranches[P, user]"), calls);
    }

    @Test
    public void getCommitHistory_delegates() throws Exception {
        assertEquals(Collections.emptyList(), functions().getCommitHistory("P", "user", 25));
        assertEquals(Collections.singletonList("getCommitHistory[P, user, 25]"), calls);
    }

    @Test
    public void listRepositoryTags_delegates() throws Exception {
        assertEquals(Collections.emptyList(), functions().listRepositoryTags("P"));
        assertEquals(Collections.singletonList("listRepositoryTags[P]"), calls);
    }

    @Test
    public void getGitTrackedProjectNames_delegates() throws Exception {
        assertEquals(Collections.emptyList(), functions().getGitTrackedProjectNames());
        assertEquals(Collections.singletonList("getGitTrackedProjectNames[]"), calls);
    }

    // ---- lazy delegate resolution ----

    @Test
    public void nullDelegate_throwsIllegalState_notNullPointer() throws Exception {
        GitScriptFunctions fns = new GitScriptFunctions(() -> null);
        try {
            fns.getCurrentBranch("P");
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("gateway"));
        }
    }

    @Test
    public void delegateResolvedPerCall_notCachedAtConstruction() throws Exception {
        // Simulates Designer ordering: initializeScriptManager runs before startup()
        // populates the RPC reference. The supplier starts null and is set later.
        final GitScriptInterface[] holder = new GitScriptInterface[]{null};
        GitScriptFunctions fns = new GitScriptFunctions(() -> holder[0]);
        holder[0] = recordingDelegate;
        assertEquals("stub", fns.getCurrentBranch("P"));
        assertEquals(Collections.singletonList("getCurrentBranch[P]"), calls);
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `mvn -q test -pl git-common`
Expected: COMPILATION ERROR — `GitScriptFunctions` does not exist yet. That is the expected failure mode for this step.

- [ ] **Step 4: Implement `GitScriptFunctions`**

Create `git-common/src/main/java/com/axone_io/ignition/git/GitScriptFunctions.java`:

```java
package com.axone_io.ignition.git;

import com.inductiveautomation.ignition.common.BundleUtil;
import com.inductiveautomation.ignition.common.script.hints.ScriptArg;
import com.inductiveautomation.ignition.common.script.hints.ScriptFunction;

import java.util.List;
import java.util.function.Supplier;

/**
 * Curated {@code system.git.*} scripting facade.
 *
 * <p>Exposes ONLY tag import and read-only status functions. Write operations
 * (push, commit, switchBranch, hotfix...) are deliberately NOT exposed here —
 * they stay Designer-only where production-mode safeguards live.</p>
 *
 * <p>The delegate is resolved lazily on every call: in Designer/Client scope the
 * RPC proxy is not available until the module hook's startup() has run, which can
 * be after initializeScriptManager() registers this class.</p>
 *
 * <p>NOTE: unlike {@link GitScriptInterface}, overloads ARE allowed here. This class
 * is consumed by Jython (which resolves Java overloads), never by the RPC layer.</p>
 */
public class GitScriptFunctions {

    static {
        BundleUtil.get().addBundle(
            GitScriptFunctions.class.getSimpleName(),
            GitScriptFunctions.class.getClassLoader(),
            GitScriptFunctions.class.getName().replace('.', '/')
        );
    }

    private final Supplier<GitScriptInterface> delegateSupplier;

    public GitScriptFunctions(Supplier<GitScriptInterface> delegateSupplier) {
        this.delegateSupplier = delegateSupplier;
    }

    private GitScriptInterface delegate() {
        GitScriptInterface d = delegateSupplier.get();
        if (d == null) {
            throw new IllegalStateException(
                "system.git is not ready: no gateway connection available yet. " +
                "Ensure the Git gateway module is installed and running.");
        }
        return d;
    }

    private static String requireProject(String projectName) {
        if (projectName == null || projectName.trim().isEmpty()) {
            throw new IllegalArgumentException("projectName is required");
        }
        return projectName;
    }

    @ScriptFunction(docBundlePrefix = "GitScriptFunctions")
    public boolean importTags(@ScriptArg("projectName") String projectName) throws Exception {
        return importTags(projectName, null);
    }

    @ScriptFunction(docBundlePrefix = "GitScriptFunctions")
    public boolean importTags(@ScriptArg("projectName") String projectName,
                              @ScriptArg("collisionPolicy") String collisionPolicy) throws Exception {
        requireProject(projectName);
        return delegate().importTags(projectName, collisionPolicy);
    }

    @ScriptFunction(docBundlePrefix = "GitScriptFunctions")
    public List<UncommittedChange> getUncommittedChanges(@ScriptArg("projectName") String projectName,
                                                         @ScriptArg("userName") String userName) throws Exception {
        requireProject(projectName);
        // RPC interface name is (sic) getUncommitedChanges; fix the spelling at the script layer.
        return delegate().getUncommitedChanges(projectName, userName);
    }

    @ScriptFunction(docBundlePrefix = "GitScriptFunctions")
    public String getCurrentBranch(@ScriptArg("projectName") String projectName) throws Exception {
        requireProject(projectName);
        return delegate().getCurrentBranch(projectName);
    }

    @ScriptFunction(docBundlePrefix = "GitScriptFunctions")
    public BranchStatus getBranchStatus(@ScriptArg("projectName") String projectName,
                                        @ScriptArg("userName") String userName) throws Exception {
        requireProject(projectName);
        return delegate().getBranchStatus(projectName, userName);
    }

    @ScriptFunction(docBundlePrefix = "GitScriptFunctions")
    public List<BranchInfo> listBranches(@ScriptArg("projectName") String projectName,
                                         @ScriptArg("userName") String userName) throws Exception {
        requireProject(projectName);
        return delegate().listBranches(projectName, userName);
    }

    @ScriptFunction(docBundlePrefix = "GitScriptFunctions")
    public List<CommitInfo> getCommitHistory(@ScriptArg("projectName") String projectName,
                                             @ScriptArg("userName") String userName,
                                             @ScriptArg("maxCount") int maxCount) throws Exception {
        requireProject(projectName);
        return delegate().getCommitHistory(projectName, userName, maxCount);
    }

    @ScriptFunction(docBundlePrefix = "GitScriptFunctions")
    public List<String> listRepositoryTags(@ScriptArg("projectName") String projectName) throws Exception {
        requireProject(projectName);
        return delegate().listRepositoryTags(projectName);
    }

    @ScriptFunction(docBundlePrefix = "GitScriptFunctions")
    public List<String> getGitTrackedProjectNames() throws Exception {
        return delegate().getGitTrackedProjectNames();
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `mvn -q test -pl git-common`
Expected: BUILD SUCCESS, 12 tests pass, 0 failures.

- [ ] **Step 6: Create the autocomplete doc bundle**

Create `git-common/src/main/resources/com/axone_io/ignition/git/GitScriptFunctions.properties` (same package path the static `BundleUtil` block registers; key format matches the existing `AbstractScriptModule.properties` example: `<function>.desc`, `<function>.param.<argName>`, `<function>.returns`):

```properties
importTags.desc=Imports git-tracked tags from the project repository working tree into the gateway tag providers. WARNING: overwrites tag providers gateway-wide and affects all projects sharing those providers. Does not pull; imports whatever is on disk.
importTags.param.projectName=The name of the Ignition project whose repository tags should be imported.
importTags.param.collisionPolicy=Optional collision policy: "a" (abort), "m" (merge), "o" (overwrite). Omit or pass None to use the policy from the repository's tag_config.
importTags.returns=True if the import completed.

getUncommittedChanges.desc=Returns the list of uncommitted changes in the project repository.
getUncommittedChanges.param.projectName=The name of the Ignition project.
getUncommittedChanges.param.userName=The Ignition user the repository session is associated with.
getUncommittedChanges.returns=A list of UncommittedChange objects (path, type, actor).

getCurrentBranch.desc=Returns the currently checked-out branch of the project repository.
getCurrentBranch.param.projectName=The name of the Ignition project.
getCurrentBranch.returns=The branch name.

getBranchStatus.desc=Returns the repository state (uncommitted changes, conflicts, merge state, ahead/behind).
getBranchStatus.param.projectName=The name of the Ignition project.
getBranchStatus.param.userName=The Ignition user the repository session is associated with.
getBranchStatus.returns=A BranchStatus object.

listBranches.desc=Lists local and remote branches of the project repository.
listBranches.param.projectName=The name of the Ignition project.
listBranches.param.userName=The Ignition user the repository session is associated with.
listBranches.returns=A list of BranchInfo objects.

getCommitHistory.desc=Returns recent commits of the project repository.
getCommitHistory.param.projectName=The name of the Ignition project.
getCommitHistory.param.userName=The Ignition user the repository session is associated with.
getCommitHistory.param.maxCount=Maximum number of commits to return.
getCommitHistory.returns=A list of CommitInfo objects (hash, message, author, timestamp).

listRepositoryTags.desc=Lists git tags (releases) present in the project repository.
listRepositoryTags.param.projectName=The name of the Ignition project.
listRepositoryTags.returns=A list of tag names.

getGitTrackedProjectNames.desc=Returns the names of all Ignition projects on this gateway that are tracked by the Git module.
getGitTrackedProjectNames.returns=A list of project names.
```

- [ ] **Step 7: Run full test suite and commit**

Run: `mvn -q test`
Expected: BUILD SUCCESS, all modules green.

```bash
git add git-common/pom.xml \
        git-common/src/main/java/com/axone_io/ignition/git/GitScriptFunctions.java \
        git-common/src/main/resources/com/axone_io/ignition/git/GitScriptFunctions.properties \
        git-common/src/test/java/com/axone_io/ignition/git/GitScriptFunctionsTest.java
git commit -m "feat(scripting): add curated GitScriptFunctions facade with doc bundle (TEC-3756)"
```

---

### Task 3: Register `system.git` in Gateway scope

**Files:**
- Modify: `git-gateway/src/main/java/com/axone_io/ignition/git/GatewayHook.java`

- [ ] **Step 1: Add the registration override**

In `GatewayHook.java`, add these imports alongside the existing ones:

```java
import com.inductiveautomation.ignition.common.script.ScriptManager;
import com.inductiveautomation.ignition.common.script.hints.PropertiesFileDocProvider;
```

Then add this method after `getRpcImplementation()` (around line 48):

```java
    @Override
    public void initializeScriptManager(ScriptManager manager) {
        super.initializeScriptManager(manager);

        // Curated scripting surface: tag import + read-only status only.
        // Enables Gateway Event Scripts (Startup/Timer) to call system.git.*.
        manager.addScriptModule(
                "system.git",
                new GitScriptFunctions(() -> scriptModule),
                new PropertiesFileDocProvider()
        );
    }
```

The supplier defers to the `scriptModule` field populated in `setup()`; `GitScriptFunctions` already throws a clear `IllegalStateException` if it is somehow called first.

- [ ] **Step 2: Verify it compiles**

Run: `mvn -q compile -pl git-gateway`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add git-gateway/src/main/java/com/axone_io/ignition/git/GatewayHook.java
git commit -m "feat(scripting): register system.git in gateway scope (TEC-3756)"
```

---

### Task 4: Register `system.git` in Designer and Vision Client scopes

**Files:**
- Modify: `git-designer/src/main/java/com/axone_io/ignition/git/DesignerHook.java:51-59`
- Modify: `git-client/src/main/java/com/axone_io/ignition/git/ClientHook.java`

- [ ] **Step 1: DesignerHook — replace the commented-out block**

In `DesignerHook.java`, the current `initializeScriptManager` (lines 51–59) contains a stale commented-out registration (`new ClientScriptModule()` no longer even compiles — the constructor now requires an RPC interface). Replace the whole method body with:

```java
    @Override
    public void initializeScriptManager(ScriptManager manager) {
        super.initializeScriptManager(manager);

        // The static rpc field is populated in startup(), which can run AFTER this
        // method — GitScriptFunctions resolves the supplier lazily on each call.
        manager.addScriptModule(
                "system.git",
                new GitScriptFunctions(() -> rpc),
                new PropertiesFileDocProvider()
        );
    }
```

`DesignerHook` already imports `ScriptManager`; add the missing import if not present:

```java
import com.inductiveautomation.ignition.common.script.hints.PropertiesFileDocProvider;
```

- [ ] **Step 2: ClientHook — replace the commented-out block with a lazy RPC proxy**

Replace the entire contents of `git-client/src/main/java/com/axone_io/ignition/git/ClientHook.java` with:

```java
package com.axone_io.ignition.git;

import com.inductiveautomation.ignition.client.gateway_interface.GatewayConnection;
import com.inductiveautomation.ignition.common.rpc.proto.ProtoRpcSerializer;
import com.inductiveautomation.ignition.common.script.ScriptManager;
import com.inductiveautomation.ignition.common.script.hints.PropertiesFileDocProvider;
import com.inductiveautomation.vision.api.client.AbstractClientModuleHook;

public class ClientHook extends AbstractClientModuleHook {

    private volatile GitScriptInterface rpc;

    // Lazily acquire the RPC proxy: the gateway connection is not available when
    // initializeScriptManager runs, only once the client session is up.
    private GitScriptInterface rpc() {
        GitScriptInterface local = rpc;
        if (local == null) {
            synchronized (this) {
                local = rpc;
                if (local == null) {
                    rpc = local = GatewayConnection.getRpcInterface(
                            ProtoRpcSerializer.DEFAULT_INSTANCE,
                            "com.axone_io.ignition.git",
                            GitScriptInterface.class
                    );
                }
            }
        }
        return local;
    }

    @Override
    public void initializeScriptManager(ScriptManager manager) {
        super.initializeScriptManager(manager);

        manager.addScriptModule(
                "system.git",
                new GitScriptFunctions(this::rpc),
                new PropertiesFileDocProvider()
        );
    }

}
```

Note: `GatewayConnection.getRpcInterface(...)` is the same 8.3 pattern `DesignerHook.startup()` uses (DesignerHook.java:70-74). If it throws because no connection exists yet, the exception propagates to the calling script — acceptable and self-describing.

- [ ] **Step 3: Verify everything compiles**

Run: `mvn -q compile`
Expected: BUILD SUCCESS across all modules.

- [ ] **Step 4: Run full test suite**

Run: `mvn -q test`
Expected: BUILD SUCCESS, no regressions.

- [ ] **Step 5: Commit**

```bash
git add git-designer/src/main/java/com/axone_io/ignition/git/DesignerHook.java \
        git-client/src/main/java/com/axone_io/ignition/git/ClientHook.java
git commit -m "feat(scripting): register system.git in designer and client scopes (TEC-3756)"
```

---

### Task 5: Documentation + full module build

**Files:**
- Create: `docs/scripting-api.md`
- Modify: `CLAUDE.md` (Key Classes table)

- [ ] **Step 1: Write `docs/scripting-api.md`**

```markdown
# Scripting API (`system.git.*`)

The Git module exposes a curated scripting API in all scopes: **Gateway**
(Gateway Event Scripts — Startup, Timer), **Designer** (Script Console), and
**Vision Client**.

Only tag import and read-only status functions are exposed. Write operations
(commit, push, branch switching, hotfix) are deliberately Designer-only, where
production-mode safeguards apply.

## Functions

### `system.git.importTags(projectName, [collisionPolicy])`

Imports git-tracked tags from the project repository's working tree into the
gateway tag providers. Does **not** pull — it imports whatever is on disk.

> **Warning:** Tag providers are gateway-wide. Importing overwrites provider
> contents and affects **all** projects sharing those providers. If multiple
> projects track the same provider, only the authoritative project should
> import (last writer wins).
>
> A scripted import bypasses the production-mode Designer checklist. This is
> acceptable because it is a local working-tree → provider operation that never
> touches git history, but treat it with the same care as a Designer-side
> import.

| Parameter | Type | Description |
|---|---|---|
| `projectName` | str | Ignition project whose repo tags to import |
| `collisionPolicy` | str (optional) | `"a"` abort, `"m"` merge, `"o"` overwrite. Defaults to the repo's `tag_config` policy |

Returns `True` on completion.

```python
# Gateway Startup event script: re-import tags after a gateway restart
system.git.importTags("MyProject")

# Overwrite explicitly
system.git.importTags("MyProject", "o")
```

### Read-only status functions

| Function | Returns |
|---|---|
| `system.git.getUncommittedChanges(projectName, userName)` | List of UncommittedChange (path, type, actor) |
| `system.git.getCurrentBranch(projectName)` | Current branch name (str) |
| `system.git.getBranchStatus(projectName, userName)` | BranchStatus (changes, conflicts, merge state, ahead/behind) |
| `system.git.listBranches(projectName, userName)` | List of BranchInfo |
| `system.git.getCommitHistory(projectName, userName, maxCount)` | List of CommitInfo |
| `system.git.listRepositoryTags(projectName)` | List of git tag names (str) |
| `system.git.getGitTrackedProjectNames()` | List of git-tracked project names (str) |

```python
# Example: log repo status from a gateway timer script
for project in system.git.getGitTrackedProjectNames():
    branch = system.git.getCurrentBranch(project)
    changes = system.git.getUncommittedChanges(project, "admin")
    system.util.getLogger("git-status").info(
        "%s on %s: %d uncommitted change(s)" % (project, branch, len(changes)))
```

## Errors

- Calling any function before the gateway connection is ready raises
  `IllegalStateException` ("system.git is not ready...").
- A missing/blank `projectName` raises `IllegalArgumentException`.
- Gateway-side failures propagate to the calling script as exceptions.
```

- [ ] **Step 2: Add `GitScriptFunctions` to the CLAUDE.md Key Classes table**

In `CLAUDE.md`, in the Key Classes table, after the `GitActionManager` row, add:

```markdown
| `GitScriptFunctions` | git-common | Curated `system.git.*` scripting facade (tag import + read-only status) |
```

Also add a cross-reference line at the end of the "RPC Considerations > Method Signatures" section:

```markdown
- The `GitScriptFunctions` facade (`system.git.*`) MAY use overloads — it is consumed by Jython, never by RPC.
```

- [ ] **Step 3: Full build verification**

Run: `mvn clean package -DskipTests`
Expected: BUILD SUCCESS; `git-build/target/Git-unsigned.modl` produced.

Run: `mvn -q test`
Expected: BUILD SUCCESS, all tests green.

- [ ] **Step 4: Commit**

```bash
git add docs/scripting-api.md CLAUDE.md
git commit -m "docs: add system.git scripting API reference (TEC-3756)"
```

---

### Task 6: Manual verification (hot deploy — requires Docker gateway)

This task cannot be automated in this repo; perform against the local `whk-services` stack. Record results in the PR description.

- [ ] **Step 1: Hot-deploy the module**

```bash
mvn clean package -DskipTests && \
cp git-build/target/Git-unsigned.modl $DEPLOYMENTS_PATH/gw-build/modules/ && \
docker cp git-build/target/Git-unsigned.modl whk-services-ignition-1:/usr/local/bin/ignition/user-lib/modules/ && \
cd $DEPLOYMENTS_PATH && docker compose restart ignition
```

- [ ] **Step 2: Designer Script Console** (restart Designer first to pick up the new module)
  - `system.git.getGitTrackedProjectNames()` returns project list
  - `system.git.getCurrentBranch("<project>")` returns branch
  - `system.git.importTags("<project>")` imports; check `docker logs whk-services-ignition-1 | grep GitTagManager`
  - Autocomplete shows `system.git.*` with descriptions

- [ ] **Step 3: Gateway Event Script**
  - Add a Gateway Timer script (one-shot) calling `system.git.importTags("<project>")`; verify via gateway logs
  - Remove the test script afterwards

- [ ] **Step 4: Error paths**
  - `system.git.importTags("")` raises `IllegalArgumentException`
  - `system.git.importTags("NoSuchProject")` — confirm the gateway-side error propagates to the Script Console

---

## Self-Review Notes

- **Spec coverage:** importTags + 8 facade functions (ticket table) ✔; all-scope registration ✔ (Tasks 3–4); typo fix at script layer ✔ (Task 2); doc bundle/autocomplete ✔ (Task 2 Step 6); no write ops beyond importTags ✔ (facade only); docs + production-mode note ✔ (Task 5).
- **Type consistency:** facade signatures match `GitScriptInterface` (verified against source); `importTags(String, String)` consistent across all 4 chain classes and facade.
- **Known judgment calls:** facade overloads `importTags` (allowed — not RPC); Vision client acquires RPC lazily because no connection exists at registration time; `getGitTrackedProjectNames` declared `throws Exception` on the facade for uniformity even though the interface method doesn't throw (callers are Jython; harmless).
