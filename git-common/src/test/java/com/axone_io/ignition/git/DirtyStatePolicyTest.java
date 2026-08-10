package com.axone_io.ignition.git;

import com.axone_io.ignition.git.dto.RepoDirtyState;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DirtyStatePolicyTest {

    /** A state the gateway read successfully — the ordinary case. */
    private static RepoDirtyState state(long revision, boolean dirty, int tags, int project) {
        RepoDirtyState s = new RepoDirtyState();
        s.setRevision(revision);
        s.setDirty(dirty);
        s.setTagChangeCount(tags);
        s.setProjectChangeCount(project);
        s.setKnown(true);
        return s;
    }

    /** What the gateway returns when the status read failed: clean-looking but not known. */
    private static RepoDirtyState unknownState() {
        return new RepoDirtyState();
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
                DirtyStatePolicy.NEVER_DISMISSED, false));
    }

    @Test
    public void badgeShown_afterDismissalWhileStillDirty() {
        assertTrue(DirtyStatePolicy.shouldShowBadge(state(77L, true, 3, 0), 77L, false));
    }

    @Test
    public void badgeHidden_onceTreeIsClean() {
        assertFalse(DirtyStatePolicy.shouldShowBadge(state(0L, false, 0, 0), 77L, true));
    }

    @Test
    public void badgeHidden_whenStateUnknown() {
        assertFalse(DirtyStatePolicy.shouldShowBadge(null, 77L, false));
    }

    @Test
    public void unknownState_holdsBadgeThatWasShowing() {
        // A failed git status must not retract a warning: the user would read the badge
        // vanishing as "my changes were committed".
        assertTrue(DirtyStatePolicy.shouldShowBadge(unknownState(), 77L, true));
    }

    @Test
    public void unknownState_doesNotRaiseBadgeThatWasHidden() {
        assertFalse(DirtyStatePolicy.shouldShowBadge(unknownState(), 77L, false));
    }

    @Test
    public void unknownState_holdsBadgeEvenBeforeAnyDismissal() {
        assertTrue(DirtyStatePolicy.shouldShowBadge(
                unknownState(), DirtyStatePolicy.NEVER_DISMISSED, true));
    }

    @Test
    public void unknownState_neverOpensADialog() {
        RepoDirtyState s = unknownState();
        s.setDirty(true);
        s.setRevision(99L);
        assertEquals(DirtyStatePolicy.Action.NOTHING,
                DirtyStatePolicy.decide(s, DirtyStatePolicy.NEVER_DISMISSED, false));
    }

    @Test
    public void freshlyDeserializedState_isNotKnown() {
        assertFalse(new RepoDirtyState().isKnown());
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

    // ---------- dismissal armed by a production save ----------
    //
    // A production save shows the user the drift in the safety checklist before writing, so the
    // poller must not immediately re-prompt for it. The revision is a hash of the change set and
    // the resources are written after the checklist is accepted, so the revision to dismiss does
    // not exist until the next poll — the dismissal is armed at save time and applied here.

    @Test
    public void armedDismissal_adoptsTheRevisionOfTheStateItSees() {
        assertEquals(77L, DirtyStatePolicy.resolveDismissedRevision(
                state(77L, true, 1, 0), DirtyStatePolicy.NEVER_DISMISSED, true));
    }

    @Test
    public void armedDismissal_ignoresACleanTree() {
        assertEquals("nothing was saved that needs dismissing", 5L,
                DirtyStatePolicy.resolveDismissedRevision(state(77L, false, 0, 0), 5L, true));
    }

    @Test
    public void armedDismissal_ignoresAnUnknownState() {
        assertEquals("an unreadable working tree is not evidence the save landed", 5L,
                DirtyStatePolicy.resolveDismissedRevision(unknownState(), 5L, true));
    }

    @Test
    public void armedDismissal_ignoresANullState() {
        assertEquals(5L, DirtyStatePolicy.resolveDismissedRevision(null, 5L, true));
    }

    @Test
    public void unarmed_neverChangesTheDismissedRevision() {
        assertEquals("an ordinary save must still raise the commit prompt", 5L,
                DirtyStatePolicy.resolveDismissedRevision(state(77L, true, 1, 0), 5L, false));
    }

    @Test
    public void armedDismissal_thenDecide_yieldsBadgeNotPrompt() {
        RepoDirtyState postSave = state(77L, true, 3, 0);
        long dismissed = DirtyStatePolicy.resolveDismissedRevision(
                postSave, DirtyStatePolicy.NEVER_DISMISSED, true);

        assertEquals("the save the user just authorised must not re-prompt",
                DirtyStatePolicy.Action.BADGE_ONLY,
                DirtyStatePolicy.decide(postSave, dismissed, false));
    }

    @Test
    public void armedDismissal_doesNotSuppressTheNextUnrelatedChange() {
        RepoDirtyState postSave = state(77L, true, 3, 0);
        long dismissed = DirtyStatePolicy.resolveDismissedRevision(
                postSave, DirtyStatePolicy.NEVER_DISMISSED, true);

        RepoDirtyState laterEdit = state(78L, true, 1, 0);
        assertEquals("drift after the save is new and must prompt",
                DirtyStatePolicy.Action.PROMPT,
                DirtyStatePolicy.decide(laterEdit, dismissed, false));
    }
}
