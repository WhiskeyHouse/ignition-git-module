package com.axone_io.ignition.git.commissioning.utils;

import com.axone_io.ignition.git.commissioning.GitCommissioningConfig;
import com.axone_io.ignition.git.commissioning.ProjectConfig;
import com.axone_io.ignition.git.commissioning.ProjectConfigs;
import com.axone_io.ignition.git.managers.GitImageManager;
import com.axone_io.ignition.git.managers.GitProjectManager;
import com.axone_io.ignition.git.managers.GitTagManager;
import com.axone_io.ignition.git.managers.GitThemeManager;
import com.axone_io.ignition.git.records.GitProjectsConfigRecord;
import com.axone_io.ignition.git.records.GitReposUsersRecord;
import com.google.common.eventbus.Subscribe;
import com.inductiveautomation.ignition.common.project.ProjectManifest;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.localdb.persistence.PersistenceInterface;
import com.inductiveautomation.ignition.gateway.project.ProjectManager;
import org.apache.commons.io.FileUtils;
import org.yaml.snakeyaml.Yaml;
import simpleorm.dataset.SQuery;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.axone_io.ignition.git.GatewayHook.context;
import static com.axone_io.ignition.git.managers.GitManager.*;

public class GitCommissioningUtils {
    private final static LoggerEx logger = LoggerEx.newBuilder().build(GitCommissioningUtils.class);

    public static GitCommissioningConfig config;

    @Subscribe
    public static void loadConfiguration() {
        Path dataDir = getDataFolderPath();
        Path yamlConfigPath = dataDir.resolve("git.yaml"); // Assuming the YAML file is named git.yaml
        ProjectManager projectManager = context.getProjectManager();

        try {
            if (yamlConfigPath.toFile().exists() && yamlConfigPath.toFile().isFile()) {
                ProjectConfigs projectConfigs = parseYaml(yamlConfigPath);
                // GitCommissioningConfig projectConfigs = parseConfigLines(yamlBytes);

                if (projectConfigs != null) {
                    // First pass: Create parent projects
                    for (ProjectConfig projectConfig : projectConfigs.getProjects()) {
                        GitCommissioningConfig gitConfig = new GitCommissioningConfig();
                        gitConfig.loadFromProjectConfig(projectConfig);

                        // Skip if this project has a parent (we'll handle it in the second pass)
                        if (gitConfig.getIgnitionProjectParentName() != null
                                && !gitConfig.getIgnitionProjectParentName().isEmpty()) {
                            continue;
                        }

                        // Process parent project
                        processProject(gitConfig, projectManager);
                    }

                    // Second pass: Create child projects
                    for (ProjectConfig projectConfig : projectConfigs.getProjects()) {
                        GitCommissioningConfig gitConfig = new GitCommissioningConfig();
                        gitConfig.loadFromProjectConfig(projectConfig);

                        // Skip if this project has no parent (already handled in first pass)
                        if (gitConfig.getIgnitionProjectParentName() == null
                                || gitConfig.getIgnitionProjectParentName().isEmpty()) {
                            continue;
                        }

                        // Verify parent exists
                        if (!projectManager.getProjectNames().contains(gitConfig.getIgnitionProjectParentName())) {
                            logger.error("Cannot create project '" + gitConfig.getIgnitionProjectName() +
                                    "' because parent project '" + gitConfig.getIgnitionProjectParentName()
                                    + "' does not exist.");
                            continue;
                        }

                        // Process child project
                        processProject(gitConfig, projectManager);
                    }
                }
            } else {
                logger.info("No git configuration file was found.");
            }
        } catch (Exception e) {
            logger.error("An error occurred while git configuration settings up from the provided YAML.", e);
        }
    }

