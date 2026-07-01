package com.axone_io.ignition.git.managers;

import com.inductiveautomation.ignition.common.config.BasicPropertySet;
import com.inductiveautomation.ignition.common.gson.GsonBuilder;
import com.inductiveautomation.ignition.common.gson.JsonElement;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.gson.Gson;
import com.inductiveautomation.ignition.common.sqltags.model.types.ScanClassComparison;
import com.inductiveautomation.ignition.common.tags.config.CommonTagGroupProperties;
import com.inductiveautomation.ignition.common.tags.config.TagGroupConfiguration;
import com.inductiveautomation.ignition.common.tags.config.TagGroupMode;
import com.inductiveautomation.ignition.common.tags.model.TagProvider;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.tags.model.GatewayTagManager;
import com.axone_io.ignition.git.TagExportConfig;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import static com.axone_io.ignition.git.GatewayHook.context;

/**
 * Manages export/import of tag group (scan class) configurations between
 * Ignition tag providers and the Git repository.
 *
 * <p>Tag groups are provider-level resources that define polling behaviour for tags
 * (rate, mode, driven expression, one-shot, etc.). Tags reference their group by name
 * via the {@code tagGroup} property, but the group configuration itself is not included
 * in a standard tag export, causing "group not found" issues when restoring to a fresh
 * gateway.</p>
 *
 * <p>On-disk format: {@code tags/<providerName>/.tag-groups.json}
 * <pre>
 * {
 *   "tagGroups": [
 *     {
 *       "name": "Default",
 *       "rate": 1000,
 *       "mode": "Leased",
 *       "leasedRate": 500,
 *       "drivingExpression": "",
 *       "drivingComparison": "Equality",
 *       "drivingComparisonValue": 1.0,
 *       "oneShot": false
 *     }
 *   ]
 * }
 * </pre>
 * </p>
 */
public class GitTagGroupManager {
    private static final LoggerEx logger = LoggerEx.newBuilder().build(GitTagGroupManager.class);

    static final String TAG_GROUPS_FILENAME = ".tag-groups.json";

    // ========================== NON-DESTRUCTIVE WRITE (fix #1) ==========================

    /**
     * Reads the existing {@code .tag-groups.json} for every provider under {@code tagsDir}
     * into an in-memory map keyed by provider name. Must be called <em>before</em> the tags
     * directory is cleared so a failed fresh fetch can fall back to the prior content.
     */
    static Map<String, String> snapshotExistingGroupFiles(Path tagsDir) {
        Map<String, String> snapshot = new HashMap<>();
        if (tagsDir == null || !Files.exists(tagsDir)) {
            return snapshot;
        }
        try (DirectoryStream<Path> providerDirs = Files.newDirectoryStream(tagsDir, Files::isDirectory)) {
            for (Path providerDir : providerDirs) {
                Path groupsFile = providerDir.resolve(TAG_GROUPS_FILENAME);
                if (Files.exists(groupsFile)) {
                    try {
                        snapshot.put(providerDir.getFileName().toString(), Files.readString(groupsFile));
                    } catch (IOException e) {
                        logger.warn("Could not read existing tag groups file for preservation: " + groupsFile, e);
                    }
                }
            }
        } catch (IOException e) {
            logger.warn("Could not snapshot existing tag group files under " + tagsDir, e);
        }
        return snapshot;
    }

    /**
     * Writes group files without ever losing prior data. For each provider, the fresh
     * content is written when the fetch succeeded (non-{@code null}); otherwise the
     * previously-snapshotted content is restored. Providers with neither are skipped.
     */
    static void writeGroupFiles(Path tagsDir, Map<String, String> freshByProvider,
                                Map<String, String> preservedByProvider) {
        Map<String, String> fresh = freshByProvider == null ? new HashMap<>() : freshByProvider;
        Map<String, String> preserved = preservedByProvider == null ? new HashMap<>() : preservedByProvider;

        TreeSet<String> providers = new TreeSet<>();
        providers.addAll(fresh.keySet());
        providers.addAll(preserved.keySet());

        for (String provider : providers) {
            String content = fresh.get(provider);
            boolean preservedUsed = false;
            if (content == null) {
                // No fresh content (fetch failed/empty) — fall back to the prior file so a
                // transient failure never deletes committed group definitions.
                content = preserved.get(provider);
                preservedUsed = content != null;
            }
            if (content == null) {
                continue;
            }
            try {
                Path providerDir = tagsDir.resolve(provider);
                Files.createDirectories(providerDir);
                Files.writeString(providerDir.resolve(TAG_GROUPS_FILENAME), content);
                if (preservedUsed) {
                    logger.warn("Preserved existing tag groups for provider '" + provider +
                            "' because the fresh export produced none (fetch failed or returned empty).");
                }
            } catch (IOException e) {
                logger.warn("Error writing tag groups file for provider '" + provider + "'.", e);
            }
        }
    }

