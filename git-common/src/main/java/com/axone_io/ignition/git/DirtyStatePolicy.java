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
