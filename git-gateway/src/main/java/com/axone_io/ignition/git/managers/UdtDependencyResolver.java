package com.axone_io.ignition.git.managers;

import com.inductiveautomation.ignition.common.gson.JsonArray;
import com.inductiveautomation.ignition.common.gson.JsonElement;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.util.LoggerEx;

import java.util.*;

/**
 * Resolves UDT type dependencies via topological sort so that base types are
 * imported before types that reference them.
 *
 * <p>Algorithm inspired by the <a href="https://github.com/design-group/ignition-tag-cicd-module">
 * Ignition Tag CI/CD Module</a> by Barry-Wehmiller Design Group (Keith Gamble).
 * Adapted: DFS-based topological sort with cycle detection (logs warning on cycle,
 * does not crash).</p>
 */
public class UdtDependencyResolver {
    private static final LoggerEx logger = LoggerEx.newBuilder().build(UdtDependencyResolver.class);

    private UdtDependencyResolver() {
        // utility class
    }

    /**
     * Sorts a list of UDT type definitions so that dependencies come before dependents.
     *
     * @param udtTypes array of UDT type JSON objects (each must have a "name" key and
     *                 optionally a "typeId" referencing another UDT, or child tags with
     *                 tagType=UdtInstance referencing typeIds)
     * @return ordered list with base types first
     */
    public static List<JsonObject> sortByDependencies(JsonArray udtTypes) {
        if (udtTypes == null || udtTypes.size() == 0) {
            return Collections.emptyList();
        }

        // Build a name -> JsonObject map
        Map<String, JsonObject> udtByName = new LinkedHashMap<>();
        for (JsonElement element : udtTypes) {
            if (element.isJsonObject()) {
                JsonObject udt = element.getAsJsonObject();
                String name = getStringProperty(udt, "name");
                if (name != null) {
                    udtByName.put(name, udt);
                }
            }
        }

        // Build adjacency: for each UDT, find which other UDTs it depends on
        Map<String, Set<String>> dependencies = new LinkedHashMap<>();
        for (Map.Entry<String, JsonObject> entry : udtByName.entrySet()) {
            String name = entry.getKey();
            JsonObject udt = entry.getValue();
            Set<String> deps = findDependencies(udt, udtByName.keySet());
            dependencies.put(name, deps);
        }

        // Topological sort via DFS
        List<String> sorted = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        Set<String> inStack = new LinkedHashSet<>();

        for (String name : udtByName.keySet()) {
            if (!visited.contains(name)) {
                dfs(name, dependencies, visited, inStack, sorted);
            }
        }

        // Convert sorted names back to JsonObjects
        List<JsonObject> result = new ArrayList<>(sorted.size());
        for (String name : sorted) {
            JsonObject obj = udtByName.get(name);
            if (obj != null) {
                result.add(obj);
            }
        }
        return result;
    }

    /**
     * Finds which UDT names the given UDT depends on by scanning for:
     * <ul>
     *   <li>A top-level "typeId" property referencing another UDT</li>
     *   <li>Child tags with tagType "UdtInstance" that have a "typeId"</li>
     * </ul>
     *
     * @param udt           the UDT type definition JSON
     * @param knownUdtNames set of all known UDT names in this batch
     * @return set of UDT names that this UDT depends on
     */
    public static Set<String> findDependencies(JsonObject udt, Set<String> knownUdtNames) {
        Set<String> deps = new LinkedHashSet<>();
        collectTypeIdReferences(udt, knownUdtNames, deps);
        return deps;
    }

    /**
     * Recursively scans a JSON object for typeId references that point to known UDT names.
     */
    private static void collectTypeIdReferences(JsonObject node, Set<String> knownUdtNames, Set<String> deps) {
        // Check the node's own typeId
        String typeId = getStringProperty(node, "typeId");
        if (typeId != null) {
            // typeId may be a full path like "MyFolder/MyUDT" — extract the last segment
            String simpleName = typeId.contains("/") ? typeId.substring(typeId.lastIndexOf('/') + 1) : typeId;
            if (knownUdtNames.contains(simpleName)) {
                deps.add(simpleName);
            }
            // Also try the full typeId in case names match with path
            if (knownUdtNames.contains(typeId)) {
                deps.add(typeId);
            }
        }

        // Check "tags" child array
        if (node.has("tags") && node.get("tags").isJsonObject()) {
            JsonObject tags = node.getAsJsonObject("tags");
            for (Map.Entry<String, JsonElement> entry : tags.entrySet()) {
                if (entry.getValue().isJsonObject()) {
                    collectTypeIdReferences(entry.getValue().getAsJsonObject(), knownUdtNames, deps);
                }
            }
        }

        // Also handle "tags" as array (different Ignition versions may use either)
        if (node.has("tags") && node.get("tags").isJsonArray()) {
            JsonArray tagsArray = node.getAsJsonArray("tags");
            for (JsonElement child : tagsArray) {
                if (child.isJsonObject()) {
                    collectTypeIdReferences(child.getAsJsonObject(), knownUdtNames, deps);
                }
            }
        }
    }

    private static void dfs(String node,
                            Map<String, Set<String>> dependencies,
                            Set<String> visited,
                            Set<String> inStack,
                            List<String> sorted) {
        if (inStack.contains(node)) {
            logger.warn("Cycle detected in UDT dependencies involving: " + node + ". Import order may not be optimal.");
            return;
        }
        if (visited.contains(node)) {
            return;
        }

        inStack.add(node);
        Set<String> deps = dependencies.getOrDefault(node, Collections.emptySet());
        for (String dep : deps) {
            dfs(dep, dependencies, visited, inStack, sorted);
        }
        inStack.remove(node);
        visited.add(node);
        sorted.add(node);
    }

    private static String getStringProperty(JsonObject obj, String key) {
        if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
            return obj.get(key).getAsString();
        }
        return null;
    }
}
