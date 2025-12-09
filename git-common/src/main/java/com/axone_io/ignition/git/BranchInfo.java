package com.axone_io.ignition.git;

import java.io.Serializable;

/**
 * Data transfer object for branch information in Git repository.
 * Used for RPC communication between Gateway and Designer.
 */
public class BranchInfo implements Serializable {
    private static final long serialVersionUID = 1L;

    private String name;
    private boolean isLocal;
    private boolean isRemote;
    private boolean isCurrent;
    private int commitsAhead;
    private int commitsBehind;
    private String fullName; // Full ref name (e.g., refs/heads/main or refs/remotes/origin/main)

    // Default constructor required for serialization
    public BranchInfo() {
    }

    public BranchInfo(String name, boolean isLocal, boolean isRemote, boolean isCurrent,
                     int commitsAhead, int commitsBehind, String fullName) {
        this.name = name;
        this.isLocal = isLocal;
        this.isRemote = isRemote;
        this.isCurrent = isCurrent;
        this.commitsAhead = commitsAhead;
        this.commitsBehind = commitsBehind;
        this.fullName = fullName;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isLocal() {
        return isLocal;
    }

    public void setLocal(boolean local) {
        isLocal = local;
    }

    public boolean isRemote() {
        return isRemote;
    }

    public void setRemote(boolean remote) {
        isRemote = remote;
    }

    public boolean isCurrent() {
        return isCurrent;
    }

    public void setCurrent(boolean current) {
        isCurrent = current;
    }

    public int getCommitsAhead() {
        return commitsAhead;
    }

    public void setCommitsAhead(int commitsAhead) {
        this.commitsAhead = commitsAhead;
    }

    public int getCommitsBehind() {
        return commitsBehind;
    }

    public void setCommitsBehind(int commitsBehind) {
        this.commitsBehind = commitsBehind;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    /**
     * Returns a display name for the branch based on its type.
     * For remote branches, strips the "origin/" prefix.
     */
    public String getDisplayName() {
        if (isRemote && name.startsWith("origin/")) {
            return name.substring(7); // Remove "origin/" prefix
        }
        return name;
    }

    /**
     * Returns a status string indicating if the branch is ahead/behind.
     */
    public String getStatusString() {
        if (commitsAhead > 0 && commitsBehind > 0) {
            return String.format("↑%d ↓%d", commitsAhead, commitsBehind);
        } else if (commitsAhead > 0) {
            return String.format("↑%d", commitsAhead);
        } else if (commitsBehind > 0) {
            return String.format("↓%d", commitsBehind);
        }
        return "";
    }

    @Override
    public String toString() {
        return "BranchInfo{" +
                "name='" + name + '\'' +
                ", isLocal=" + isLocal +
                ", isRemote=" + isRemote +
                ", isCurrent=" + isCurrent +
                ", commitsAhead=" + commitsAhead +
                ", commitsBehind=" + commitsBehind +
                '}';
    }
}
