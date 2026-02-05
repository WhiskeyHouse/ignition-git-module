package com.axone_io.ignition.git;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Data transfer object for branch status information in Git repository.
 * Contains information about uncommitted changes and unpushed commits
 * to help users make informed decisions before switching branches.
 * Used for RPC communication between Gateway and Designer.
 */
public class BranchStatus implements Serializable {
    private static final long serialVersionUID = 1L;

    private boolean hasUncommittedChanges;
    private int unpushedCommits;
    private List<String> uncommittedFiles;
    private List<String> untrackedFiles;
    private List<String> conflictingFiles;
    private String currentBranch;
    private boolean isMerging;

    // Default constructor required for serialization
    public BranchStatus() {
        this.uncommittedFiles = new ArrayList<>();
        this.untrackedFiles = new ArrayList<>();
        this.conflictingFiles = new ArrayList<>();
        this.isMerging = false;
    }

    public BranchStatus(boolean hasUncommittedChanges, int unpushedCommits,
                       List<String> uncommittedFiles, List<String> untrackedFiles,
                       String currentBranch) {
        this(hasUncommittedChanges, unpushedCommits, uncommittedFiles, untrackedFiles, new ArrayList<>(), false, currentBranch);
    }

    public BranchStatus(boolean hasUncommittedChanges, int unpushedCommits,
                       List<String> uncommittedFiles, List<String> untrackedFiles,
                       List<String> conflictingFiles, String currentBranch) {
        this(hasUncommittedChanges, unpushedCommits, uncommittedFiles, untrackedFiles, conflictingFiles, false, currentBranch);
    }

    public BranchStatus(boolean hasUncommittedChanges, int unpushedCommits,
                       List<String> uncommittedFiles, List<String> untrackedFiles,
                       List<String> conflictingFiles, boolean isMerging, String currentBranch) {
        this.hasUncommittedChanges = hasUncommittedChanges;
        this.unpushedCommits = unpushedCommits;
        this.uncommittedFiles = uncommittedFiles != null ? uncommittedFiles : new ArrayList<>();
        this.untrackedFiles = untrackedFiles != null ? untrackedFiles : new ArrayList<>();
        this.conflictingFiles = conflictingFiles != null ? conflictingFiles : new ArrayList<>();
        this.isMerging = isMerging;
        this.currentBranch = currentBranch;
    }

    public boolean hasUncommittedChanges() {
        return hasUncommittedChanges;
    }

    public void setHasUncommittedChanges(boolean hasUncommittedChanges) {
        this.hasUncommittedChanges = hasUncommittedChanges;
    }

    public int getUnpushedCommits() {
        return unpushedCommits;
    }

    public void setUnpushedCommits(int unpushedCommits) {
        this.unpushedCommits = unpushedCommits;
    }

    public List<String> getUncommittedFiles() {
        return uncommittedFiles;
    }

    public void setUncommittedFiles(List<String> uncommittedFiles) {
        this.uncommittedFiles = uncommittedFiles;
    }

    public List<String> getUntrackedFiles() {
        return untrackedFiles;
    }

    public void setUntrackedFiles(List<String> untrackedFiles) {
        this.untrackedFiles = untrackedFiles;
    }

    public String getCurrentBranch() {
        return currentBranch;
    }

    public void setCurrentBranch(String currentBranch) {
        this.currentBranch = currentBranch;
    }

    public List<String> getConflictingFiles() {
        return conflictingFiles;
    }

    public void setConflictingFiles(List<String> conflictingFiles) {
        this.conflictingFiles = conflictingFiles;
    }

    public boolean hasConflicts() {
        return conflictingFiles != null && !conflictingFiles.isEmpty();
    }

    public boolean isMerging() {
        return isMerging;
    }

    public void setMerging(boolean merging) {
        isMerging = merging;
    }

    /**
     * Returns true if there are any issues that should warn the user before switching branches.
     */
    public boolean hasWarnings() {
        return hasUncommittedChanges || unpushedCommits > 0 || hasConflicts() || isMerging;
    }

    /**
     * Returns a formatted warning message for display in UI.
     */
    public String getWarningMessage() {
        StringBuilder message = new StringBuilder();

        if (hasConflicts()) {
            message.append("MERGE CONFLICTS: You have ").append(conflictingFiles.size())
                   .append(" file(s) with unresolved merge conflicts.\n");
            message.append("You must resolve these conflicts before committing.\n\n");

            if (conflictingFiles.size() <= 10) {
                for (String file : conflictingFiles) {
                    message.append("  Conflict: ").append(file).append("\n");
                }
            }
        }

        if (hasUncommittedChanges) {
            int totalFiles = uncommittedFiles.size() + untrackedFiles.size();
            if (message.length() > 0) {
                message.append("\n");
            }
            message.append("You have ").append(totalFiles).append(" uncommitted file(s).\n");

            if (totalFiles <= 10) {
                for (String file : uncommittedFiles) {
                    message.append("  Modified: ").append(file).append("\n");
                }
                for (String file : untrackedFiles) {
                    message.append("  Untracked: ").append(file).append("\n");
                }
            }
        }

        if (unpushedCommits > 0) {
            if (message.length() > 0) {
                message.append("\n");
            }
            message.append("You have ").append(unpushedCommits)
                   .append(" unpushed commit(s) on branch '").append(currentBranch).append("'.\n");
        }

        return message.toString();
    }

    @Override
    public String toString() {
        return "BranchStatus{" +
                "hasUncommittedChanges=" + hasUncommittedChanges +
                ", unpushedCommits=" + unpushedCommits +
                ", uncommittedFiles=" + uncommittedFiles.size() +
                ", untrackedFiles=" + untrackedFiles.size() +
                ", currentBranch='" + currentBranch + '\'' +
                '}';
    }
}