    protected static ProjectConfigs parseYaml(Path yamlFilePath) {
        try (InputStream inputStream = new FileInputStream(yamlFilePath.toFile())) {
            // Yaml yaml = new Yaml(new Constructor(ProjectConfigs.class));
            Yaml yaml = new Yaml();
            Object obj = yaml.load(inputStream);

            ProjectConfigs projectConfigs = new ProjectConfigs();

            if (obj instanceof List) {
                List<Map<String, Object>> list = (List<Map<String, Object>>) obj;
                for (Map<String, Object> item : list) {
                    // Create an instance of ProjectConfig
                    ProjectConfig config = new ProjectConfig();

                    // Iterate over each entry in the YAML map
                    for (Map.Entry<String, Object> entry : item.entrySet()) {
                        try {
                            // Convert YAML key to field name
                            String fieldName = yamlKeyToFieldName(entry.getKey());
                            Field field = ProjectConfig.class.getDeclaredField(fieldName);
                            field.setAccessible(true); // Make the field accessible

                            // Set the field value, converting to the appropriate type if necessary
                            Object value = entry.getValue();
                            if (value != null) {
                                if (field.getType().isAssignableFrom(value.getClass())) {
                                    logger.info("Successful addition of field: " + fieldName + ": " + value);
                                    field.set(config, value);
                                } else {
                                    // Handle type conversion if necessary, e.g., for Boolean fields
                                    if (field.getType().equals(Boolean.class) && value instanceof String) {
                                        logger.info("Successful addition of field: " + fieldName + ": " + value);
                                        field.set(config, Boolean.parseBoolean((String) value));
                                    } else {
                                        // Log or throw an error for unsupported types
                                        // System.err.println("Unsupported type conversion for field: " + fieldName);
                                        logger.warn("Unsupported type conversion for field: " + fieldName);
                                    }
                                }
                            } else {
                                // If value is null and the field type supports null, set it directly.
                                // This is particularly relevant for object wrapper types like Boolean, String,
                                // etc.
                                if (!field.getType().isPrimitive()) {
                                    field.set(config, null);
                                    logger.info("Successful addition of field: " + fieldName + ": null");
                                } else {
                                    // For primitive fields, you might decide to leave the default value
                                    // or handle it according to your application's needs.
                                    System.err.println("Cannot set null value to primitive field: " + fieldName);
                                    logger.warn("Cannot set null value to primitive field: " + fieldName);
                                }
                            }
                        } catch (NoSuchFieldException | IllegalAccessException e) {
                            logger.error("Error occurred in fetching YAML Git Config data ", e);
                        }

                    }
                    projectConfigs.addProject(config);
                }
                // Return project configs
                return projectConfigs;
            }
        } catch (IOException e) {
            logger.error("An error occurred while fetching the YAML configuration file.", e);
        }
        return null;
    }

