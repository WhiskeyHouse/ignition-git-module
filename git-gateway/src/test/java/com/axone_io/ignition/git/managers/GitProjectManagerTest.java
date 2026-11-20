package com.axone_io.ignition.git.managers;

import com.inductiveautomation.ignition.common.resourcecollection.Resource;
import com.inductiveautomation.ignition.common.resourcecollection.ResourcePath;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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

        Set<Resource> resources = GitProjectManager.importFromFolder(root.toPath(), "TestProject", MODULE_IDS);

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

        Set<Resource> resources = GitProjectManager.importFromFolder(root.toPath(), "TestProject", MODULE_IDS);

        Set<ResourcePath> seen = new HashSet<>();
        for (Resource r : resources) {
            assertTrue("duplicate resource emitted for " + r.getResourcePath(),
                    seen.add(r.getResourcePath()));
        }
        assertTrue(find(resources, "Broken").isFolder());
        assertFalse(find(resources, "Broken/Child").isFolder());
    }

    /**
     * "attributes" is optional in resource.json — real Ignition projects contain resources
     * without it. ResourceBuilder.setAttributes does {@code new HashMap<>(map)}, so a missing key
     * became {@code NullPointerException: Cannot invoke "java.util.Map.size()"} and aborted the
     * whole import: a pull updated the working tree but never loaded the project, and
     * commissioning left the project on its previous version with only a logged error.
     *
     * <p>Every other fixture in this class hardcodes {@code "attributes":{}}, which is exactly
     * why the existing tests never caught it.</p>
     */
    @Test
    public void resourceJsonWithoutAttributesStillImports() throws Exception {
        File root = tmp.newFolder("project");
        Path resource = root.toPath()
                .resolve("com.inductiveautomation.webdev/resources/tagConfig");
        Files.createDirectories(resource);
        Files.write(resource.resolve("doGet.py"), "def doGet(request, session): pass".getBytes());
        // No "attributes" key, and no "documentation" either - copied from the shape Ignition
        // actually writes for WebDev python resources.
        Files.write(resource.resolve("resource.json"),
                ("{\"scope\":\"G\",\"version\":1,\"restricted\":false,\"overridable\":true,"
                        + "\"files\":[\"doGet.py\"]}").getBytes());

        Set<Resource> resources = GitProjectManager.importFromFolder(root.toPath(), "TestProject",
                Set.of("com.inductiveautomation.webdev"));

        Resource imported = find(resources, "tagConfig");
        assertFalse("resource with its own resource.json must not be a folder",
                imported.isFolder());
        assertNotNull("attributes must be usable, not null", imported.getAttributes());
        assertTrue("no attributes in resource.json means no attributes on the resource",
                imported.getAttributes().isEmpty());
    }

    /**
     * A cloned project dir holds files that are not resources: the root project.json,
     * README.md and .gitignore, the whole .git/ tree, and top-level dirs such as tags/.
     * importFromFolder grouped every file by its parent dir, so a root file became a folder
     * resource named after itself and .git/** became resources too. The next project re-read
     * dropped them, and redundancy sent a delete for each one, which removed the backup's
     * real project.json.
     */
    @Test
    public void nonResourceFilesAreNotImported() throws Exception {
        File root = tmp.newFolder("project");
        Path dir = root.toPath();
        Files.write(dir.resolve("project.json"), "{\"title\":\"TestProject\"}".getBytes());
        Files.write(dir.resolve("README.md"), "# readme".getBytes());
        Files.write(dir.resolve(".gitignore"), "*.tmp".getBytes());
        Files.createDirectories(dir.resolve(".git/objects/xx"));
        Files.write(dir.resolve(".git/HEAD"), "ref: refs/heads/main".getBytes());
        Files.write(dir.resolve(".git/objects/xx/yy"), new byte[]{1, 2, 3});
        Files.createDirectories(dir.resolve("tags"));
        Files.write(dir.resolve("tags/tags.json"), "{}".getBytes());
        writeView(dir.resolve("com.inductiveautomation.perspective/views/Main"));

        Set<Resource> resources = GitProjectManager.importFromFolder(dir, "TestProject", MODULE_IDS);

        for (Resource r : resources) {
            ResourcePath path = r.getResourcePath();
            String moduleId = path.getResourceType().moduleId();
            for (String excluded : new String[]{"project.json", "README.md", ".gitignore", ".git", "tags"}) {
                assertFalse("non-resource file imported as " + path,
                        moduleId.equals(excluded) || String.valueOf(path).startsWith(excluded));
            }
            assertEquals("only the perspective resource tree is expected, got " + path,
                    "com.inductiveautomation.perspective", moduleId);
        }
        assertFalse("the real view is imported as a data resource", find(resources, "Main").isFolder());
        long dataResources = resources.stream().filter(r -> !r.isFolder()).count();
        assertEquals("the view is the only data resource; the rest are its parent folders",
                1, dataResources);
    }

    private static final Set<String> MODULE_IDS =
            Set.of("com.inductiveautomation.perspective", "ignition");

    private static void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes());
    }

    /**
     * A real project repo also holds .github/, deploy/, docs/, tests/ and other top-level dirs
     * that are not resources. A deny-list of names imported them, and the redundancy sync later
     * deleted them on the backup. Only top-level dirs named after a module id are resources.
     */
    @Test
    public void onlyModuleDirsAreImported() throws Exception {
        Path dir = tmp.newFolder("project").toPath();
        write(dir.resolve("project.json"), "{\"title\":\"TestProject\"}");
        write(dir.resolve("README.md"), "# readme");
        write(dir.resolve(".gitignore"), "*.tmp");
        write(dir.resolve(".git/HEAD"), "ref: refs/heads/main");
        write(dir.resolve(".git/objects/xx/yy"), "pack");
        write(dir.resolve(".github/workflows/ci.yml"), "on: push");
        write(dir.resolve("deploy/x.yaml"), "a: 1");
        write(dir.resolve("docs/a.md"), "# docs");
        write(dir.resolve("tests/t.py"), "pass");
        write(dir.resolve("tags/tags.json"), "{}");
        writeView(dir.resolve("com.inductiveautomation.perspective/views/Main"));
        Path script = dir.resolve("ignition/script-python/foo");
        write(script.resolve("code.py"), "x = 1");
        write(script.resolve("resource.json"), RESOURCE_JSON.replace("view.json", "code.py"));

        Set<Resource> resources = GitProjectManager.importFromFolder(dir, "TestProject", MODULE_IDS);

        Set<String> paths = new HashSet<>();
        for (Resource r : resources) {
            paths.add(r.getResourcePath() + (r.isFolder() ? " (folder)" : ""));
        }
        assertEquals(Set.of(
                "com.inductiveautomation.perspective (folder)",
                "com.inductiveautomation.perspective/views (folder)",
                "com.inductiveautomation.perspective/views/Main",
                "ignition (folder)",
                "ignition/script-python (folder)",
                "ignition/script-python/foo"), paths);
    }

    /**
     * listFiles read every file's bytes before filtering, .git packs included, and a large repo
     * ran the gateway out of heap. A skipped top-level dir must not be read at all: an
     * unreadable file under .git/ must not fail the import.
     */
    @Test
    public void skippedDirsAreNotRead() throws Exception {
        Assume.assumeFalse(System.getProperty("os.name").toLowerCase().startsWith("windows"));
        Path dir = tmp.newFolder("project").toPath();
        writeView(dir.resolve("com.inductiveautomation.perspective/views/Main"));
        Path pack = dir.resolve(".git/objects/pack/pack-1.pack");
        write(pack, "pack");
        Files.setPosixFilePermissions(pack, PosixFilePermissions.fromString("---------"));
        Assume.assumeFalse("running as a user that can read mode 000 files", Files.isReadable(pack));
        try {
            Set<Resource> resources = GitProjectManager.importFromFolder(dir, "TestProject", MODULE_IDS);
            assertFalse(find(resources, "Main").isFolder());
        } finally {
            Files.setPosixFilePermissions(pack, PosixFilePermissions.fromString("rw-------"));
        }
    }

    /**
     * The allowlist comes from the gateway's registered modules. If the import runs before a
     * module has registered, its folder looks like any other skipped dir, its resources are
     * dropped, and the redundancy sync sends them to the backup as deletions. A skipped dir
     * that holds a resource.json must stop the import instead.
     */
    @Test
    public void skippedDirWithResourcesAbortsImport() throws Exception {
        Path dir = tmp.newFolder("project").toPath();
        writeView(dir.resolve("com.inductiveautomation.perspective/views/Main"));
        Path script = dir.resolve("ignition/script-python/foo");
        write(script.resolve("code.py"), "x = 1");
        write(script.resolve("resource.json"), RESOURCE_JSON.replace("view.json", "code.py"));

        Set<Resource> resources = null;
        try {
            resources = GitProjectManager.importFromFolder(dir, "TestProject", Set.of("ignition"));
            fail("import must abort when a skipped dir holds resources, got " + resources);
        } catch (IllegalStateException e) {
            assertTrue("message names the folder: " + e.getMessage(),
                    e.getMessage().contains("com.inductiveautomation.perspective"));
        }
        assertNull("nothing is imported", resources);
    }

    /**
     * Dirs without any resource.json (.github, docs, tags, ...) are still skipped silently.
     */
    @Test
    public void skippedDirsWithoutResourcesDoNotAbort() throws Exception {
        Path dir = tmp.newFolder("project").toPath();
        write(dir.resolve(".github/workflows/ci.yml"), "on: push");
        write(dir.resolve("docs/guide/a.md"), "# docs");
        write(dir.resolve("tags/tags.json"), "{}");
        write(dir.resolve(".git/objects/xx/resource.json"), "{}");
        writeView(dir.resolve("com.inductiveautomation.perspective/views/Main"));

        Set<Resource> resources = GitProjectManager.importFromFolder(dir, "TestProject", MODULE_IDS);

        assertFalse(find(resources, "Main").isFolder());
        for (Resource r : resources) {
            assertEquals("com.inductiveautomation.perspective",
                    r.getResourcePath().getResourceType().moduleId());
        }
    }

    /**
     * A cloned project can hold directory symlinks. Following them let a loop (or a link out of
     * the repo) hang the walk or scan outside the project. Both the guard walk over skipped dirs
     * and listFiles over module dirs must not follow symlinks. Two links per loop make a
     * following walk branch until the OS refuses the path. A link from docs/ to a folder outside
     * the project that holds a resource.json would make a following guard abort the import.
     */
    @Test(timeout = 20000)
    public void symlinkLoopsDoNotHangImport() throws Exception {
        Path dir = tmp.newFolder("project").toPath();
        writeView(dir.resolve("com.inductiveautomation.perspective/views/Main"));
        Path docsLoop = dir.resolve("docs/a");
        Path moduleLoop = dir.resolve("com.inductiveautomation.perspective/views/loop");
        Files.createDirectories(docsLoop);
        Files.createDirectories(moduleLoop);
        try {
            for (Path loopDir : new Path[]{docsLoop, moduleLoop}) {
                Files.createSymbolicLink(loopDir.resolve("l1"), loopDir);
                Files.createSymbolicLink(loopDir.resolve("l2"), loopDir);
            }
            // A link out of the repo to a dir with a resource.json: followed, it would abort.
            Path outside = tmp.newFolder("outside").toPath();
            write(outside.resolve("x/resource.json"), "{}");
            Files.createSymbolicLink(dir.resolve("docs/out"), outside);
        } catch (UnsupportedOperationException | IOException e) {
            Assume.assumeNoException("filesystem cannot create symlinks", e);
        }

        Set<Resource> resources = GitProjectManager.importFromFolder(dir, "TestProject", MODULE_IDS);

        assertFalse(find(resources, "Main").isFolder());
        for (Resource r : resources) {
            assertFalse("symlinked dir was followed: " + r.getResourcePath(),
                    String.valueOf(r.getResourcePath()).contains("/l1"));
        }
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
