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
