package com.axone_io.ignition.git.managers;

import com.inductiveautomation.ignition.common.config.BasicPropertySet;
import com.inductiveautomation.ignition.common.gson.GsonBuilder;
import com.inductiveautomation.ignition.common.gson.JsonElement;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.gson.Gson;
import com.inductiveautomation.ignition.common.tags.config.CommonTagGroupProperties;
import com.inductiveautomation.ignition.common.tags.config.TagGroupConfiguration;
import com.inductiveautomation.ignition.common.tags.config.TagGroupMode;
import com.inductiveautomation.ignition.common.tags.model.TagProvider;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.tags.model.GatewayTagManager;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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

    // ========================== EXPORT ==========================

    /**
     * Exports tag groups for all tag providers to {@code tags/<provider>/.tag-groups.json}.
     * Called automatically during {@link GitTagManager#exportTag(Path)}.
     */
    public static void exportTagGroups(Path tagsDir) {
        GatewayTagManager gatewayTagManager = context.getTagManager();

        for (TagProvider tagProvider : gatewayTagManager.getTagProviders()) {
            String providerName = tagProvider.getName();
            Path providerDir = tagsDir.resolve(providerName);

            try {
                List<TagGroupConfiguration> tagGroups = tagProvider.getTagGroupsAsync()
                        .get(30, TimeUnit.SECONDS);

                if (tagGroups == null || tagGroups.isEmpty()) {
                    logger.debug("No tag groups found for provider '" + providerName + "', skipping export.");
                    continue;
                }

                JsonObject root = new JsonObject();
                com.inductiveautomation.ignition.common.gson.JsonArray groupsArray =
                        new com.inductiveautomation.ignition.common.gson.JsonArray();

                for (TagGroupConfiguration group : tagGroups) {
                    JsonObject groupJson = serializeTagGroup(group);
                    groupsArray.add(groupJson);
                }

                root.add("tagGroups", groupsArray);

                Files.createDirectories(providerDir);
                Path groupsFile = providerDir.resolve(TAG_GROUPS_FILENAME);
                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                Files.writeString(groupsFile, gson.toJson(root));

                logger.info("Exported " + tagGroups.size() + " tag group(s) for provider '" + providerName + "'.");

            } catch (Exception e) {
                logger.warn("Error exporting tag groups for provider '" + providerName + "'.", e);
            }
        }
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
        if (obj.has("oneShot")) {
            props.set(CommonTagGroupProperties.OneShot, obj.get("oneShot").getAsBoolean());
        }
        if (obj.has("drivingComparisonValue")) {
            props.set(CommonTagGroupProperties.DrivingComparisonValue, obj.get("drivingComparisonValue").getAsDouble());
        }

        return new TagGroupConfiguration(props, isNew);
    }
}