    // ========================== DIAGNOSTIC (#2) ==========================

    /**
     * Diagnostic probe for the "empty tag groups on export" issue (#2). For every tag provider
     * it calls {@code getTagGroupsAsync()} exactly the way {@link #exportTagGroups} does and
     * reports, per provider: elapsed time, group count, the group names, or the exception/timeout.
     *
     * <p>Runnable live from the Designer/Gateway script console:
     * {@code print system.git.diagnoseTagGroups()}. The full report is also written to the
     * gateway log at INFO. This is a temporary diagnostic — remove once #2 is resolved.</p>
     *
     * @return a human-readable multi-line report
     */
    public static String diagnoseTagGroups() {
        GatewayTagManager gatewayTagManager = context.getTagManager();
        StringBuilder sb = new StringBuilder();
        sb.append("=== Tag Group Diagnostic ===\n");

        List<TagProvider> providers = gatewayTagManager.getTagProviders();
        sb.append("Providers found: ").append(providers.size()).append("\n");

        for (TagProvider tagProvider : providers) {
            String providerName = tagProvider.getName();
            sb.append("\nProvider '").append(providerName).append("'");
            if (TagExportConfig.SYSTEM_PROVIDER_NAME.equals(providerName)) {
                sb.append(" [System — skipped by export]");
            }
            sb.append(":\n");

            long start = System.currentTimeMillis();
            try {
                List<TagGroupConfiguration> groups = tagProvider.getTagGroupsAsync()
                        .get(30, TimeUnit.SECONDS);
                long ms = System.currentTimeMillis() - start;

                if (groups == null) {
                    sb.append("  -> NULL result after ").append(ms).append("ms\n");
                } else if (groups.isEmpty()) {
                    sb.append("  -> EMPTY list after ").append(ms).append("ms " +
                            "(unexpected: built-in Default groups should always be present)\n");
                } else {
                    List<String> names = new ArrayList<>();
                    for (TagGroupConfiguration g : groups) {
                        names.add(g.getName());
                    }
                    sb.append("  -> ").append(groups.size()).append(" group(s) in ")
                            .append(ms).append("ms: ").append(names).append("\n");
                }
            } catch (Exception e) {
                long ms = System.currentTimeMillis() - start;
                sb.append("  -> ").append(e.getClass().getSimpleName())
                        .append(" after ").append(ms).append("ms: ").append(e.getMessage());
                Throwable cause = e.getCause();
                if (cause != null) {
                    sb.append(" | cause: ").append(cause.getClass().getSimpleName())
                            .append(": ").append(cause.getMessage());
                }
                sb.append("\n");
            }
        }

        String report = sb.toString();
        logger.info(report);
        return report;
    }

    // ========================== EXPORT ==========================

    /**
     * Exports tag groups for all tag providers to {@code tags/<provider>/.tag-groups.json}.
     * Called automatically during {@link GitTagManager#exportTag(Path)}.
     *
     * <p>Non-destructive: group JSON is built entirely in memory first, then handed to
     * {@link #writeGroupFiles(Path, Map, Map)}. A provider whose group fetch fails or returns
     * empty is left out of the fresh map, so its previously-committed group file (from
     * {@code preservedByProvider}) is restored rather than lost. An empty result is treated as
     * a failed capture because every provider always has the built-in Default groups.</p>
     *
     * @param tagsDir             the {@code tags/} directory to write into
     * @param preservedByProvider group-file contents snapshotted before the tags dir was cleared
     */
    public static void exportTagGroups(Path tagsDir, Map<String, String> preservedByProvider) {
        GatewayTagManager gatewayTagManager = context.getTagManager();
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Map<String, String> freshByProvider = new HashMap<>();

        for (TagProvider tagProvider : gatewayTagManager.getTagProviders()) {
            String providerName = tagProvider.getName();

            if (TagExportConfig.SYSTEM_PROVIDER_NAME.equals(providerName)) {
                logger.debug("Skipping tag group export for built-in System provider.");
                continue;
            }

            try {
                List<TagGroupConfiguration> tagGroups = tagProvider.getTagGroupsAsync()
                        .get(30, TimeUnit.SECONDS);

                if (tagGroups == null || tagGroups.isEmpty()) {
                    // Treat empty as a failed capture and preserve any existing file: every
                    // provider always has at least the built-in Default/Default Historical groups.
                    logger.warn("Tag group fetch for provider '" + providerName + "' returned no groups; " +
                            "preserving any existing group file rather than deleting it.");
                    continue;
                }

                JsonObject root = new JsonObject();
                com.inductiveautomation.ignition.common.gson.JsonArray groupsArray =
                        new com.inductiveautomation.ignition.common.gson.JsonArray();

                for (TagGroupConfiguration group : tagGroups) {
                    groupsArray.add(serializeTagGroup(group));
                }

                root.add("tagGroups", groupsArray);
                freshByProvider.put(providerName, gson.toJson(root));

                logger.info("Captured " + tagGroups.size() + " tag group(s) for provider '" + providerName + "'.");

            } catch (Exception e) {
                logger.warn("Error exporting tag groups for provider '" + providerName +
                        "'; preserving any existing group file rather than deleting it.", e);
            }
        }

        // Never restore a System-provider group file (System is intentionally omitted from tracking).
        Map<String, String> preserved = new HashMap<>(preservedByProvider == null ? new HashMap<>() : preservedByProvider);
        preserved.remove(TagExportConfig.SYSTEM_PROVIDER_NAME);

        writeGroupFiles(tagsDir, freshByProvider, preserved);
    }

