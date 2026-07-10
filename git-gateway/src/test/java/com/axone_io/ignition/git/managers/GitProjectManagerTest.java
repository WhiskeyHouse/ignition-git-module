package com.axone_io.ignition.git.managers;

import com.inductiveautomation.ignition.common.resourcecollection.Resource;
import com.inductiveautomation.ignition.common.resourcecollection.ResourcePath;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GitProjectManagerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String RESOURCE_JSON =
            "{\"scope\":\"G\",\"version\":1,\"restricted\":false,\"overridable\":true," +
            "\"files\":[\"view.json\"],\"attributes\":{}}";

    private void writeView(Path dir) throws Exception {
        Files.createDirectories(dir);
        Files.write(dir.resolve("view.json"), "{}".getBytes());
        Files.write(dir.resolve("resource.json"), RESOURCE_JSON.getBytes());
    }

    /**
     * A view nested inside another view's folder must not produce both a
     * folder resource and a data resource at the parent path — that made a
     * cold createOrReplace fail with "resource already exists"
     * (views/Changeover/Popups/EquipmentAction in WHK-Global).
     */
    @Test
    public void nestedViewDoesNotDuplicateParentResource() throws Exception {
        File root = tmp.newFolder("project");
        Path views = root.toPath().resolve("com.inductiveautomation.perspective/views/Popups");

        writeView(views.resolve("EquipmentAction"));
        writeView(views.resolve("EquipmentAction/ActionButton"));

        Set<Resource> resources = GitProjectManager.importFromFolder(root.toPath(), "TestProject");

        Map<ResourcePath, Integer> counts = new HashMap<>();
        for (Resource r : resources) {
            counts.merge(r.getResourcePath(), 1, Integer::sum);
        }
        for (Map.Entry<ResourcePath, Integer> e : counts.entrySet()) {
            assertEquals("duplicate resource emitted for " + e.getKey(), 1, (int) e.getValue());
        }

        Resource parent = find(resources, "Popups/EquipmentAction");
        Resource child = find(resources, "Popups/EquipmentAction/ActionButton");
        assertFalse("parent with its own resource.json must be a data resource, not a folder",
                parent.isFolder());
        assertFalse(child.isFolder());
        assertTrue("plain parent folders still created",
                find(resources, "Popups").isFolder());
    }

    /**
     * A directory whose resource.json is malformed keeps the pre-fix fallback:
     * it becomes a plain folder (exactly once) so children keep a valid parent.
     */
    @Test
    public void malformedResourceJsonStillCreatesFolder() throws Exception {
        File root = tmp.newFolder("project");
        Path bad = root.toPath().resolve("com.inductiveautomation.perspective/views/Broken");
        Files.createDirectories(bad);
        Files.write(bad.resolve("resource.json"), "{not-json".getBytes());
        writeView(bad.resolve("Child"));

        Set<Resource> resources = GitProjectManager.importFromFolder(root.toPath(), "TestProject");

        Set<ResourcePath> seen = new HashSet<>();
        for (Resource r : resources) {
            assertTrue("duplicate resource emitted for " + r.getResourcePath(),
                    seen.add(r.getResourcePath()));
        }
        assertTrue(find(resources, "Broken").isFolder());
        assertFalse(find(resources, "Broken/Child").isFolder());
    }

    private static Resource find(Set<Resource> resources, String pathSuffix) {
        for (Resource r : resources) {
            if (String.valueOf(r.getResourcePath()).endsWith(pathSuffix)) {
                return r;
            }
        }
        throw new AssertionError("no resource ending in " + pathSuffix + " found in " + resources);
    }
}
