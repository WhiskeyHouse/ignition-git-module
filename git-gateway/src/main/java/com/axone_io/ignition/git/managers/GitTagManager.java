package com.axone_io.ignition.git.managers;

import com.axone_io.ignition.git.TagExportConfig;
import com.inductiveautomation.ignition.common.JsonUtilities;
import com.inductiveautomation.ignition.common.gson.Gson;
import com.inductiveautomation.ignition.common.gson.GsonBuilder;
import com.inductiveautomation.ignition.common.gson.JsonArray;
import com.inductiveautomation.ignition.common.gson.JsonElement;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.tags.TagUtilities;
import com.inductiveautomation.ignition.common.tags.config.CollisionPolicy;
import com.inductiveautomation.ignition.common.tags.config.TagConfigurationModel;
import com.inductiveautomation.ignition.common.tags.model.TagPath;
import com.inductiveautomation.ignition.common.tags.model.TagProvider;
import com.inductiveautomation.ignition.common.tags.paths.BasicTagPath;
import com.inductiveautomation.ignition.common.tags.paths.parser.TagPathParser;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.tags.model.GatewayTagManager;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.axone_io.ignition.git.GatewayHook.context;
import static com.axone_io.ignition.git.managers.GitManager.clearDirectory;
import static com.axone_io.ignition.git.managers.GitManager.getProjectFolderPath;
import static com.inductiveautomation.ignition.common.tags.TagUtilities.TAG_GSON;

/**
 * Manages tag export/import between Ignition tag providers and the Git repository.
 *
 * <p>Supports two on-disk formats:
 * <ul>
 *   <li><b>Individual files</b> (new) — one JSON file per tag, organized in a directory
 *       hierarchy mirroring the tag tree. UDT definitions go under {@code _types_/}.</li>
 *   <li><b>Legacy single-file</b> — one {@code <provider>.json} file per provider,
 *       containing the entire tag tree.</li>
 * </ul>
 *
 * <p>Format is auto-detected on import. First export on updated code transitions the
 * repository to the individual-file format.</p>
 *
 * <p>Individual-file export strategy and UDT dependency resolution inspired by the
 * <a href="https://github.com/design-group/ignition-tag-cicd-module">Ignition Tag CI/CD Module</a>
 * by Barry-Wehmiller Design Group (Keith Gamble).</p>
 */
public class GitTagManager {
    private static final LoggerEx logger = LoggerEx.newBuilder().build(GitTagManager.class);

    private static final String TAG_CONFIG_FILENAME = ".tag-config.json";
    private static final String TYPES_DIR_NAME = "_types_";

    // Known Ignition tag types that represent UDT definitions
    private static final String TAG_TYPE_UDT_DEF = "UdtType";
    private static final String TAG_TYPE_UDT_INST = "UdtInstance";
    private static final String TAG_TYPE_FOLDER = "Folder";
    private static final String TAG_TYPE_PROVIDER = "Provider";

    // ========================== IMPORT ==========================

    /**
     * Imports tags for the given project from the repository's {@code tags/} directory.
     * Auto-detects whether the on-disk format is individual files or legacy single-file.
     */
    public static void importTagManager(String projectName, String collisionPolicyOverride) {
        logger.warn("Importing tags for project '" + projectName + "'. WARNING: This overwrites tag providers " +
                "at the gateway level and will affect ALL projects sharing the same tag providers.");
        Path projectDir = getProjectFolderPath(projectName);
        Path tagsDir = projectDir.resolve("tags");

        if (!Files.exists(tagsDir)) {
            logger.info("No tags directory found for project '" + projectName + "', skipping tag import.");
            return;
        }

        TagExportConfig config = loadTagExportConfig(tagsDir);
        // Use the RPC-provided collision policy if given, otherwise fall back to config file
        String policyStr = (collisionPolicyOverride != null && !collisionPolicyOverride.isEmpty())
                ? collisionPolicyOverride
                : config.getCollisionPolicy();
        CollisionPolicy collisionPolicy = resolveCollisionPolicy(policyStr);

        // Import tag groups first so that tags can reference them by name
        GitTagGroupManager.importTagGroups(tagsDir);

        if (detectFormat(tagsDir) == Format.INDIVIDUAL_FILES) {
            importFromIndividualFiles(tagsDir, collisionPolicy);
        } else {
            importFromLegacyFiles(tagsDir, collisionPolicy);
        }
    }

