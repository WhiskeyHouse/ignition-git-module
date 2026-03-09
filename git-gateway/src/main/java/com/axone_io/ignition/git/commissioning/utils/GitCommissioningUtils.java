package com.axone_io.ignition.git.commissioning.utils;

import com.axone_io.ignition.git.commissioning.GitCommissioningConfig;
import com.axone_io.ignition.git.commissioning.ProjectConfig;
import com.axone_io.ignition.git.commissioning.ProjectConfigs;
import com.axone_io.ignition.git.managers.GitImageManager;
import com.axone_io.ignition.git.managers.GitManager;
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
import org.eclipse.jgit.api.*;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
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

                        // Resolve secret from env var (direct value) or file path (legacy)
                        resolveSecretFromEnv(config, projectsConfigRecord != null && projectsConfigRecord.isSSHAuthentication());

                        if (projectsConfigRecord == null) {
                            // Create new config record
                            projectsConfigRecord = persistenceInterface.createNew(GitProjectsConfigRecord.META);
                            projectsConfigRecord.setProjectName(config.getIgnitionProjectName());
                            projectsConfigRecord.setURI(config.getRepoURI());

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

                        // If the project already exists, sync it rather than re-cloning
                        if (projectManager.getNames().contains(gitConfig.getIgnitionProjectName())) {
                            logger.info("Project '" + config.getIgnitionProjectName() + "' already exists, syncing...");
                            syncExistingProject(config);
                            continue;
                        }

                        // Create new project — catch "already exists" as a fallback in case
                        // getNames() didn't include it (observed in some Ignition versions)
                        try {
                            projectManager.create(config.getIgnitionProjectName(), new ResourceCollectionManifest(config.getIgnitionProjectName(), "", false, config.isIgnitionProjectInheritable(), config.getIgnitionProjectParentName()), new ArrayList<>());
                        } catch (Exception createEx) {
                            String msg = createEx.getMessage() != null ? createEx.getMessage() : "";
                            if (msg.contains("already exists") || createEx.getClass().getSimpleName().contains("Conflict")) {
                                logger.warn("Project '" + config.getIgnitionProjectName() + "' already exists (missed by getNames check), falling back to sync.");
                                syncExistingProject(config);
                                continue;
                            }
                            throw createEx;
                        }

                        Path projectDir = getProjectFolderPath(config.getIgnitionProjectName());
                        clearDirectory(projectDir);

                        // CLONE PROJECT
                        cloneRepo(config.getIgnitionProjectName(), config.getIgnitionUserName(), config.getRepoURI(), config.getRepoBranch());

                        // IMPORT PROJECT AND RESOURCES
                        importProjectResources(config);
                    }
                }
            } else {
                logger.info("No git configuration file was found.");
            }
        } catch (Exception e) {
            logger.error("An error occurred while git configuration settings up from the provided YAML.", e);
        }
    }

    /**
     * Syncs an existing project to the state defined in git.yaml.
     * Stashes local changes, fetches, switches branch if needed, pulls, and re-imports.
     */
    private static void syncExistingProject(GitCommissioningConfig config) {
        String projectName = config.getIgnitionProjectName();
        Path projectDir = getProjectFolderPath(projectName);

        // Guard: skip if no .git directory (not a git-managed project)
        if (!projectDir.resolve(".git").toFile().exists()) {
            logger.warn("Project '" + projectName + "' exists but has no .git directory, skipping sync.");
            return;
        }

        logger.info("Syncing existing project '" + projectName + "' to configured state...");

        try (Git git = getGit(projectDir)) {
            String remoteName = GitManager.getRemoteName(git);
            String currentBranch = git.getRepository().getBranch();
            String configuredBranch = config.getRepoBranch();

            // 1. Stash uncommitted changes
            stashIfDirty(git, projectName);

            // 2. Fetch from remote
            logger.info("Fetching from remote '" + remoteName + "' for project '" + projectName + "'...");
            FetchCommand fetch = git.fetch().setRemote(remoteName);
            setAuthentication(fetch, projectName, config.getIgnitionUserName());
            fetch.call();

            // 3. Switch branch if needed (and enforceBranch is enabled)
            if (config.isEnforceBranch()) {
                if (!currentBranch.equals(configuredBranch)) {
                    logger.info("Switching project '" + projectName + "' from branch '" + currentBranch + "' to configured branch '" + configuredBranch + "'.");
                    boolean localBranchExists = git.branchList().call().stream()
                            .anyMatch(ref -> ref.getName().equals("refs/heads/" + configuredBranch));

                    CheckoutCommand checkout = git.checkout().setName(configuredBranch);
                    if (!localBranchExists) {
                        checkout.setCreateBranch(true)
                                .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                                .setStartPoint(remoteName + "/" + configuredBranch);
                    }
                    checkout.call();
                }
            } else {
                logger.info("Skipping branch switch for project '" + projectName + "' because commissioning_enforceBranch is false. Staying on branch '" + currentBranch + "'.");
            }

            // 4. Pull latest
            String activeBranch = git.getRepository().getBranch();
            logger.info("Pulling latest changes for project '" + projectName + "' on branch '" + activeBranch + "'...");
            PullCommand pull = git.pull().setRemote(remoteName).setRemoteBranchName(activeBranch);
            setAuthentication(pull, projectName, config.getIgnitionUserName());
            PullResult pullResult = pull.call();
            logger.info("Pull result for project '" + projectName + "': " + (pullResult.isSuccessful() ? "success" : "failed"));

            // 5. Re-import project resources
            importProjectResources(config);

            logger.info("Sync complete for project '" + projectName + "'.");
        } catch (Exception e) {
            logger.error("Error syncing existing project '" + projectName + "'. Project remains in its current state.", e);
        }
    }

    /**
     * Stashes uncommitted changes if the working tree is dirty.
     */
    private static void stashIfDirty(Git git, String projectName) {
        try {
            Status status = git.status().call();
            if (!status.isClean()) {
                logger.info("Project '" + projectName + "' has uncommitted changes, stashing...");
                RevCommit stash = git.stashCreate()
                        .setWorkingDirectoryMessage("Auto-stash before commissioning sync")
                        .call();
                if (stash != null) {
                    logger.info("Stashed uncommitted changes for project '" + projectName + "': " + stash.getName());
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to stash changes for project '" + projectName + "', continuing: " + e.getMessage());
        }
    }

    /**
     * Imports project resources (project data, tags, themes, images) based on config flags.
     */
    private static void importProjectResources(GitCommissioningConfig config) {
        String projectName = config.getIgnitionProjectName();

        GitProjectManager.importProject(projectName);

        if (config.isImportTags()) {
            GitTagManager.importTagManager(projectName, null);
        }
        if (config.isImportThemes()) {
            GitThemeManager.importTheme(projectName);
        }
        if (config.isImportImages()) {
            GitImageManager.importImages(projectName);
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

    /**
     * Resolves the git user secret from environment variables.
     * Checks GATEWAY_GIT_USER_SECRET (direct value) first, then falls back to
     * GATEWAY_GIT_USER_SECRET_FILE (file path) for backward compatibility.
     */
    private static void resolveSecretFromEnv(GitCommissioningConfig config, boolean isSSHAuth) throws IOException {
        String secret = System.getenv("GATEWAY_GIT_USER_SECRET");
        if (secret != null) {
            config.setSecret(secret, isSSHAuth);
            return;
        }

        String secretFilePath = System.getenv("GATEWAY_GIT_USER_SECRET_FILE");
        if (secretFilePath != null) {
            config.setSecretFromFilePath(Paths.get(secretFilePath), isSSHAuth);
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
                yamlKey.equals("commissioning_importTags") || yamlKey.equals("commissioning_importImages") ||
                yamlKey.equals("commissioning_enforceBranch")) {
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

