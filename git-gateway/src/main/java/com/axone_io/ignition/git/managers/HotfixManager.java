package com.axone_io.ignition.git.managers;

import com.axone_io.ignition.git.dto.HotfixResult;
import com.axone_io.ignition.git.dto.HotfixResult.Step;
import com.axone_io.ignition.git.dto.HotfixResult.StepStatus;
import com.axone_io.ignition.git.records.GitProjectsConfigRecord;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates the hotfix pipeline: branch, commit, push, PR, merge, cleanup.
 * Steps 1-3 are critical (rollback on failure). Steps 4-8 are best-effort.
 */
public class HotfixManager {
    private static final Logger logger = LoggerFactory.getLogger(HotfixManager.class);

    /** In-flight hotfix results, keyed by project name. Polled by Designer for progress. */
    private static final Map<String, HotfixResult> activeHotfixes = new ConcurrentHashMap<>();

    public static HotfixResult getActiveHotfix(String projectName) {
        return activeHotfixes.get(projectName);
    }

    /**
     * Execute the full hotfix pipeline. Called from GatewayScriptModule on a background thread.
     */
    public static HotfixResult execute(Git git, String projectName, String userName,
                                       String hotfixDescription, String commitMessage,
                                       String[] changes, String userEmail,
                                       String repoUri, String token, String gatewayName,
                                       String productionBranch,
                                       GitProjectsConfigRecord configRecord,
                                       com.inductiveautomation.ignition.gateway.localdb.persistence.PersistenceInterface persistence) {
        HotfixResult result = new HotfixResult();
        String branchName = "hotfix/" + GitHubApiManager.sanitizeBranchName(hotfixDescription);
        result.setHotfixBranch(branchName);
        activeHotfixes.put(projectName, result);

        logger.info("[Production Hotfix] INITIATED by '{}' on project '{}': {}", userName, projectName, branchName);

        try {
            // Step 1: Create hotfix branch
            result.updateStep(Step.CREATE_BRANCH, StepStatus.IN_PROGRESS, "");
            try {
                createHotfixBranch(git, GitHubApiManager.sanitizeBranchName(hotfixDescription));
                result.updateStep(Step.CREATE_BRANCH, StepStatus.COMPLETED, "Created " + branchName);
                logger.info("[Production Hotfix] Branch created: {}", branchName);
            } catch (Exception e) {
                result.updateStep(Step.CREATE_BRANCH, StepStatus.FAILED, e.getMessage());
                logger.error("[Production Hotfix] Failed to create branch: {}", branchName, e);
                failPipeline(result, "Failed to create hotfix branch: " + e.getMessage());
                return result;
            }

            // Step 2: Switch to hotfix branch (already done in createHotfixBranch)
            result.updateStep(Step.SWITCH_BRANCH, StepStatus.COMPLETED, "Switched to " + branchName);

            // Step 3: Commit changes
            result.updateStep(Step.COMMIT, StepStatus.IN_PROGRESS, "");
            try {
                String hash = commitHotfix(git, changes, commitMessage, userEmail);
                result.setCommitHash(hash);
                result.updateStep(Step.COMMIT, StepStatus.COMPLETED, "Committed " + changes.length + " changes (" + hash + ")");
                logger.info("[Production Hotfix] Committed: {} \"{}\"", hash, commitMessage);
            } catch (Exception e) {
                result.updateStep(Step.COMMIT, StepStatus.FAILED, e.getMessage());
                logger.error("[Production Hotfix] Commit failed, rolling back", e);
                rollback(git, branchName, productionBranch);
                failPipeline(result, "Commit failed: " + e.getMessage());
                return result;
            }

            // --- Beyond this point, failures are best-effort (commit exists locally) ---

            // Step 4: Push hotfix branch
            result.updateStep(Step.PUSH, StepStatus.IN_PROGRESS, "");
            boolean pushSucceeded = false;
            try {
                String remoteName = GitManager.getRemoteName(git);
                RefSpec refSpec = new RefSpec(
                    Constants.R_HEADS + branchName + ":" + Constants.R_HEADS + branchName
                );
                var pushCmd = git.push().setRemote(remoteName).setRefSpecs(refSpec);
                GitManager.setAuthentication(pushCmd, projectName, userName);
                Iterable<PushResult> pushResults = pushCmd.call();

                pushSucceeded = true;
                result.updateStep(Step.PUSH, StepStatus.COMPLETED, "Pushed to " + remoteName);
                logger.info("[Production Hotfix] Pushed to remote");
            } catch (Exception e) {
                result.updateStep(Step.PUSH, StepStatus.FAILED, e.getMessage());
                logger.error("[Production Hotfix] Push FAILED: {}", e.getMessage());
            }

            // Step 5: Create PR (only if push succeeded)
            if (pushSucceeded && repoUri != null && token != null) {
                result.updateStep(Step.CREATE_PR, StepStatus.IN_PROGRESS, "");
                try {
                    String prTitle = "\uD83D\uDE91 HOTFIX: " + hotfixDescription;
                    String prBody = GitHubApiManager.buildPrBody(
                        userName, gatewayName, projectName,
                        branchName, result.getCommitHash(), commitMessage, changes
                    );

                    GitHubApiManager.CreatePrResult prResult = GitHubApiManager.createPullRequest(
                        repoUri, token, branchName, productionBranch, prTitle, prBody
                    );

                    if (prResult.isSuccess()) {
                        result.setPrUrl(prResult.getPrUrl());
                        result.setPrNumber(prResult.getPrNumber());
                        result.updateStep(Step.CREATE_PR, StepStatus.COMPLETED,
                            "PR #" + prResult.getPrNumber() + " created");
                        logger.info("[Production Hotfix] PR #{} created: {}", prResult.getPrNumber(), prResult.getPrUrl());
                    } else {
                        result.updateStep(Step.CREATE_PR, StepStatus.FAILED, prResult.getError());
                        logger.error("[Production Hotfix] PR creation failed: {}", prResult.getError());
                    }
                } catch (Exception e) {
                    result.updateStep(Step.CREATE_PR, StepStatus.FAILED, e.getMessage());
                    logger.error("[Production Hotfix] PR creation error", e);
                }
            } else if (!pushSucceeded) {
                result.updateStep(Step.CREATE_PR, StepStatus.SKIPPED, "Push failed — skipping PR creation");
                logger.warn("[Production Hotfix] PR creation SKIPPED (push failed)");
            }

            // Step 6: Switch back to main
            result.updateStep(Step.SWITCH_BACK, StepStatus.IN_PROGRESS, "");
            try {
                git.checkout().setName(productionBranch).call();
                result.updateStep(Step.SWITCH_BACK, StepStatus.COMPLETED, "Switched to " + productionBranch);
            } catch (Exception e) {
                result.updateStep(Step.SWITCH_BACK, StepStatus.FAILED, e.getMessage());
                logger.error("[Production Hotfix] Failed to switch back to {}", productionBranch, e);
            }

            // Step 7: Merge hotfix into local main
            result.updateStep(Step.MERGE_LOCAL, StepStatus.IN_PROGRESS, "");
            try {
                mergeHotfixIntoMain(git, branchName, productionBranch);
                result.updateStep(Step.MERGE_LOCAL, StepStatus.COMPLETED, "Merged " + branchName + " into " + productionBranch);
                logger.info("[Production Hotfix] Local merge to {} completed", productionBranch);
            } catch (Exception e) {
                result.updateStep(Step.MERGE_LOCAL, StepStatus.FAILED, e.getMessage());
                logger.error("[Production Hotfix] Local merge failed", e);
            }

            // Step 8: Cleanup hotfix branch
            result.updateStep(Step.CLEANUP, StepStatus.IN_PROGRESS, "");
            try {
                cleanupHotfixBranch(git, branchName);
                result.updateStep(Step.CLEANUP, StepStatus.COMPLETED, "Deleted " + branchName);
            } catch (Exception e) {
                result.updateStep(Step.CLEANUP, StepStatus.FAILED, e.getMessage());
                logger.warn("[Production Hotfix] Branch cleanup failed (non-critical): {}", e.getMessage());
            }

            // Finalize
            boolean success = !result.hasFailures();
            result.setPipelineComplete(true);
            result.setPipelineSuccess(success);

            String status = success ? "COMPLETED" : "COMPLETED_WITH_WARNINGS";
            logger.info("[Production Hotfix] {} — {}", status,
                success ? "all steps succeeded" : "some steps failed, check logs");

            // Persist status to DB
            persistHotfixStatus(configRecord, persistence, result, userName);

        } finally {
            activeHotfixes.remove(projectName);
        }

        return result;
    }

