package com.axone_io.ignition.git.managers;

import com.axone_io.ignition.git.dto.ProductionModeConfig;
import com.axone_io.ignition.git.records.GitProjectsConfigRecord;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Manager class for production mode safety checks and validations.
 * Provides methods to validate git operations against production mode constraints.
 */
public class ProductionModeManager {
    private static final Logger logger = LoggerFactory.getLogger(ProductionModeManager.class);

    /**
     * Build a ProductionModeConfig from the database record.
     */
    public static ProductionModeConfig buildConfig(GitProjectsConfigRecord record) {
        ProductionModeConfig config = new ProductionModeConfig();
        config.setProductionMode(record.getProductionMode());
        config.setProductionBranch(record.getProductionBranch());
        config.setProductionTagPattern(record.getProductionTagPattern());
        return config;
    }

    /**
     * Check if a branch name is a hotfix branch.
     */
    public static boolean isHotfixBranch(String branchName) {
        return branchName != null && branchName.startsWith("hotfix/");
    }

    /**
     * Validate if a pull operation is safe in production mode.
     * Checks:
     * - If production mode is enabled
     * - If current branch matches production branch
     * - If tags match the allowed pattern
     */
    public static boolean validatePull(Git git, ProductionModeConfig config) {
        if (!config.isProductionMode()) {
            logger.debug("Production mode not enabled, allowing pull");
            return true;
        }

        try {
            String currentBranch = git.getRepository().getBranch();

            // Block pull on hotfix branches — hotfix is a sealed environment
            if (isHotfixBranch(currentBranch)) {
                String warning = "Pull is disabled on hotfix branches. Complete your hotfix first, then pull on main.";
                logger.warn(warning);
                config.setWarningMessage(warning);
                config.setValid(false);
                return false;
            }

            // Check repository safety first
            String unsafeReason = describeUnsafeState(git.getRepository());
            if (unsafeReason != null) {
                String warning = "Production mode: cannot pull because " + unsafeReason;
                logger.warn(warning);
                config.setWarningMessage(warning);
                config.setValid(false);
                return false;
            }

            String productionBranch = config.getProductionBranch();

            // Check if we're on the production branch
            if (productionBranch != null && !productionBranch.isEmpty()) {
                if (!currentBranch.equals(productionBranch)) {
                    String warning = String.format(
                        "Production mode warning: Current branch '%s' does not match production branch '%s'",
                        currentBranch, productionBranch
                    );
                    logger.warn(warning);
                    config.setWarningMessage(warning);
                    config.setValid(false);
                    return false;
                }
            }

            // Validate tag pattern if specified
            String tagPattern = config.getProductionTagPattern();
            if (tagPattern != null && !tagPattern.isEmpty()) {
                List<String> tags = listTags(git);
                if (!validateTagPattern(tags, tagPattern)) {
                    String warning = String.format(
                        "Production mode warning: Repository tags do not match pattern '%s'",
                        tagPattern
                    );
                    logger.warn(warning);
                    config.setWarningMessage(warning);
                    config.setValid(false);
                    return false;
                }
            }

            logger.info("Production mode validation passed for pull operation");
            return true;

        } catch (Exception e) {
            logger.error("Error validating production mode pull", e);
            config.setValid(false);
            config.setValidationMessage("Error during validation: " + e.getMessage());
            return false;
        }
    }