    /**
     * Serializes a {@link TagGroupConfiguration} to a {@link JsonObject}.
     * Only the properties defined in {@link CommonTagGroupProperties} are written.
     */
    static JsonObject serializeTagGroup(TagGroupConfiguration group) {
        JsonObject obj = new JsonObject();
        obj.addProperty("name", group.getName());

        if (group.getConfig() != null) {
            // Rate (milliseconds)
            Integer rate = group.getConfig().getOrElse(CommonTagGroupProperties.Rate, null);
            if (rate != null) obj.addProperty("rate", rate);

            // Tag group mode (Leased, DrivenByValue, etc.)
            TagGroupMode mode = group.getConfig().getOrElse(CommonTagGroupProperties.Mode, null);
            if (mode != null) obj.addProperty("mode", mode.name());

            // Leased scan rate (used when a subscription is active)
            Integer leasedRate = group.getConfig().getOrElse(CommonTagGroupProperties.LeasedRate, null);
            if (leasedRate != null) obj.addProperty("leasedRate", leasedRate);

            // Expression used to drive tag updates (driven mode)
            String drivingExpr = group.getConfig().getOrElse(CommonTagGroupProperties.DrivingExpression, null);
            if (drivingExpr != null) obj.addProperty("drivingExpression", drivingExpr);

            // One-shot: group fires once per scan, then stops until re-triggered
            Boolean oneShot = group.getConfig().getOrElse(CommonTagGroupProperties.OneShot, null);
            if (oneShot != null) obj.addProperty("oneShot", oneShot);

            // Driving comparison properties
            Object drivingComparison = group.getConfig().getOrElse(CommonTagGroupProperties.DrivingComparison, null);
            if (drivingComparison != null) obj.addProperty("drivingComparison", drivingComparison.toString());

            Double drivingComparisonValue = group.getConfig().getOrElse(CommonTagGroupProperties.DrivingComparisonValue, null);
            if (drivingComparisonValue != null) obj.addProperty("drivingComparisonValue", drivingComparisonValue);
        }

        return obj;
    }

    // ========================== IMPORT ==========================

