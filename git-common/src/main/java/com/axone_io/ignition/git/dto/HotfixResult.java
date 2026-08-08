package com.axone_io.ignition.git.dto;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * DTO for hotfix pipeline result. Travels over RPC between gateway and designer.
 * Each step has a status that the Designer polls to update the progress dialog.
 */
public class HotfixResult implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum StepStatus { PENDING, IN_PROGRESS, COMPLETED, FAILED, SKIPPED }

    public enum Step {
        CREATE_BRANCH("Creating hotfix branch"),
        SWITCH_BRANCH("Switching to hotfix branch"),
        COMMIT("Committing changes"),
        PUSH("Pushing to remote"),
        CREATE_PR("Creating pull request"),
        // Not "main": the production branch is configurable, and hardcoding a name produced
        // self-contradicting rows like "Switching back to main — Switched to master". The
        // actual branch already appears in each step's message.
        SWITCH_BACK("Switching back to the production branch"),
        MERGE_LOCAL("Merging hotfix into the local production branch"),
        CLEANUP("Cleaning up hotfix branch");

        private final String displayName;
        Step(String displayName) { this.displayName = displayName; }
        public String getDisplayName() { return displayName; }
    }

    private StepStatus[] stepStatuses;
    private String[] stepMessages;
    private boolean pipelineComplete;
    private boolean pipelineSuccess;
    private String hotfixBranch;
    private String commitHash;
    private String prUrl;
    private int prNumber;
    private String errorMessage;

    public HotfixResult() {
        int stepCount = Step.values().length;
        this.stepStatuses = new StepStatus[stepCount];
        this.stepMessages = new String[stepCount];
        for (int i = 0; i < stepCount; i++) {
            this.stepStatuses[i] = StepStatus.PENDING;
            this.stepMessages[i] = "";
        }
        this.pipelineComplete = false;
        this.pipelineSuccess = false;
    }

    public void updateStep(Step step, StepStatus status, String message) {
        int idx = step.ordinal();
        this.stepStatuses[idx] = status;
        this.stepMessages[idx] = message != null ? message : "";
    }

    public StepStatus getStepStatus(Step step) {
        return stepStatuses[step.ordinal()];
    }

    public String getStepMessage(Step step) {
        return stepMessages[step.ordinal()];
    }

    public boolean hasFailures() {
        for (StepStatus status : stepStatuses) {
            if (status == StepStatus.FAILED) return true;
        }
        return false;
    }

    public boolean isPipelineComplete() { return pipelineComplete; }
    public void setPipelineComplete(boolean pipelineComplete) { this.pipelineComplete = pipelineComplete; }
    public boolean isPipelineSuccess() { return pipelineSuccess; }
    public void setPipelineSuccess(boolean pipelineSuccess) { this.pipelineSuccess = pipelineSuccess; }
    public String getHotfixBranch() { return hotfixBranch; }
    public void setHotfixBranch(String hotfixBranch) { this.hotfixBranch = hotfixBranch; }
    public String getCommitHash() { return commitHash; }
    public void setCommitHash(String commitHash) { this.commitHash = commitHash; }
    public String getPrUrl() { return prUrl; }
    public void setPrUrl(String prUrl) { this.prUrl = prUrl; }
    public int getPrNumber() { return prNumber; }
    public void setPrNumber(int prNumber) { this.prNumber = prNumber; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
