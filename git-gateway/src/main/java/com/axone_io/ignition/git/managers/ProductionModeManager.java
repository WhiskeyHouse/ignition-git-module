package com.axone_io.ignition.git.managers;

import com.axone_io.ignition.git.dto.ProductionModeConfig;
import com.axone_io.ignition.git.records.GitProjectsConfigRecord;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
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
     * Prevents pushing to production branch unless explicitly allowed.
     */
    public static boolean validatePush(Git git, ProductionModeConfig config, String targetBranch) {
        if (!config.isProductionMode()) {
            logger.debug("Production mode not enabled, allowing push");
            return true;
        }

        try {
            String productionBranch = config.getProductionBranch();

            if (productionBranch != null && !productionBranch.isEmpty()) {
                // Prevent direct push to production branch
                if (targetBranch.equals(productionBranch) ||
                    productionBranch.equals("main") ||
                    productionBranch.equals("master")) {

                    String warning = String.format(
                        "Production mode: Direct push to '%s' is not allowed in production mode",
                        targetBranch
                    );
                    logger.warn(warning);
                    config.setWarningMessage(warning);
                    config.setValid(false);
                    return false;
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
            String regexPattern = patternStr;
            if (!patternStr.startsWith("^")) {
                // Simple wildcard conversion: * -> .*, ? -> .
                regexPattern = patternStr.replace(".", "\\.")
                                       .replace("*", ".*")
                                       .replace("?", ".");
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
     */
    public static boolean isRepositorySafe(Repository repository) {
        try {
            // Check for uncommitted changes
            // This would need to be implemented based on JGit status check
            logger.debug("Checking repository safety for production operations");
            return true;
        } catch (Exception e) {
            logger.error("Error checking repository safety", e);
            return false;
        }
    }
}