    /**
     * Legacy import: reads {@code <provider>.json} files directly from the tags directory.
     */
    private static void importFromLegacyFiles(Path tagsDir, CollisionPolicy collisionPolicy) {
        GatewayTagManager gatewayTagManager = context.getTagManager();
        File[] files = tagsDir.toFile().listFiles();
        if (files == null) return;

        for (File file : files) {
            if (!file.isFile() || !file.getName().endsWith(".json") || file.getName().equals(TAG_CONFIG_FILENAME)) {
                continue;
            }
            String providerName = FilenameUtils.removeExtension(file.getName());
            TagProvider tagProvider = gatewayTagManager.getTagProvider(providerName);
            if (tagProvider != null) {
                try {
                    String json = FileUtils.readFileToString(file, StandardCharsets.UTF_8.toString());
                    tagProvider.importTagsAsync(new BasicTagPath(""), json, "JSON", collisionPolicy, null);
                    logger.info("Imported tags for provider '" + providerName + "' (legacy format).");
                } catch (IOException e) {
                    logger.warn("Error importing legacy tags for provider '" + providerName + "'.", e);
                }
            } else {
                logger.warn("Tag provider '" + providerName + "' not found, skipping legacy import.");
            }
        }
    }

    /**
     * Individual-file import: reconstructs the tag JSON from the directory tree and imports
     * into the corresponding tag provider. UDT types are imported first in dependency order.
     */
    private static void importFromIndividualFiles(Path tagsDir, CollisionPolicy collisionPolicy) {
        GatewayTagManager gatewayTagManager = context.getTagManager();

        try (DirectoryStream<Path> providerDirs = Files.newDirectoryStream(tagsDir, Files::isDirectory)) {
            for (Path providerDir : providerDirs) {
                String providerName = providerDir.getFileName().toString();
                TagProvider tagProvider = gatewayTagManager.getTagProvider(providerName);
                if (tagProvider == null) {
                    logger.warn("Tag provider '" + providerName + "' not found, skipping individual-file import.");
                    continue;
                }

                // Import UDT types first (in dependency order)
                Path typesDir = providerDir.resolve(TYPES_DIR_NAME);
                if (Files.exists(typesDir) && Files.isDirectory(typesDir)) {
                    importUdtTypes(tagProvider, typesDir, collisionPolicy);
                }

                // Import remaining tags (everything except _types_)
                JsonObject reconstructed = readTagsFromDirectory(providerDir, true);
                if (reconstructed.entrySet().isEmpty()) {
                    logger.info("No non-UDT tags found for provider '" + providerName + "'.");
                    continue;
                }

                // Wrap in a root object for import
                JsonObject root = new JsonObject();
                root.add("tags", reconstructed);

                String jsonStr = TAG_GSON.toJson(root);
                tagProvider.importTagsAsync(new BasicTagPath(""), jsonStr, "JSON", collisionPolicy, null);
                logger.info("Imported tags for provider '" + providerName + "' (individual files).");
            }
        } catch (IOException e) {
            logger.error("Error reading provider directories during individual-file import.", e);
        }
    }