    /**
     * Imports tag groups from {@code tags/<provider>/.tag-groups.json} for all providers
     * that have a groups file present. Existing groups with the same name are updated;
     * new groups are created.
     */
    public static void importTagGroups(Path tagsDir) {
        GatewayTagManager gatewayTagManager = context.getTagManager();

        if (!Files.exists(tagsDir)) {
            logger.info("No tags directory found, skipping tag group import.");
            return;
        }

        try (DirectoryStream<Path> providerDirs = Files.newDirectoryStream(tagsDir, Files::isDirectory)) {
            for (Path providerDir : providerDirs) {
                String providerName = providerDir.getFileName().toString();
                // Skip hidden/dot dirs (.omc, .claude, .git, etc.) that may sit alongside provider dirs.
                if (providerName.startsWith(".")) {
                    continue;
                }
                Path groupsFile = providerDir.resolve(TAG_GROUPS_FILENAME);

                if (!Files.exists(groupsFile)) {
                    logger.debug("No tag groups file for provider '" + providerName + "', skipping.");
                    continue;
                }

                TagProvider tagProvider = gatewayTagManager.getTagProvider(providerName);
                if (tagProvider == null) {
                    logger.warn("Tag provider '" + providerName + "' not found, skipping tag group import.");
                    continue;
                }

                try {
                    String content = Files.readString(groupsFile);
                    Gson gson = new Gson();
                    JsonObject root = gson.fromJson(content, JsonObject.class);

                    if (!root.has("tagGroups") || !root.get("tagGroups").isJsonArray()) {
                        logger.warn("Invalid tag groups file for provider '" + providerName + "', skipping.");
                        continue;
                    }

                    // Fetch existing group names to determine which are new vs updates
                    List<String> existingGroupNames = new ArrayList<>();
                    try {
                        List<TagGroupConfiguration> existing = tagProvider.getTagGroupsAsync()
                                .get(10, TimeUnit.SECONDS);
                        if (existing != null) {
                            for (TagGroupConfiguration tg : existing) {
                                existingGroupNames.add(tg.getName());
                            }
                        }
                    } catch (Exception e) {
                        logger.warn("Could not fetch existing tag groups for provider '" + providerName +
                                "', will treat all as new.", e);
                    }

                    List<TagGroupConfiguration> toSave = new ArrayList<>();
                    com.inductiveautomation.ignition.common.gson.JsonArray groupsArray =
                            root.getAsJsonArray("tagGroups");

                    for (JsonElement element : groupsArray) {
                        if (!element.isJsonObject()) continue;
                        JsonObject groupJson = element.getAsJsonObject();
                        TagGroupConfiguration config = deserializeTagGroup(groupJson, existingGroupNames);
                        if (config != null) {
                            toSave.add(config);
                        }
                    }

                    if (!toSave.isEmpty()) {
                        tagProvider.saveTagGroupsAsync(toSave).get(30, TimeUnit.SECONDS);
                        logger.info("Imported " + toSave.size() + " tag group(s) for provider '" + providerName + "'.");
                    }

                } catch (Exception e) {
                    logger.warn("Error importing tag groups for provider '" + providerName + "'.", e);
                }
            }
        } catch (IOException e) {
            logger.error("Error reading provider directories during tag group import.", e);
        }
    }

    /**
     * Deserializes a {@link TagGroupConfiguration} from its JSON representation.
     *
     * @param obj                the JSON object for this group
     * @param existingGroupNames names of groups already on the provider (to set the isNew flag)
     * @return the configuration, or {@code null} if the JSON is invalid
     */
    static TagGroupConfiguration deserializeTagGroup(JsonObject obj, List<String> existingGroupNames) {
        if (!obj.has("name")) {
            logger.warn("Tag group JSON missing 'name' field, skipping.");
            return null;
        }

        String name = obj.get("name").getAsString();
        boolean isNew = !existingGroupNames.contains(name);

        BasicPropertySet props = new BasicPropertySet();
        // Name must be present in the PropertySet so the TagGroupConfiguration(PropertySet, boolean)
        // constructor can read it back correctly via CommonTagGroupProperties.Name.
        props.set(CommonTagGroupProperties.Name, name);

        if (obj.has("rate")) {
            props.set(CommonTagGroupProperties.Rate, obj.get("rate").getAsInt());
        }
        if (obj.has("mode")) {
            try {
                TagGroupMode mode = TagGroupMode.valueOf(obj.get("mode").getAsString());
                props.set(CommonTagGroupProperties.Mode, mode);
            } catch (IllegalArgumentException e) {
                logger.warn("Unknown tag group mode '" + obj.get("mode").getAsString() + "' for group '" + name + "', using default.");
            }
        }
        if (obj.has("leasedRate")) {
            props.set(CommonTagGroupProperties.LeasedRate, obj.get("leasedRate").getAsInt());
        }
        if (obj.has("drivingExpression")) {
            props.set(CommonTagGroupProperties.DrivingExpression, obj.get("drivingExpression").getAsString());
        }
        if (obj.has("drivingComparison")) {
            try {
                ScanClassComparison comparison = ScanClassComparison.valueOf(obj.get("drivingComparison").getAsString());
                props.set(CommonTagGroupProperties.DrivingComparison, comparison);
            } catch (IllegalArgumentException e) {
                logger.warn("Unknown drivingComparison '" + obj.get("drivingComparison").getAsString() + "' for group '" + name + "', using default.");
            }
        }
        if (obj.has("drivingComparisonValue")) {
            props.set(CommonTagGroupProperties.DrivingComparisonValue, obj.get("drivingComparisonValue").getAsDouble());
        }
        if (obj.has("oneShot")) {
            props.set(CommonTagGroupProperties.OneShot, obj.get("oneShot").getAsBoolean());
        }

        return new TagGroupConfiguration(props, isNew);
    }
}
