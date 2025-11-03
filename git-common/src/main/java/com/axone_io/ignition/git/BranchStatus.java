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
    private String currentBranch;

    // Default constructor required for serialization
    public BranchStatus() {
        this.uncommittedFiles = new ArrayList<>();
        this.untrackedFiles = new ArrayList<>();
    }

    public BranchStatus(boolean hasUncommittedChanges, int unpushedCommits,
                       List<String> uncommittedFiles, List<String> untrackedFiles,
                       String currentBranch) {
        this.hasUncommittedChanges = hasUncommittedChanges;
        this.unpushedCommits = unpushedCommits;
        this.uncommittedFiles = uncommittedFiles != null ? uncommittedFiles : new ArrayList<>();
        this.untrackedFiles = untrackedFiles != null ? untrackedFiles : new ArrayList<>();
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

    /**
     * Returns true if there are any issues that should warn the user before switching branches.
     */
    public boolean hasWarnings() {
        return hasUncommittedChanges || unpushedCommits > 0;
    }

    /**
     * Returns a formatted warning message for display in UI.
     */
    public String getWarningMessage() {
        StringBuilder message = new StringBuilder();

        if (hasUncommittedChanges) {
            int totalFiles = uncommittedFiles.size() + untrackedFiles.size();
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