    /**
     * Validate if a push operation is safe in production mode.
     * Pushes to the production branch are allowed but flagged with a warning
     * so the Designer can require explicit confirmation.
     * Returns false only for hard blocks (unsafe repo state).
     */
    public static boolean validatePush(Git git, ProductionModeConfig config, String targetBranch) {
        if (!config.isProductionMode()) {
            logger.debug("Production mode not enabled, allowing push");
            return true;
        }

        try {
            // Hotfix branches are expected to be pushed — no warning needed
            if (isHotfixBranch(targetBranch)) {
                logger.info("Production mode: allowing push on hotfix branch '{}'", targetBranch);
                return true;
            }

            // Check repository safety first — this is a hard block
            String unsafeReason = describeUnsafeState(git.getRepository());
            if (unsafeReason != null) {
                String warning = "Production mode: cannot push because " + unsafeReason;
                logger.warn(warning);
                config.setWarningMessage(warning);
                config.setValid(false);
                return false;
            }

            String productionBranch = config.getProductionBranch();

            if (productionBranch != null && !productionBranch.isEmpty()) {
                // Flag push to production branch as a warning (requires confirmation via popup)
                if (targetBranch.equals(productionBranch)) {
                    String warning = String.format(
                        "You are pushing directly to the production branch '%s'. " +
                        "This should only be done for hotfixes that need to go live immediately.",
                        targetBranch
                    );
                    logger.warn("Production mode: Push to production branch '{}' — requires confirmation", targetBranch);
                    config.setWarningMessage(warning);
                    // Valid but with warning — Designer popup will handle confirmation
                }
            }

            logger.info("Production mode validation passed for push operation to branch: {}", targetBranch);
            return true;

        } catch (Exception e) {
            logger.error("Error validating production mode push", e);
            config.setValid(false);
            config.setValidationMessage("Error during validation: " + e.getMessage());
            return false;
        }
    }

    /**
     * List all tags in the repository.
     */
    public static List<String> listTags(Git git) throws Exception {
        List<String> tagNames = new ArrayList<>();
        List<Ref> tags = git.tagList().call();

        for (Ref tag : tags) {
            String tagName = tag.getName();
            // Remove refs/tags/ prefix
            if (tagName.startsWith("refs/tags/")) {
                tagName = tagName.substring("refs/tags/".length());
            }
            tagNames.add(tagName);
        }

        logger.debug("Found {} tags in repository", tagNames.size());
        return tagNames;
    }

