package com.axone_io.ignition.git.managers;

import com.inductiveautomation.ignition.common.StringPath;
import com.inductiveautomation.ignition.common.gson.Gson;
import com.inductiveautomation.ignition.common.gson.JsonSyntaxException;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceCollectionInvalidException;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceCollectionManifest;
import com.inductiveautomation.ignition.common.resourcecollection.Resource;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceBuilder;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceManifest;
import com.inductiveautomation.ignition.common.resourcecollection.ResourcePath;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceType;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.project.ProjectManager;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static com.axone_io.ignition.git.GatewayHook.context;
import static com.axone_io.ignition.git.managers.GitManager.getProjectFolderPath;

public class GitProjectManager {
    private final static LoggerEx logger = LoggerEx.newBuilder().build(GitProjectManager.class);

    public static void importProject(String projectName) {
        ProjectManager projectManager = context.getProjectManager();
        Path projectDir = getProjectFolderPath(projectName);

        try {
            Set<Resource> resources = importFromFolder(projectDir, projectName, installedModuleIds());
            ResourceCollectionManifest projectManifest = loadProjectManifest(projectDir);
            projectManager.createOrReplace(projectName, projectManifest, new ArrayList(resources));

        } catch (Exception e) {
            logger.error("An error occurred while importing '" + projectName + "' project.", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Top-level dirs of an 8.3 project that can hold resources: a resource lives at
     * {@code <moduleId>/<resourceTypeId>/...}, so the first segment must be a module id.
     * Taken from the gateway's module manager because it is the gateway's own list of
     * installed modules. Project resource types are registered in the Designer, not on the
     * gateway, and the gateway ResourceTypeMetaRegistry covers config resources only, so
     * neither is a usable list here. getModules() rather than getActiveModules(): a module
     * that is installed but faulted still owns its resources, and dropping them would delete
     * them. "ignition" is the platform id (script-python, named-query, ...), not a module.
     */
    static Set<String> installedModuleIds() {
        Set<String> ids = new HashSet<>();
        ids.add("ignition");
        context.getModuleManager().getModules().forEach(m -> ids.add(m.getInfo().getId()));
        return ids;
    }

    /**
     * Reads every file under the allowed top-level dirs, keyed by its project-relative path
     * with "/" separators. Root files and any other top-level dir (.git, .github, tags,
     * deploy, docs, ...) are skipped before anything under them is listed or read. Symlinks are
     * skipped, never followed, so a loop or a link out of the repo cannot hang or widen the walk.
     */
    public static Set<Map.Entry<String, byte[]>> listFiles(Path projectPath, Set<String> topLevelDirs) {
        Set<Map.Entry<String, byte[]>> resources = new HashSet<>();
        Stack<File> stack = new Stack<>();

        File[] topLevel = projectPath.toFile().listFiles();
        if (topLevel != null) {
            for (File file : topLevel) {
                if (Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS) && topLevelDirs.contains(file.getName())) {
                    stack.push(file);
                }
            }
        }

        while (!stack.empty()) {
            File current = stack.pop();

            File[] files = current.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (Files.isSymbolicLink(file.toPath())) {
                        continue;
                    }
                    if (file.isDirectory()) {
                        stack.push(file);
                    } else {
                        try {
                            String path = file.getAbsolutePath().replace(projectPath.toFile().getAbsolutePath(), "");
                            path = path.substring(1);
                            path = path.replace("\\", "/");
                            resources.add(new AbstractMap.SimpleEntry<>(path, Files.readAllBytes(file.toPath())));
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
            }
        }
        return resources;
    }

    /**
     * Fails the import when a top-level dir that listFiles would skip holds a resource.json.
     * The allowlist is the gateway's registered modules, so an import that runs before a module
     * has registered would drop that module's resources, and the redundancy sync would send them
     * to the backup as deletions. Aborting before anything is built leaves the project as it is.
     * .git is never walked and symlinks are not followed. Each dir's walk lists names only and
     * stops at the first resource.json.
     */
    static void refuseSkippedResourceDirs(Path projectPath, Set<String> moduleIds) {
        File[] topLevel = projectPath.toFile().listFiles();
        if (topLevel == null) {
            return;
        }
        List<String> offending = new ArrayList<>();
        for (File dir : topLevel) {
            if (Files.isDirectory(dir.toPath(), LinkOption.NOFOLLOW_LINKS) && !moduleIds.contains(dir.getName())
                    && !".git".equals(dir.getName()) && containsResourceJson(dir)) {
                offending.add(dir.getName());
            }
        }
        if (!offending.isEmpty()) {
            Collections.sort(offending);
            throw new IllegalStateException("Folder(s) " + offending + " in " + projectPath
                    + " contain Ignition resources but no installed module has that id; refusing to"
                    + " import so those resources are not deleted. Check that the module is installed"
                    + " and has started, then import again.");
        }
    }

    private static boolean containsResourceJson(File dir) {
        Stack<File> stack = new Stack<>();
        stack.push(dir);
        while (!stack.empty()) {
            File[] entries = stack.pop().listFiles();
            if (entries == null) {
                continue;
            }
            for (File entry : entries) {
                if (Files.isSymbolicLink(entry.toPath())) {
                    continue;
                }
                if (entry.isDirectory()) {
                    stack.push(entry);
                } else if ("resource.json".equals(entry.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    public static Set<Resource> importFromFolder(Path projectPath, String projectName, Set<String> moduleIds) throws ResourceCollectionInvalidException, IOException {
        Set<Resource> resources = new HashSet<>();
        Set<StringPath> createdFolders = new HashSet<>();

        refuseSkippedResourceDirs(projectPath, moduleIds);
        Set<Map.Entry<String, byte[]>> files = listFiles(projectPath, moduleIds);

        // Pre-seed with every directory that has its own resource.json so
        // createParentFolderResources never emits a folder resource for a path
        // that is itself a real resource. Without this, a nested resource
        // (e.g. a Perspective view inside another view's folder) produces both
        // a folder and a data resource at the parent path, and a cold
        // createOrReplace fails with "resource already exists".
        Set<StringPath> resourceJsonDirs = files.stream()
                .filter(e -> e.getKey().endsWith("/resource.json"))
                .map(e -> StringPath.parse(StringUtils.substringBeforeLast(e.getKey(), "/")))
                .collect(Collectors.toSet());
        createdFolders.addAll(resourceJsonDirs);

        files.stream().collect(Collectors.groupingBy(e -> StringUtils.substringBeforeLast(e.getKey(), "/")))
                .forEach((resourcePath, listOfFileNodes) -> {
                    StringPath stringPath = StringPath.parse(resourcePath);
                    resources.addAll(createParentFolderResources(projectName, stringPath, createdFolders));
                    String manifestPath = String.format("%s/%s", resourcePath, "resource.json");

                    ResourceManifest resourceManifest = removeResourceManifest(manifestPath, listOfFileNodes);

                    if (resourceManifest != null) {

                        Map<String, byte[]> dataMap = createDataMap(resourceManifest, listOfFileNodes);

                        resources.add(createResourceBuilder(projectName, stringPath, resourceManifest, dataMap).build());
                    } else if (!createdFolders.contains(stringPath) || resourceJsonDirs.contains(stringPath)) {
                        // Second clause: a resource.json existed here but failed to
                        // parse (manifest == null) — fall back to a plain folder so
                        // any children keep a valid parent, as before the pre-seed.
                        resources.add(createResourceBuilder(projectName, stringPath, ResourceManifest.newBuilder().build(), new HashMap<>()).setFolder(true).build());
                        createdFolders.add(stringPath);
                    }
                });

        return resources;
    }

    private static ResourceManifest removeResourceManifest(String manifestPath, List<Map.Entry<String, byte[]>> listOfFileNodes) {
        Gson gson = new Gson();
        return listOfFileNodes.stream()
                .filter(e -> manifestPath.equals(e.getKey()))
                .findFirst()
                .map(entry -> {
                    listOfFileNodes.remove(entry);
                    try {
                        return gson.fromJson(new String(entry.getValue(), StandardCharsets.UTF_8), ResourceManifest.class);
                    } catch (JsonSyntaxException e) {
                        logger.infof("Malformed resource.json at %s, unable to remove", entry.getKey(), e);
                        return null;
                    }
                }).orElse(null);
    }


    private static List<Resource> createParentFolderResources(String projectName, StringPath resourcePath, Set<StringPath> ignoreList) {
        List<Resource> folders = new ArrayList<>();
        StringPath currentPath = resourcePath.getParentPath();
        while (currentPath != null && currentPath.getPathLength() > 0 &&
                !ignoreList.contains(currentPath)) {
            folders.add(createResourceBuilder(projectName, currentPath,
                    ResourceManifest.newBuilder().build(), new HashMap<>())
                    .setFolder(true)
                    .build());
            ignoreList.add(currentPath);
            currentPath = currentPath.getParentPath();
        }

        return folders;
    }

    private static Map<String, byte[]> createDataMap(ResourceManifest resourceManifest, List<Map.Entry<String, byte[]>> listOfFileNodes) {
        List<String> allowedFiles = resourceManifest.getFiles();
        HashMap<String, byte[]> dataMap = new HashMap<>();
        listOfFileNodes.forEach(e -> {
            String filename = StringUtils.substringAfterLast(e.getKey(), "/");
            if (allowedFiles.contains(filename))
                dataMap.put(filename, e.getValue());
        });
        return dataMap;
    }

    private static ResourceBuilder createResourceBuilder(String projectName, StringPath resourcePath, ResourceManifest manifest, Map<String, byte[]> dataMap) {
        String moduleId = resourcePath.getPathComponent(0);
        String resourceType = (resourcePath.getPathLength() > 1) ? resourcePath.getPathComponent(1) : null;
        String subPath = (resourcePath.getPathLength() > 2) ? resourcePath.subPath().subPath().toString() : "";
        return Resource.newBuilder()
                .setResourceCollectionName(projectName)
                .setResourcePath(new ResourcePath(new ResourceType(moduleId, resourceType), subPath))
                .setDataBytes(dataMap)
                .setRestricted(manifest.isRestricted())
                // "attributes" is optional in resource.json, so Gson leaves it null for the many
                // real resources that omit it — and ResourceBuilder.setAttributes does
                // new HashMap<>(map), which NPEs on null and aborted the entire project import.
                // Of the manifest values used here this is the only null-hostile one:
                // setDocumentation is a plain field assignment, and isRestricted/getVersion/
                // isOverridable return primitives.
                .setAttributes(manifest.getAttributes() == null
                        ? Collections.emptyMap()
                        : manifest.getAttributes())
                .setApplicationScope(manifest.getScope())
                .setDocumentation(manifest.getDocumentation())
                .setVersion(manifest.getVersion())
                .setOverridable(manifest.isOverridable());
    }

    public static ResourceCollectionManifest loadProjectManifest(Path projectPath) throws IOException {
        String json = new String(Files.readAllBytes(projectPath.resolve("project.json")), StandardCharsets.UTF_8);
        Gson gson = new Gson();
        return gson.fromJson(json, ResourceCollectionManifest.class);
    }

}
