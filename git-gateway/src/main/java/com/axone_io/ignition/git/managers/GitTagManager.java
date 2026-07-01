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
import com.inductiveautomation.ignition.common.tags.config.TagGroupConfiguration;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    // Reserved on Windows: < > : " / \ | ? *. Encoding these (plus control chars and %)
    // lets tag names round-trip through the filesystem on every OS.
    private static final String FS_RESERVED_CHARS = "<>:\"/\\|?*";

    /**
     * Returns {@code true} for filesystem entries whose names start with {@code "."} — agent
     * state directories like {@code .omc}, {@code .claude}, {@code .cursor}, VCS metadata
     * ({@code .git}), and the internal config files ({@code .tag-config.json},
     * {@code .tag-groups.json}). Ignition rejects tag names starting with {@code "."} as
     * invalid ({@code Error_Configuration("The name '.omc' is not a valid tag name")}), so
     * filtering them out during import prevents a single stray dot-folder in the working tree
     * from derailing the rest of the provider's tag import.
     */
    static boolean isHiddenEntry(String name) {
        return name != null && name.startsWith(".");
    }

    /**
     * Encodes a tag name so it is safe to use as a filesystem path segment on any OS.
     * Reserved characters, control characters, and the percent sign itself are
     * replaced with {@code %XX} hex escapes. Names with only safe characters are
     * returned unchanged so existing repositories do not churn.
     */
    static String encodeFsName(String name) {
        if (name == null || name.isEmpty()) return name;
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '%' || c < 0x20 || FS_RESERVED_CHARS.indexOf(c) >= 0) {
                sb.append(String.format("%%%02X", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Reverses {@link #encodeFsName(String)} when reading a tag name from a path
     * segment. Unrecognised {@code %XX} sequences are left intact so legacy names
     * that happen to contain a literal {@code %} still read correctly.
     */
    static String decodeFsName(String fsName) {
        if (fsName == null || fsName.indexOf('%') < 0) return fsName;
        StringBuilder sb = new StringBuilder(fsName.length());
        int i = 0;
        while (i < fsName.length()) {
            char c = fsName.charAt(i);
            if (c == '%' && i + 2 < fsName.length()) {
                try {
                    int code = Integer.parseInt(fsName.substring(i + 1, i + 3), 16);
                    sb.append((char) code);
                    i += 3;
                    continue;
                } catch (NumberFormatException ignored) {
                    // not a valid escape — fall through and keep literal
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    // ========================== TAG GROUP RECONCILIATION (fix #3) ==========================

    /**
     * Walks a tag JSON node and remaps any {@code tagGroup} reference that is not present in
     * {@code knownGroups} to {@code fallbackGroup}, so a tag whose group could not be restored
     * imports cleanly instead of erroring. Empty/absent {@code tagGroup} values (which already
     * mean "use the provider default") are left untouched.
     *
     * @return the number of tag nodes that were remapped
     */
    static int reconcileUnknownTagGroups(JsonObject node, Set<String> knownGroups, String fallbackGroup) {
        if (node == null) {
            return 0;
        }
        int count = 0;

        if (node.has("tagGroup") && node.get("tagGroup").isJsonPrimitive()) {
            String group = node.get("tagGroup").getAsString();
            // An empty tagGroup already means "use the provider default", so leave it alone.
            if (group != null && !group.isEmpty()
                    && !group.equals(fallbackGroup)
                    && !knownGroups.contains(group)) {
                node.addProperty("tagGroup", fallbackGroup);
                count++;
            }
        }

        // Recurse into child tags, which may be an array (import shape) or an object (keyed by name).
        if (node.has("tags")) {
            JsonElement tagsEl = node.get("tags");
            if (tagsEl.isJsonArray()) {
                for (JsonElement child : tagsEl.getAsJsonArray()) {
                    if (child.isJsonObject()) {
                        count += reconcileUnknownTagGroups(child.getAsJsonObject(), knownGroups, fallbackGroup);
                    }
                }
            } else if (tagsEl.isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : tagsEl.getAsJsonObject().entrySet()) {
                    if (e.getValue().isJsonObject()) {
                        count += reconcileUnknownTagGroups(e.getValue().getAsJsonObject(), knownGroups, fallbackGroup);
                    }
                }
            }
        }

        return count;
    }

    /**
     * Fetches the tag group names currently defined on {@code tagProvider} and remaps any
     * unresolved {@code tagGroup} reference in {@code root} to {@code Default}, so tags whose
     * group could not be restored import cleanly instead of erroring. If the provider's group
     * list can't be determined, reconciliation is skipped (tags import as-is).
     */
    private static void reconcileWithProviderGroups(TagProvider tagProvider, String providerName, JsonObject root) {
        try {
            List<TagGroupConfiguration> groups = tagProvider.getTagGroupsAsync().get(10, TimeUnit.SECONDS);
            if (groups == null || groups.isEmpty()) {
                logger.warn("Could not determine tag groups for provider '" + providerName +
                        "'; importing tags without group reconciliation.");
                return;
            }
            Set<String> known = new HashSet<>();
            for (TagGroupConfiguration g : groups) {
                known.add(g.getName());
            }
            int remapped = reconcileUnknownTagGroups(root, known, "Default");
            if (remapped > 0) {
                logger.warn("Remapped " + remapped + " tag(s) in provider '" + providerName +
                        "' to the 'Default' tag group because their configured tag group is not present " +
                        "on this gateway. Restore the missing tag group(s) and re-import to keep the original rates.");
            }
        } catch (Exception e) {
            logger.warn("Could not reconcile tag groups for provider '" + providerName +
                    "'; importing tags as-is.", e);
        }
    }

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
            if (!file.isFile() || !file.getName().endsWith(".json") || isHiddenEntry(file.getName())) {
                continue;
            }
            String providerName = FilenameUtils.removeExtension(file.getName());
            if (TagExportConfig.SYSTEM_PROVIDER_NAME.equals(providerName)) {
                logger.debug("Skipping legacy import for built-in System provider.");
                continue;
            }
            TagProvider tagProvider = gatewayTagManager.getTagProvider(providerName);
            if (tagProvider != null) {
                try {
                    String json = FileUtils.readFileToString(file, StandardCharsets.UTF_8.toString());
                    // Reconcile unresolved tag groups before import (fix #3).
                    JsonObject root = TAG_GSON.fromJson(json, JsonObject.class);
                    reconcileWithProviderGroups(tagProvider, providerName, root);
                    json = TAG_GSON.toJson(root);
                    tagProvider.importTagsAsync(new BasicTagPath(""), json, "JSON", collisionPolicy, null)
                        .get(30, TimeUnit.SECONDS);
                    logger.info("Imported tags for provider '" + providerName + "' (legacy format).");
                } catch (Exception e) {
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
                if (isHiddenEntry(providerName)) {
                    logger.debug("Skipping hidden/dot directory under tags/: " + providerName);
                    continue;
                }
                if (TagExportConfig.SYSTEM_PROVIDER_NAME.equals(providerName)) {
                    logger.debug("Skipping individual-file import for built-in System provider.");
                    continue;
                }
                TagProvider tagProvider = gatewayTagManager.getTagProvider(providerName);
                if (tagProvider == null) {
                    logger.warn("Tag provider '" + providerName + "' not found, skipping individual-file import.");
                    continue;
                }

                // Import UDT types first (in dependency order)
                Path typesDir = providerDir.resolve(TYPES_DIR_NAME);
                boolean hasTypesDir = Files.exists(typesDir) && Files.isDirectory(typesDir);
                if (hasTypesDir) {
                    importUdtTypes(tagProvider, typesDir, collisionPolicy);
                }

                // Import remaining tags (everything except _types_)
                JsonObject reconstructed = readTagsFromDirectory(providerDir, true);

                // Build the set of expected root-level names (for stale tag cleanup)
                Set<String> expectedRootNames = new HashSet<>(reconstructed.keySet());
                if (hasTypesDir) {
                    expectedRootNames.add(TYPES_DIR_NAME);
                }

                if (!reconstructed.entrySet().isEmpty()) {
                    // Convert tags from object format to array format for importTagsAsync
                    JsonArray tagsArray = new JsonArray();
                    for (Map.Entry<String, JsonElement> entry : reconstructed.entrySet()) {
                        tagsArray.add(entry.getValue());
                    }

                    JsonObject root = new JsonObject();
                    root.add("tags", tagsArray);

                    // Remap any tag referencing a group that isn't present on this gateway so the
                    // import doesn't error the tag (fix #3).
                    reconcileWithProviderGroups(tagProvider, providerName, root);

                    String jsonStr = TAG_GSON.toJson(root);
                    var tagResult = tagProvider.importTagsAsync(new BasicTagPath(""), jsonStr, "JSON", collisionPolicy, null)
                        .get(30, TimeUnit.SECONDS);
                    logger.info("Import result for provider '" + providerName + "': " + tagResult);
                }

                // Remove root-level tags that exist in the provider but not on disk
                removeStaleRootTags(tagProvider, providerName, expectedRootNames);
            }
        } catch (Exception e) {
            logger.error("Error during individual-file tag import.", e);
        }
    }

    /**
     * Removes root-level tags from the provider that are not in the expected set.
     * This ensures the provider matches the on-disk state after import.
     */
    private static void removeStaleRootTags(TagProvider tagProvider, String providerName,
                                            Set<String> expectedRootNames) {
        try {
            var browseResults = tagProvider.browseAsync(new BasicTagPath(""), null)
                    .get(30, TimeUnit.SECONDS);

            if (browseResults == null || browseResults.getResults() == null) {
                return;
            }

            List<TagPath> toRemove = new ArrayList<>();
            for (var node : browseResults.getResults()) {
                String nodeName = node.getName();
                if (nodeName == null) {
                    continue;
                }
                if (!expectedRootNames.contains(nodeName)) {
                    toRemove.add(new BasicTagPath("", List.of(nodeName)));
                }
            }

            if (!toRemove.isEmpty()) {
                logger.info("Removing " + toRemove.size() + " stale root tag(s) from provider '"
                        + providerName + "': " + toRemove);
                tagProvider.removeTagConfigsAsync(toRemove).get(30, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            logger.warn("Error removing stale tags from provider '" + providerName + "'.", e);
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

        // Make names unique by including path prefix so the dependency resolver
        // doesn't deduplicate UDTs at different paths with the same base name.
        // E.g. "ScannerCSVHandler" at paths WMS/, Util/, Equipment/DataScanners/
        // become "WMS/ScannerCSVHandler", "Util/ScannerCSVHandler", etc.
        for (int i = 0; i < udtArray.size(); i++) {
            JsonObject udt = udtArray.get(i).getAsJsonObject();
            String name = udt.has("name") ? udt.get("name").getAsString() : "";
            String pp = udt.has("_pathPrefix") ? udt.get("_pathPrefix").getAsString() : "";
            if (!pp.isEmpty()) {
                udt.addProperty("name", pp + "/" + name);
            }
        }

        // Sort by dependencies so base types are imported first
        List<JsonObject> sorted = UdtDependencyResolver.sortByDependencies(udtArray);
        logger.info("Collected " + udtArray.size() + " UDT file(s), " + sorted.size() + " after dependency resolution.");

        for (JsonObject udt : sorted) {
            String fullName = udt.has("name") ? udt.get("name").getAsString() : "unknown";
            // Extract actual tag name and path prefix from the full path name
            String name;
            String pathPrefix;
            if (fullName.contains("/")) {
                name = fullName.substring(fullName.lastIndexOf('/') + 1);
                pathPrefix = fullName.substring(0, fullName.lastIndexOf('/'));
            } else {
                name = fullName;
                pathPrefix = "";
            }
            // Remove internal marker and restore the original tag name for Ignition
            udt.remove("_pathPrefix");
            udt.addProperty("name", name);
            try {
                // Build the import JSON wrapping this single UDT under _types_
                // All "tags" fields must be arrays for importTagsAsync compatibility

                // Innermost: the UDT definition itself
                JsonArray innermostArray = new JsonArray();
                innermostArray.add(udt);

                JsonElement currentTags = innermostArray;
                if (!pathPrefix.isEmpty()) {
                    // Build nested folders from innermost to outermost
                    // e.g. pathPrefix "MES/Changeover" -> Folder("Changeover", tags=[udt]) -> Folder("MES", tags=[Changeover])
                    String[] segments = pathPrefix.split("/");
                    for (int i = segments.length - 1; i >= 0; i--) {
                        JsonObject folder = new JsonObject();
                        folder.addProperty("name", segments[i]);
                        folder.addProperty("tagType", TAG_TYPE_FOLDER);
                        folder.add("tags", currentTags);
                        JsonArray outerArray = new JsonArray();
                        outerArray.add(folder);
                        currentTags = outerArray;
                    }
                }

                JsonObject wrapper = new JsonObject();
                wrapper.addProperty("name", TYPES_DIR_NAME);
                wrapper.addProperty("tagType", TAG_TYPE_FOLDER);
                wrapper.add("tags", currentTags);

                JsonArray typesArray = new JsonArray();
                typesArray.add(wrapper);

                JsonObject root = new JsonObject();
                root.add("tags", typesArray);

                // UDT member tags can also reference custom groups — reconcile before import (fix #3).
                reconcileWithProviderGroups(tagProvider, tagProvider.getName(), root);

                String jsonStr = TAG_GSON.toJson(root);
                logger.info("Importing UDT type '" + name + "' with pathPrefix='" + pathPrefix + "', JSON length=" + jsonStr.length());
                logger.debug("UDT import JSON: " + jsonStr);
                // Block until this UDT is imported before proceeding to dependents
                var udtResult = tagProvider.importTagsAsync(new BasicTagPath(""), jsonStr, "JSON", collisionPolicy, null)
                    .get(30, TimeUnit.SECONDS);
                logger.info("UDT import result for '" + name + "': " + udtResult);
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
            if (isHiddenEntry(file.getName())) continue;
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
    static JsonObject readTagsFromDirectory(Path dir, boolean skipTypesDir) {
        JsonObject tags = new JsonObject();
        File[] entries = dir.toFile().listFiles();
        if (entries == null) return tags;

        for (File entry : entries) {
            String fsName = entry.getName();

            // Skip hidden/dot entries — covers .tag-config.json, .tag-groups.json, and any
            // agent-state directories like .omc/ or .claude/ that may be present in the
            // working tree. Ignition rejects tag names starting with "." as invalid, so a
            // single stray dot-folder would otherwise abort the entire provider import.
            if (isHiddenEntry(fsName)) continue;

            // Optionally skip _types_ directory (handled separately for UDT ordering)
            if (skipTypesDir && fsName.equals(TYPES_DIR_NAME)) continue;

            if (entry.isDirectory()) {
                // Recurse into subdirectory — this is a folder node.
                // Decode the directory name to recover the original tag name.
                String name = decodeFsName(fsName);
                JsonObject childTags = readTagsFromDirectory(entry.toPath(), false);
                JsonObject folder = new JsonObject();
                folder.addProperty("name", name);
                folder.addProperty("tagType", TAG_TYPE_FOLDER);
                if (childTags.entrySet().size() > 0) {
                    // Convert to array format for importTagsAsync compatibility
                    JsonArray childArray = new JsonArray();
                    for (Map.Entry<String, JsonElement> child : childTags.entrySet()) {
                        childArray.add(child.getValue());
                    }
                    folder.add("tags", childArray);
                }
                tags.add(name, folder);

            } else if (fsName.endsWith(".json")) {
                // Strip extension first, then decode — the .json suffix is always literal.
                String tagName = decodeFsName(FilenameUtils.removeExtension(fsName));
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

        // Snapshot existing group files BEFORE clearing so a failed group fetch below can fall
        // back to the prior content instead of leaving the tags directory with no group files.
        Map<String, String> preservedGroups = GitTagGroupManager.snapshotExistingGroupFiles(tagFolderPath);

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

            // Export tag groups (scan classes) for the same included providers as the tag files.
            // Passing the pre-clear snapshot makes this non-destructive: providers whose groups can't
            // be fetched keep their prior file.
            GitTagGroupManager.exportTagGroups(tagFolderPath, preservedGroups, config);

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
                // Create subdirectory and recurse — encode name for filesystem safety
                Path subDir = parentDir.resolve(encodeFsName(name));
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

        Path file = dir.resolve(encodeFsName(tagName) + ".json");
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