    /**
     * Imports UDT type definitions from the {@code _types_/} directory in dependency order.
     */
    private static void importUdtTypes(TagProvider tagProvider, Path typesDir, CollisionPolicy collisionPolicy) {
        JsonArray udtArray = new JsonArray();
        collectUdtFiles(typesDir, udtArray, "");

        if (udtArray.size() == 0) {
            return;
        }

        // Sort by dependencies so base types are imported first
        List<JsonObject> sorted = UdtDependencyResolver.sortByDependencies(udtArray);

        for (JsonObject udt : sorted) {
            String name = udt.has("name") ? udt.get("name").getAsString() : "unknown";
            String pathPrefix = udt.has("_pathPrefix") ? udt.get("_pathPrefix").getAsString() : "";
            // Remove the internal marker before importing
            udt.remove("_pathPrefix");
            try {
                // Build the import JSON wrapping this single UDT under _types_
                // If pathPrefix is set (e.g. "Motor"), wrap in intermediate folder nodes
                JsonObject innermost = new JsonObject();
                innermost.add(name, udt);

                JsonObject tagsObj = innermost;
                if (!pathPrefix.isEmpty()) {
                    // Build nested folders from innermost to outermost
                    // e.g. pathPrefix "Motor/Sub" -> Folder("Sub", tags={udt}) -> Folder("Motor", tags={Sub})
                    String[] segments = pathPrefix.split("/");
                    for (int i = segments.length - 1; i >= 0; i--) {
                        JsonObject folder = new JsonObject();
                        folder.addProperty("name", segments[i]);
                        folder.addProperty("tagType", TAG_TYPE_FOLDER);
                        folder.add("tags", tagsObj);
                        JsonObject outerTags = new JsonObject();
                        outerTags.add(segments[i], folder);
                        tagsObj = outerTags;
                    }
                }

                JsonObject wrapper = new JsonObject();
                wrapper.addProperty("name", TYPES_DIR_NAME);
                wrapper.addProperty("tagType", TAG_TYPE_FOLDER);
                wrapper.add("tags", tagsObj);

                JsonObject root = new JsonObject();
                JsonObject typesContainer = new JsonObject();
                typesContainer.add(TYPES_DIR_NAME, wrapper);
                root.add("tags", typesContainer);

                String jsonStr = TAG_GSON.toJson(root);
                // Block until this UDT is imported before proceeding to dependents
                tagProvider.importTagsAsync(new BasicTagPath(""), jsonStr, "JSON", collisionPolicy, null)
                    .get(30, TimeUnit.SECONDS);
                logger.info("Imported UDT type: " + name);
            } catch (Exception e) {
                logger.warn("Error importing UDT type '" + name + "'.", e);
            }
        }
    }