    /**
     * Validate if tags match the specified pattern.
     * Pattern can be a regex or a simple wildcard pattern.
     */
    public static boolean validateTagPattern(List<String> tags, String patternStr) {
        if (tags.isEmpty()) {
            logger.warn("No tags found in repository");
            return false;
        }

        try {
            // Convert wildcard pattern to regex if needed
            String regexPattern;
            boolean looksLikeWildcard = patternStr.contains("*") || patternStr.contains("?");
            if (looksLikeWildcard) {
                // Split on wildcards, quote literal segments, then rejoin with regex equivalents
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < patternStr.length(); i++) {
                    char c = patternStr.charAt(i);
                    if (c == '*') {
                        sb.append(".*");
                    } else if (c == '?') {
                        sb.append(".");
                    } else {
                        sb.append(Pattern.quote(String.valueOf(c)));
                    }
                }
                regexPattern = sb.toString();
            } else {
                // Treat as regex as-is
                regexPattern = patternStr;
            }

            Pattern pattern = Pattern.compile(regexPattern);

            // Check if at least one tag matches the pattern
            for (String tag : tags) {
                if (pattern.matcher(tag).matches()) {
                    logger.debug("Tag '{}' matches pattern '{}'", tag, patternStr);
                    return true;
                }
            }

            logger.warn("No tags match the pattern '{}'", patternStr);
            return false;

        } catch (Exception e) {
            logger.error("Error validating tag pattern: {}", patternStr, e);
            return false;
        }
    }

    /**
     * Generate a warning message for production mode operations.
     */
    public static String generateWarningMessage(ProductionModeConfig config, String operation) {
        StringBuilder warning = new StringBuilder();
        warning.append("⚠️ PRODUCTION MODE ACTIVE ⚠️\n\n");
        warning.append("This project is configured for production mode.\n");
        warning.append("Operation: ").append(operation).append("\n\n");

        if (config.getProductionBranch() != null && !config.getProductionBranch().isEmpty()) {
            warning.append("Production Branch: ").append(config.getProductionBranch()).append("\n");
        }

        if (config.getProductionTagPattern() != null && !config.getProductionTagPattern().isEmpty()) {
            warning.append("Required Tag Pattern: ").append(config.getProductionTagPattern()).append("\n");
        }

        warning.append("\nPlease review carefully before proceeding.");

        return warning.toString();
    }

    /**
     * Check if the current repository state is safe for production operations.
     * A repository is considered unsafe if it has uncommitted changes or is in a
     * special state (e.g., merging, rebasing, cherry-picking).
     */
    public static boolean isRepositorySafe(Repository repository) {
        return describeUnsafeState(repository) == null;
    }

    /**
     * Maximum number of offending paths named in an unsafe-state message. Beyond this the
     * message summarises the remainder rather than dumping an unreadable wall of paths.
     */
    private static final int MAX_REPORTED_PATHS = 10;

    /**
     * Explain why the repository is unsafe for production operations, or return {@code null}
     * if it is safe.
     *
     * <p>Callers put this string in front of the user, so it names the offending paths. Knowing
     * <em>that</em> the working tree is dirty is not actionable on a gateway you have to SSH into
     * to inspect; knowing <em>which</em> files are dirty is.</p>
     *
     * <p>A repository is unsafe if it has uncommitted changes to tracked files or is in a special
     * state (merging, rebasing, cherry-picking). Untracked files are intentionally ignored, since
     * Ignition may create temporary files in the project directory.</p>
     */
    public static String describeUnsafeState(Repository repository) {
        try {
            logger.debug("Checking repository safety for production operations");

            RepositoryState state = repository.getRepositoryState();
            if (state != RepositoryState.SAFE) {
                logger.warn("Repository is in an unsafe state for production operations: {}", state);
                return "the repository is in the " + state.name() + " state (an unfinished merge, "
                        + "rebase or cherry-pick). Finish or abort it before proceeding.";
            }

            try (Git git = new Git(repository)) {
                Status status = git.status().call();

                if (status.hasUncommittedChanges()) {
                    Set<String> paths = new TreeSet<>();
                    addPaths(paths, status.getConflicting());
                    addPaths(paths, status.getChanged());
                    addPaths(paths, status.getModified());
                    addPaths(paths, status.getAdded());
                    addPaths(paths, status.getRemoved());
                    addPaths(paths, status.getMissing());

                    logger.warn("Repository has uncommitted changes — unsafe for production operations. "
                                    + "Modified: {}, Added: {}, Removed: {}, Missing: {}, Conflicting: {}",
                            status.getModified().size(),
                            status.getAdded().size(),
                            status.getRemoved().size(),
                            status.getMissing().size(),
                            status.getConflicting().size());

                    return "the repository has " + paths.size() + " uncommitted change(s):\n"
                            + formatPaths(paths)
                            + "\nCommit or discard them before proceeding.";
                }
            }

            logger.debug("Repository is safe for production operations");
            return null;
        } catch (Exception e) {
            logger.error("Error checking repository safety", e);
            return "the repository state could not be verified: " + e.getMessage();
        }
    }

    /**
     * Collect paths for reporting, deduplicated and sorted across every status.
     *
     * <p>A {@link TreeSet} rather than sort-per-status: the message does not label which status
     * each path came from, so per-status ordering is invisible and just reads as an unsorted
     * list. One global alphabetical order is scannable, and stays stable as files move between
     * statuses.</p>
     */
    private static void addPaths(Set<String> target, Set<String> paths) {
        if (paths == null) {
            return;
        }
        target.addAll(paths);
    }

    private static String formatPaths(Collection<String> paths) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (String path : paths) {
            if (shown == MAX_REPORTED_PATHS) {
                break;
            }
            sb.append("  • ").append(path).append('\n');
            shown++;
        }
        if (paths.size() > shown) {
            sb.append("  … and ").append(paths.size() - shown).append(" more\n");
        }
        return sb.toString();
    }
}
