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
        // An unknown state is not evidence of anything; never raise a dialog on it.
        if (state == null || !state.isKnown() || !state.isDirty()) {
            return Action.NOTHING;
        }
        if (promptOpen) {
            return Action.NOTHING;
        }
        return dismissedRevision == NEVER_DISMISSED || state.getRevision() != dismissedRevision
                ? Action.PROMPT
                : Action.BADGE_ONLY;
    }

    /**
     * The badge marks changes the user has seen and declined, and survives until committed.
     *
     * <p>A state whose status read failed is not "clean" — it is unknown, and retracting the
     * badge on it would read to the user as "your changes were committed". On an unknown
     * state the badge is held at whatever it already was until a successful read says
     * otherwise, which is why the caller passes {@code currentlyShowing}.</p>
     *
     * @param currentlyShowing whether the badge is on screen right now
     */
    public static boolean shouldShowBadge(RepoDirtyState state,
                                          long dismissedRevision,
                                          boolean currentlyShowing) {
        if (state != null && !state.isKnown()) {
            return currentlyShowing;
        }
        return state != null
                && state.isDirty()
                && dismissedRevision != NEVER_DISMISSED
                && state.getRevision() == dismissedRevision;
    }
}
