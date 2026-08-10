package com.axone_io.ignition.git.dto;

import java.io.Serializable;

/**
 * Summary of how far a project's working tree has drifted from HEAD.
 *
 * <p>{@code revision} is a stable hash of the current change set, not a counter. Any file
 * appearing, changing, or reverting produces a different revision, whatever caused it —
 * which is what lets the Designer distinguish "new changes since the user dismissed the
 * prompt" from "the same changes they already declined to commit".</p>
 *
 * <p>{@code known} distinguishes "the working tree is clean" from "the status read failed".
 * It defaults to {@code false}, which is also the correct deserialization default: a state
 * that never had a successful read behind it must not be believed.</p>
 */
public class RepoDirtyState implements Serializable {
    private static final long serialVersionUID = 1L;

    private long revision;
    private boolean dirty;
    private int projectChangeCount;
    private int tagChangeCount;
    private boolean productionMode;
    private boolean known;

    /** Default constructor required for serialization. */
    public RepoDirtyState() {
        this.revision = 0L;
        this.dirty = false;
        this.projectChangeCount = 0;
        this.tagChangeCount = 0;
        this.productionMode = false;
        this.known = false;
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

    /** Whether the working-tree status was actually read; {@code false} means "unknown". */
    public boolean isKnown() {
        return known;
    }

    public void setKnown(boolean known) {
        this.known = known;
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
                ", productionMode=" + productionMode +
                ", known=" + known + '}';
    }
}