    private static void processProject(GitCommissioningConfig config, ProjectManager projectManager) {
        try {
            logger.info("Starting to process project: " + config.getIgnitionProjectName());

            // Validate configuration
            if (config.getRepoURI() == null || config.getRepoBranch() == null
                    || config.getIgnitionProjectName() == null || config.getIgnitionUserName() == null
                    || config.getUserName() == null || (config.getUserPassword() == null && config.getSshKey() == null)
                    || config.getUserEmail() == null) {
                logger.error("Incomplete configuration for project: " + config.getIgnitionProjectName());
                throw new RuntimeException("Incomplete git configuration file.");
            }

            // Create Git records first
            logger.info("Setting up Git configuration records");
            PersistenceInterface persistenceInterface = context.getPersistenceInterface();

            // Create project config record
            GitProjectsConfigRecord projectsConfigRecord = persistenceInterface.createNew(GitProjectsConfigRecord.META);
            projectsConfigRecord.setProjectName(config.getIgnitionProjectName());
            projectsConfigRecord.setURI(config.getRepoURI());

            String userSecretFilePath = System.getenv("GATEWAY_GIT_USER_SECRET_FILE");
            if (userSecretFilePath != null) {
                config.setSecretFromFilePath(Paths.get(userSecretFilePath), projectsConfigRecord.isSSHAuthentication());
            }
            if (config.getSshKey() == null && config.getUserPassword() == null) {
                throw new Exception("Git User Password or SSHKey not configured.");
            }
            persistenceInterface.save(projectsConfigRecord);

            // Verify project record was saved and get its ID
            GitProjectsConfigRecord savedProject = persistenceInterface
                    .queryOne(new SQuery<>(GitProjectsConfigRecord.META)
                            .eq(GitProjectsConfigRecord.ProjectName, config.getIgnitionProjectName()));
            if (savedProject == null) {
                throw new Exception("Failed to save project configuration record");
            }
            logger.info("Created project record with ID: " + savedProject.getId());

            // Create user record with verified project ID
            GitReposUsersRecord reposUsersRecord = persistenceInterface.createNew(GitReposUsersRecord.META);
            reposUsersRecord.setUserName(config.getUserName());
            reposUsersRecord.setIgnitionUser(config.getIgnitionUserName());
            reposUsersRecord.setProjectId(savedProject.getId()); // Use the verified ID
            if (projectsConfigRecord.isSSHAuthentication()) {
                reposUsersRecord.setSSHKey(config.getSshKey());
            } else {
                reposUsersRecord.setPassword(config.getUserPassword());
            }
            reposUsersRecord.setEmail(config.getUserEmail());
            persistenceInterface.save(reposUsersRecord);

            // Verify user record was saved
            GitReposUsersRecord savedUser = persistenceInterface.queryOne(new SQuery<>(GitReposUsersRecord.META)
                    .eq(GitReposUsersRecord.ProjectId, savedProject.getId())
                    .eq(GitReposUsersRecord.IgnitionUser, config.getIgnitionUserName()));
            if (savedUser == null) {
                throw new Exception("Failed to save user record");
            }
            logger.info("Created user record for project ID: " + savedProject.getId());

            // Create the project in Ignition first
            logger.info("Creating project in Ignition");
            projectManager.createProject(
                    config.getIgnitionProjectName(),
                    new ProjectManifest(
                            config.getIgnitionProjectName(),
                            "",
                            true,
                            config.isIgnitionProjectInheritable(),
                            config.getIgnitionProjectParentName()),
                    new ArrayList());

            // Delete the project directory that Ignition created
            Path projectDir = getProjectFolderPath(config.getIgnitionProjectName());
            logger.info("Deleting project directory at: " + projectDir);
            try {
                if (projectDir.toFile().exists()) {
                    FileUtils.deleteDirectory(projectDir.toFile());
                }
            } catch (IOException e) {
                logger.error("Failed to delete project directory", e);
                throw e;
            }

            // Now clone the repository into the clean directory
            logger.info("Cloning repository: " + config.getRepoURI() + " branch: " + config.getRepoBranch());
            cloneRepo(config.getIgnitionProjectName(), config.getIgnitionUserName(), config.getRepoURI(),
                    config.getRepoBranch());

            // Verify clone result
            if (projectDir.toFile().exists()) {
                logger.info("Directory exists after clone, contents: " +
                        String.join(", ", projectDir.toFile().list()));
                File gitDir = new File(projectDir.toFile(), ".git");
                if (!gitDir.exists()) {
                    logger.error("Git directory not found after clone");
                    throw new RuntimeException("Git clone failed - .git directory not created");
                }
            } else {
                logger.error("Project directory does not exist after clone attempt");
                throw new RuntimeException("Git clone failed - directory not created");
            }

            // Only proceed with Git operations if clone was successful
            try (Git git = Git.open(projectDir.toFile())) {
                logger.info("Cleaning git repository");
                git.clean()
                        .setCleanDirectories(true)
                        .setForce(true)
                        .setIgnore(false)
                        .call();

                logger.info("Resetting to origin/" + config.getRepoBranch());
                git.reset()
                        .setMode(ResetCommand.ResetType.HARD)
                        .setRef("origin/" + config.getRepoBranch())
                        .call();

                logger.info("Checking out branch: " + config.getRepoBranch());
                git.checkout()
                        .setName(config.getRepoBranch())
                        .setForce(true)
                        .setForceRefUpdate(true)
                        .call();
            }

            // Import project resources
            logger.info("Importing project resources");
            GitProjectManager.importProject(config.getIgnitionProjectName());

            // Import additional resources
            if (config.isImportTags()) {
                logger.info("Importing tags");
                GitTagManager.importTagManager(config.getIgnitionProjectName());
            }

            if (config.isImportThemes()) {
                logger.info("Importing themes");
                GitThemeManager.importTheme(config.getIgnitionProjectName());
            }

            if (config.isImportImages()) {
                logger.info("Importing images");
                GitImageManager.importImages(config.getIgnitionProjectName());
            }

            logger.info("Successfully completed processing project: " + config.getIgnitionProjectName());

        } catch (Exception e) {
            logger.error("Error processing project " + config.getIgnitionProjectName(), e);
            logger.error("Stack trace: ", e);
        }
    }

    private static String yamlKeyToFieldName(String yamlKey) {

        // If the YAML key exactly matches the field name, just return it.
        // This is a shortcut for cases where no conversion is necessary.
        // Remove this line if all keys need conversion.
        if (yamlKey.equals("repo_uri") || yamlKey.equals("repo_branch") ||
                yamlKey.equals("ignition_projectName") || yamlKey.equals("ignition_userName") ||
                yamlKey.equals("ignition_inheritable") || yamlKey.equals("ignition_parentName") ||
                yamlKey.equals("user_name") || yamlKey.equals("user_email") ||
                yamlKey.equals("user_password") || yamlKey.equals("commissioning_importThemes") ||
                yamlKey.equals("commissioning_importTags") || yamlKey.equals("commissioning_importImages")) {
            return yamlKey; // Your field names already match the YAML keys
        }

        // Split the string at each underscore
        String[] parts = yamlKey.split("_");
        StringBuilder fieldName = new StringBuilder(parts[0]); // Keep the first part as is

        // Convert the first letter of each subsequent part to uppercase
        for (int i = 1; i < parts.length; i++) {
            // Check if part is not empty to avoid StringIndexOutOfBoundsException
            if (!parts[i].isEmpty()) {
                fieldName.append(parts[i].substring(0, 1).toUpperCase()).append(parts[i].substring(1));
            }
        }

        return fieldName.toString();
    }
}
