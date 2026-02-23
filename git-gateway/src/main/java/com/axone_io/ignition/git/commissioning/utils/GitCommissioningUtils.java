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
import com.inductiveautomation.ignition.common.resourcecollection.ResourceCollectionManifest;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.localdb.persistence.PersistenceInterface;
import com.inductiveautomation.ignition.gateway.project.ProjectManager;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import simpleorm.dataset.SQuery;

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
//                GitCommissioningConfig projectConfigs = parseConfigLines(yamlBytes);

                if (projectConfigs != null) {
                    for (ProjectConfig projectConfig : projectConfigs.getProjects()) {
                        GitCommissioningConfig gitConfig = new GitCommissioningConfig();
                        gitConfig.loadFromProjectConfig(projectConfig);

                        config = gitConfig;

                        if (config.getRepoURI() == null || config.getRepoBranch() == null
                                || config.getIgnitionProjectName() == null || config.getIgnitionUserName() == null
                                || config.getUserName() == null || (config.getUserPassword() == null && config.getSshKey() == null)
                                || config.getUserEmail() == null) {
                            throw new RuntimeException("Incomplete git configuration file.");
                        }

                        // Ensure config and user records exist even if the project already exists
                        PersistenceInterface persistenceInterface = context.getPersistenceInterface();
                        SQuery<GitProjectsConfigRecord> configQuery = new SQuery<>(GitProjectsConfigRecord.META).eq(GitProjectsConfigRecord.ProjectName, config.getIgnitionProjectName());
                        GitProjectsConfigRecord projectsConfigRecord = persistenceInterface.queryOne(configQuery);

                        if (projectsConfigRecord == null) {
                            // Create new config record
                            projectsConfigRecord = persistenceInterface.createNew(GitProjectsConfigRecord.META);
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
                            logger.info("Created GitProjectsConfigRecord for '" + config.getIgnitionProjectName() + "'.");
                        }

                        // Ensure user record exists for this project
                        SQuery<GitReposUsersRecord> userQuery = new SQuery<>(GitReposUsersRecord.META).eq(GitReposUsersRecord.ProjectId, projectsConfigRecord.getId());
                        if (persistenceInterface.queryOne(userQuery) == null) {
                            logger.info("Creating missing GitReposUsersRecord for project '" + config.getIgnitionProjectName() + "'.");

                            String userSecretFilePath = System.getenv("GATEWAY_GIT_USER_SECRET_FILE");
                            if (userSecretFilePath != null) {
                                config.setSecretFromFilePath(Paths.get(userSecretFilePath), projectsConfigRecord.isSSHAuthentication());
                            }

                            GitReposUsersRecord reposUsersRecord = persistenceInterface.createNew(GitReposUsersRecord.META);
                            reposUsersRecord.setUserName(config.getUserName());
                            reposUsersRecord.setIgnitionUser(config.getIgnitionUserName());
                            reposUsersRecord.setProjectId(projectsConfigRecord.getId());

                            // Only set encrypted fields if they have valid values
                            // EncodedStringField cannot accept placeholder text like "<not used>"
                            if (projectsConfigRecord.isSSHAuthentication()) {
                                String sshKey = config.getSshKey();
                                if (sshKey != null && !sshKey.trim().isEmpty() && !sshKey.trim().equals("<not used>")) {
                                    reposUsersRecord.setSSHKey(sshKey.trim());
                                }
                            } else {
                                String password = config.getUserPassword();
                                if (password != null && !password.trim().isEmpty() && !password.trim().equals("<not used>")) {
                                    reposUsersRecord.setPassword(password.trim());
                                }
                            }
                            reposUsersRecord.setEmail(config.getUserEmail());
                            persistenceInterface.save(reposUsersRecord);
                        }

                        // Skip project creation/cloning/importing if the project already exists
                        if (projectManager.getNames().contains(gitConfig.getIgnitionProjectName())) {
                            logger.info("Project '" + config.getIgnitionProjectName() + "' already exists, skipping clone/import.");
                            continue;
                        }

                        projectManager.create(config.getIgnitionProjectName(), new ResourceCollectionManifest(config.getIgnitionProjectName(), "", false, config.isIgnitionProjectInheritable(), config.getIgnitionProjectParentName()), new ArrayList<>());

                        Path projectDir = getProjectFolderPath(config.getIgnitionProjectName());
                        clearDirectory(projectDir);

                        // CLONE PROJECT
                        cloneRepo(config.getIgnitionProjectName(), config.getIgnitionUserName(), config.getRepoURI(), config.getRepoBranch());

                        // IMPORT PROJECT
                        GitProjectManager.importProject(config.getIgnitionProjectName());

                        // IMPORT TAGS
                        if (config.isImportTags()) {
                            GitTagManager.importTagManager(config.getIgnitionProjectName(), null);
                        }

                        // IMPORT THEMES
                        if (config.isImportThemes()) {
                            GitThemeManager.importTheme(config.getIgnitionProjectName());
                        }

                        // IMPORT IMAGES
                        if (config.isImportImages()) {
                            GitImageManager.importImages(config.getIgnitionProjectName());
                        }
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
//            Yaml yaml = new Yaml(new Constructor(ProjectConfigs.class));
            LoaderOptions loaderOptions = new LoaderOptions();
            Yaml yaml = new Yaml(new SafeConstructor(loaderOptions));
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
                                    logger.info("Successful addition of field: " + fieldName+ ": " + value);
                                    field.set(config, value);
                                } else {
                                    // Handle type conversion if necessary, e.g., for Boolean fields
                                    if (field.getType().equals(Boolean.class) && value instanceof String) {
                                        logger.info("Successful addition of field: " + fieldName+ ": " + value);
                                        field.set(config, Boolean.parseBoolean((String) value));
                                    } else {
                                        // Log or throw an error for unsupported types
                                        //                                    System.err.println("Unsupported type conversion for field: " + fieldName);
                                        logger.warn("Unsupported type conversion for field: " + fieldName);
                                    }
                                }
                            } else {
                                // If value is null and the field type supports null, set it directly.
                                // This is particularly relevant for object wrapper types like Boolean, String, etc.
                                if (!field.getType().isPrimitive()) {
                                    field.set(config, null);
                                    logger.info("Successful addition of field: " + fieldName+ ": null");
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