    /**
     * Recursively collects UDT definition files from the types directory into a JsonArray.
     */
    private static void collectUdtFiles(Path dir, JsonArray udtArray, String pathPrefix) {
        File[] files = dir.toFile().listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                String newPrefix = pathPrefix.isEmpty() ? file.getName() : pathPrefix + "/" + file.getName();
                collectUdtFiles(file.toPath(), udtArray, newPrefix);
            } else if (file.getName().endsWith(".json")) {
                try {
                    String content = Files.readString(file.toPath());
                    JsonObject udt = TAG_GSON.fromJson(content, JsonObject.class);
                    // Ensure the name field reflects the file name (without extension)
                    String tagName = FilenameUtils.removeExtension(file.getName());
                    udt.addProperty("name", tagName);
                    // Store path prefix so importUdtTypes can reconstruct nested folder hierarchy
                    if (!pathPrefix.isEmpty()) {
                        udt.addProperty("_pathPrefix", pathPrefix);
                    }
                    udtArray.add(udt);
                } catch (Exception e) {
                    logger.warn("Error reading UDT file: " + file.getAbsolutePath(), e);
                }
            }
        }
    }

    /**
     * Reconstructs a tags JSON object from the directory tree. Each subdirectory becomes a
     * folder node, each {@code .json} file becomes a tag node.
     *
     * @param dir           the directory to scan
     * @param skipTypesDir  if {@code true}, skips the {@code _types_/} directory
     * @return a JsonObject where keys are tag/folder names and values are their JSON definitions
     */
    private static JsonObject readTagsFromDirectory(Path dir, boolean skipTypesDir) {
        JsonObject tags = new JsonObject();
        File[] entries = dir.toFile().listFiles();
        if (entries == null) return tags;

        for (File entry : entries) {
            String name = entry.getName();

            // Skip config file
            if (name.equals(TAG_CONFIG_FILENAME)) continue;

            // Optionally skip _types_ directory (handled separately for UDT ordering)
            if (skipTypesDir && name.equals(TYPES_DIR_NAME)) continue;

            if (entry.isDirectory()) {
                // Recurse into subdirectory — this is a folder node
                JsonObject childTags = readTagsFromDirectory(entry.toPath(), false);
                JsonObject folder = new JsonObject();
                folder.addProperty("name", name);
                folder.addProperty("tagType", TAG_TYPE_FOLDER);
                if (childTags.entrySet().size() > 0) {
                    folder.add("tags", childTags);
                }
                tags.add(name, folder);

            } else if (name.endsWith(".json")) {
                String tagName = FilenameUtils.removeExtension(name);
                try {
                    String content = Files.readString(entry.toPath());
                    JsonObject tagObj = TAG_GSON.fromJson(content, JsonObject.class);
                    tagObj.addProperty("name", tagName);
                    tags.add(tagName, tagObj);
                } catch (Exception e) {
                    logger.warn("Error reading tag file: " + entry.getAbsolutePath(), e);
                }
            }
        }
        return tags;
    }

    // ========================== EXPORT ==========================

    /**
     * Exports all tag providers to the individual-file format under
     * {@code <projectFolder>/tags/<provider>/}.
     */
    public static void exportTag(Path projectFolderPath) {
        Path tagFolderPath = projectFolderPath.resolve("tags");

        // Load config BEFORE clearing the directory so user settings are preserved
        TagExportConfig config = loadTagExportConfig(tagFolderPath);

        clearDirectory(tagFolderPath);

        try {
            Files.createDirectories(tagFolderPath);

            for (TagProvider tagProvider : context.getTagManager().getTagProviders()) {
                String providerName = tagProvider.getName();

                if (!config.isProviderIncluded(providerName)) {
                    logger.debug("Skipping provider '" + providerName + "' (not in includedProviders).");
                    continue;
                }

                TagPath rootPath = TagPathParser.parse("");
                List<TagPath> tagPaths = new ArrayList<>();
                tagPaths.add(rootPath);

                CompletableFuture<List<TagConfigurationModel>> cfTagModels =
                        tagProvider.getTagConfigsAsync(tagPaths, true, true);
                List<TagConfigurationModel> tModels = cfTagModels.get();

                JsonObject json = TagUtilities.toJsonObject(tModels.get(0));
                JsonElement sortedJson = JsonUtilities.createDeterministicCopy(json);

                if (!sortedJson.isJsonObject()) {
                    logger.warn("Unexpected non-object JSON for provider '" + providerName + "', skipping.");
                    continue;
                }

                JsonObject providerJson = sortedJson.getAsJsonObject();

                // Create provider directory
                Path providerDir = tagFolderPath.resolve(providerName);
                Files.createDirectories(providerDir);

                // Walk the tag tree and write individual files
                writeTagsRecursively(providerJson, providerDir, config.getExcludedTagPaths(), "");
            }

            // Write the config file (preserves user settings for next import)
            writeTagExportConfig(tagFolderPath, config);

            // Export tag groups (scan classes) for all providers
            GitTagGroupManager.exportTagGroups(tagFolderPath);

        } catch (Exception e) {
            logger.error("Error exporting tags: " + e.toString(), e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Recursively walks the tag JSON tree and writes individual files.
     * <ul>
     *   <li>Folder/Provider nodes → create subdirectory, recurse into children</li>
     *   <li>UdtType nodes → write under {@code _types_/} subdirectory</li>
     *   <li>Leaf tags (AtomicTag, UdtInstance, etc.) → write {@code <name>.json}</li>
     * </ul>
     *
     * @param tagJson       the JSON object representing a tag node (may contain "tags" children)
     * @param parentDir     the filesystem directory to write into
     * @param excludedPaths list of relative paths to skip
     * @param relativePath  current path relative to provider root (for exclusion matching)
     */
    private static void writeTagsRecursively(JsonObject tagJson, Path parentDir,
                                             List<String> excludedPaths, String relativePath) {
        // Check for child tags
        if (!tagJson.has("tags")) {
            return;
        }

        JsonElement tagsElement = tagJson.get("tags");

        // Tags can be a JsonObject (keyed by name) or a JsonArray
        if (tagsElement.isJsonObject()) {
            JsonObject tagsObj = tagsElement.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : tagsObj.entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;

                String childName = entry.getKey();
                JsonObject childJson = entry.getValue().getAsJsonObject();
                String childRelPath = relativePath.isEmpty() ? childName : relativePath + "/" + childName;

                // Check exclusions
                if (isExcluded(childRelPath, excludedPaths)) {
                    logger.debug("Skipping excluded path: " + childRelPath);
                    continue;
                }

                writeTagNode(childName, childJson, parentDir, excludedPaths, childRelPath);
            }
        } else if (tagsElement.isJsonArray()) {
            JsonArray tagsArray = tagsElement.getAsJsonArray();
            for (JsonElement element : tagsArray) {
                if (!element.isJsonObject()) continue;

                JsonObject childJson = element.getAsJsonObject();
                String childName = childJson.has("name") ? childJson.get("name").getAsString() : null;
                if (childName == null) continue;

                String childRelPath = relativePath.isEmpty() ? childName : relativePath + "/" + childName;

                if (isExcluded(childRelPath, excludedPaths)) {
                    logger.debug("Skipping excluded path: " + childRelPath);
                    continue;
                }

                writeTagNode(childName, childJson, parentDir, excludedPaths, childRelPath);
            }
        }
    }

    /**
     * Writes a single tag node either as a directory (for folders/providers) or as a JSON file
     * (for leaf tags). UDT types are written into the {@code _types_/} directory.
     */
    private static void writeTagNode(String name, JsonObject tagJson, Path parentDir,
                                     List<String> excludedPaths, String relativePath) {
        String tagType = tagJson.has("tagType") ? tagJson.get("tagType").getAsString() : "";

        try {
            if (TAG_TYPE_FOLDER.equals(tagType) || TAG_TYPE_PROVIDER.equals(tagType)) {
                // Create subdirectory and recurse
                Path subDir = parentDir.resolve(name);
                Files.createDirectories(subDir);
                writeTagsRecursively(tagJson, subDir, excludedPaths, relativePath);

            } else if (TAG_TYPE_UDT_DEF.equals(tagType)) {
                // UDT type definitions go under _types_/
                // If we're already under _types_/, preserve the current directory hierarchy
                // Otherwise, move to the root _types_/ directory
                Path targetDir = isUnderTypesDir(parentDir) ? parentDir : getTypesDir(parentDir);
                writeTagFile(targetDir, name, tagJson);

            } else {
                // All other tags: AtomicTag, UdtInstance, OPC, Expression, etc.
                writeTagFile(parentDir, name, tagJson);
            }
        } catch (IOException e) {
            logger.warn("Error writing tag '" + name + "' to " + parentDir, e);
        }
    }

    /**
     * Checks if the given path is already under the {@code _types_/} directory.
     */
    private static boolean isUnderTypesDir(Path path) {
        Path current = path;
        while (current != null) {
            if (current.getFileName() != null && current.getFileName().toString().equals(TYPES_DIR_NAME)) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    /**
     * Returns (and creates) the {@code _types_/} directory at the provider root level.
     * Walks up from the current directory to find the provider root if needed.
     */
    private static Path getTypesDir(Path currentDir) throws IOException {
        // _types_ always lives directly under the provider directory.
        // The provider directory is the first directory under tags/<providerName>.
        // Since we may be nested, find the provider root by checking if parent is the tags dir.
        // Simpler approach: always put _types_ at the same level as the current directory's parent chain.
        // For simplicity, _types_ is placed at the provider root (the directory directly under tags/).
        // We pass providerDir down or use a heuristic.
        //
        // Since this method is called from within the recursion, and UDT types in Ignition
        // live under the _types_ folder at the provider root, we place the types dir
        // at the provider root. We can determine the provider root by navigating up until
        // we find the "tags" parent.
        Path dir = currentDir;
        while (dir.getParent() != null && !dir.getParent().getFileName().toString().equals("tags")) {
            dir = dir.getParent();
        }
        Path typesDir = dir.resolve(TYPES_DIR_NAME);
        Files.createDirectories(typesDir);
        return typesDir;
    }

    /**
     * Writes a tag's JSON to a file, stripping the "name" property (it's encoded in the filename).
     */
    private static void writeTagFile(Path dir, String tagName, JsonObject tagJson) throws IOException {
        Files.createDirectories(dir);

        // Create a copy without the "name" field (encoded in filename)
        JsonObject toWrite = tagJson.deepCopy();
        toWrite.remove("name");

        Path file = dir.resolve(tagName + ".json");
        String json = TAG_GSON.toJson(JsonUtilities.createDeterministicCopy(toWrite));
        Files.writeString(file, json);
    }

    // ========================== FORMAT DETECTION ==========================

    private enum Format {
        INDIVIDUAL_FILES,
        LEGACY_SINGLE_FILE
    }

    /**
     * Detects whether the tags directory uses individual files (subdirectories present)
     * or legacy format (only .json files at top level).
     */
    static Format detectFormat(Path tagsDir) {
        File[] entries = tagsDir.toFile().listFiles();
        if (entries == null) return Format.LEGACY_SINGLE_FILE;

        for (File entry : entries) {
            if (entry.isDirectory()) {
                return Format.INDIVIDUAL_FILES;
            }
        }
        return Format.LEGACY_SINGLE_FILE;
    }

    // ========================== CONFIG ==========================

    /**
     * Loads the per-project tag export config from {@code .tag-config.json}.
     * Returns defaults if the file doesn't exist or can't be parsed.
     */
    static TagExportConfig loadTagExportConfig(Path tagsDir) {
        Path configFile = tagsDir.resolve(TAG_CONFIG_FILENAME);
        if (Files.exists(configFile)) {
            try {
                String content = Files.readString(configFile);
                Gson gson = new Gson();
                TagExportConfig config = gson.fromJson(content, TagExportConfig.class);
                if (config != null) {
                    return config;
                }
            } catch (Exception e) {
                logger.warn("Error reading tag export config, using defaults.", e);
            }
        }
        return new TagExportConfig();
    }

    /**
     * Writes the tag export config to {@code .tag-config.json}.
     */
    private static void writeTagExportConfig(Path tagsDir, TagExportConfig config) {
        try {
            Path configFile = tagsDir.resolve(TAG_CONFIG_FILENAME);
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            Files.writeString(configFile, gson.toJson(config));
        } catch (IOException e) {
            logger.warn("Error writing tag export config.", e);
        }
    }

    /**
     * Maps the config's collision policy string to an Ignition {@link CollisionPolicy}.
     */
    static CollisionPolicy resolveCollisionPolicy(String policy) {
        if (policy == null) return CollisionPolicy.Overwrite;
        switch (policy.toLowerCase()) {
            case "m":
            case "merge":
                return CollisionPolicy.MergeOverwrite;
            case "a":
            case "abort":
            case "abort on collision":
                return CollisionPolicy.Abort;
            case "o":
            case "overwrite":
            default:
                return CollisionPolicy.Overwrite;
        }
    }

    private static boolean isExcluded(String relativePath, List<String> excludedPaths) {
        if (excludedPaths == null || excludedPaths.isEmpty()) return false;
        for (String excluded : excludedPaths) {
            if (relativePath.equals(excluded) || relativePath.startsWith(excluded + "/")) {
                return true;
            }
        }
        return false;
    }
}