    /** Create a hotfix branch from current HEAD and switch to it. */
    public static String createHotfixBranch(Git git, String sanitizedName) throws Exception {
        String branchName = "hotfix/" + sanitizedName;
        git.branchCreate().setName(branchName).call();
        git.checkout().setName(branchName).call();
        return branchName;
    }

    /** Commit specified files on the current branch. Returns short commit hash. */
    public static String commitHotfix(Git git, String[] files, String message, String email) throws Exception {
        var addCmd = git.add();
        for (String file : files) {
            addCmd.addFilepattern(file);
        }
        addCmd.call();

        RevCommit commit = git.commit()
            .setMessage(message)
            .setAuthor("", email)
            .call();
        return commit.abbreviate(7).name();
    }

    /** Merge hotfix branch into the target branch. Assumes we're already on the target branch. */
    public static void mergeHotfixIntoMain(Git git, String hotfixBranch, String targetBranch) throws Exception {
        // Ensure we're on the target branch
        String currentBranch = git.getRepository().getBranch();
        if (!currentBranch.equals(targetBranch)) {
            git.checkout().setName(targetBranch).call();
        }

        MergeResult mergeResult = git.merge()
            .include(git.getRepository().findRef(hotfixBranch))
            .call();

        if (!mergeResult.getMergeStatus().isSuccessful()) {
            throw new RuntimeException("Merge failed with status: " + mergeResult.getMergeStatus());
        }
    }

