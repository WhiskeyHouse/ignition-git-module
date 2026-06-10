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
    public void nullSupplier_failsFastAtConstruction() {
        try {
            new GitScriptFunctions(null);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            assertTrue(expected.getMessage().contains("delegateSupplier"));
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