    /** Delete the hotfix branch locally. */
    public static void cleanupHotfixBranch(Git git, String branchName) throws Exception {
        git.branchDelete().setBranchNames(branchName).setForce(true).call();
    }

    /** Roll back a failed hotfix: switch to main, delete the hotfix branch. */
    public static void rollback(Git git, String hotfixBranch, String targetBranch) {
        try {
            git.checkout().setName(targetBranch).call();
            git.branchDelete().setBranchNames(hotfixBranch).setForce(true).call();
            logger.info("[Production Hotfix] Rollback completed: switched to {} and deleted {}", targetBranch, hotfixBranch);
        } catch (Exception e) {
            logger.error("[Production Hotfix] Rollback failed — manual cleanup may be needed", e);
        }
    }

    private static void failPipeline(HotfixResult result, String message) {
        result.setPipelineComplete(true);
        result.setPipelineSuccess(false);
        result.setErrorMessage(message);
    }

    private static void persistHotfixStatus(GitProjectsConfigRecord record,
                                            com.inductiveautomation.ignition.gateway.localdb.persistence.PersistenceInterface persistence,
                                            HotfixResult result, String userName) {
        try {
            record.setLastHotfixStatus(result.isPipelineSuccess() ? "COMPLETED" : "COMPLETED_WITH_WARNINGS");
            record.setLastHotfixTimestamp(System.currentTimeMillis());
            record.setLastHotfixUser(userName);
            record.setLastHotfixBranch(result.getHotfixBranch());
            record.setLastHotfixPRUrl(result.getPrUrl());
            persistence.save(record);
        } catch (Exception e) {
            logger.error("[Production Hotfix] Failed to persist hotfix status to DB", e);
        }
    }
}
