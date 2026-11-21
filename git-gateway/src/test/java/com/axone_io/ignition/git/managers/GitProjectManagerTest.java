package com.axone_io.ignition.git.managers;

import com.inductiveautomation.ignition.common.resourcecollection.Resource;
import com.inductiveautomation.ignition.common.resourcecollection.ResourcePath;
import com.inductiveautomation.ignition.gateway.project.ProjectManager;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.Assert.assertArrayEquals;
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

    /**
     * The guard must fail closed: a skipped dir it cannot list may hide a resource.json, so an
     * unreadable subdir stops the import instead of being passed over.
     */
    @Test
    public void unlistableSkippedDirAbortsImport() throws Exception {
        Assume.assumeFalse(System.getProperty("os.name").toLowerCase().startsWith("windows"));
        Path dir = tmp.newFolder("project").toPath();
        writeView(dir.resolve("com.inductiveautomation.perspective/views/Main"));
        Path locked = dir.resolve("docs/locked");
        write(locked.resolve("resource.json"), "{}");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            Assume.assumeTrue("running as a user that can list mode 000 dirs",
                    locked.toFile().listFiles() == null);
            Set<Resource> resources = null;
            try {
                resources = GitProjectManager.importFromFolder(dir, "TestProject", MODULE_IDS);
                fail("import must abort when a skipped dir cannot be listed, got " + resources);
            } catch (IllegalStateException e) {
                assertTrue("message names the dir: " + e.getMessage(),
                        e.getMessage().contains("locked"));
            }
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    /**
     * Stands in for the gateway ProjectManager. Its createOrReplace mimics Ignition's project
     * write: it keeps project.json, .git and the module dirs it was given resources for, and
     * removes every other top-level entry from the project dir. Symlinks are removed as links,
     * never followed. The resources it was handed are recorded for assertions.
     */
    private static ProjectManager deletingProjectManager(Path projectDir, Set<String> moduleIds,
                                                         List<Resource> received, boolean thenFail) {
        return deletingProjectManager(projectDir, moduleIds, received, thenFail, () -> { });
    }

    private interface AfterWrite {
        void run() throws IOException;
    }

    private static ProjectManager deletingProjectManager(Path projectDir, Set<String> moduleIds,
                                                         List<Resource> received, boolean thenFail,
                                                         AfterWrite afterWrite) {
        return (ProjectManager) Proxy.newProxyInstance(ProjectManager.class.getClassLoader(),
                new Class<?>[]{ProjectManager.class}, (proxy, method, args) -> {
                    if (!"createOrReplace".equals(method.getName())) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    @SuppressWarnings("unchecked")
                    List<Resource> resources = (List<Resource>) args[2];
                    received.addAll(resources);
                    try (Stream<Path> top = Files.list(projectDir)) {
                        for (Path entry : (Iterable<Path>) top::iterator) {
                            String name = entry.getFileName().toString();
                            if (!name.equals("project.json") && !name.equals(".git")
                                    && !moduleIds.contains(name)) {
                                deleteTree(entry);
                            }
                        }
                    }
                    afterWrite.run();
                    if (thenFail) {
                        throw new IOException("project write failed");
                    }
                    return null;
                });
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(path);
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** A project dir with committed files outside the module dirs, as a real project repo has. */
    private Map<String, byte[]> writeRepoFiles(Path dir) throws Exception {
        Map<String, byte[]> files = new HashMap<>();
        files.put("deploy/project.yaml", "name: TestProject\n".getBytes());
        files.put("deploy/tag-providers/Provider.json", "{\"name\":\"Provider\"}".getBytes());
        files.put("images/logo.txt", "logo".getBytes());
        files.put("tags/default/tags.json", "{\"tags\":[]}".getBytes());
        files.put("README.md", "# readme\n".getBytes());
        files.put(".gitignore", "*.tmp\n".getBytes());
        files.put("docs/bin/data.bin", new byte[]{0, 1, 2, (byte) 0xff, 13, 10});
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            Path file = dir.resolve(e.getKey());
            Files.createDirectories(file.getParent());
            Files.write(file, e.getValue());
        }
        write(dir.resolve("project.json"), "{\"title\":\"TestProject\"}");
        write(dir.resolve(".git/HEAD"), "ref: refs/heads/main");
        writeView(dir.resolve("com.inductiveautomation.perspective/views/Main"));
        return files;
    }

    /**
     * Ignition's project write removes the top-level dirs it was not given resources for. Since
     * only module dirs are imported, every committed file outside them (deploy/, images/, tags/,
     * ...) was deleted from the working copy on each gateway start, and the module's own status
     * then reported them as deleted. Those files must survive the import byte for byte, and they
     * must still not be sent to the gateway as resources.
     */
    @Test
    public void nonModuleFilesSurviveProjectImport() throws Exception {
        Path dir = tmp.newFolder("project").toPath();
        Map<String, byte[]> files = writeRepoFiles(dir);
        List<Resource> received = new ArrayList<>();

        GitProjectManager.importProject(deletingProjectManager(dir, MODULE_IDS, received, false),
                dir, "TestProject", MODULE_IDS);

        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            Path file = dir.resolve(e.getKey());
            assertTrue(e.getKey() + " was deleted by the import", Files.exists(file));
            assertArrayEquals(e.getKey() + " changed during the import", e.getValue(),
                    Files.readAllBytes(file));
        }
        assertFalse("nothing was sent to the gateway", received.isEmpty());
        for (Resource r : received) {
            assertEquals("only module resources are sent: " + r.getResourcePath(),
                    "com.inductiveautomation.perspective",
                    r.getResourcePath().getResourceType().moduleId());
        }
    }

    /**
     * The files that come back are the ones on disk before the import, so a local edit that is
     * not committed yet is kept as edited, not replaced with another version.
     */
    @Test
    public void uncommittedEditsUnderSkippedDirsAreKept() throws Exception {
        Path dir = tmp.newFolder("project").toPath();
        writeRepoFiles(dir);
        byte[] edited = "name: TestProject\nedited: true\n".getBytes();
        Files.write(dir.resolve("deploy/project.yaml"), edited);
        write(dir.resolve("deploy/new-untracked.yaml"), "a: 1");

        GitProjectManager.importProject(deletingProjectManager(dir, MODULE_IDS, new ArrayList<>(), false),
                dir, "TestProject", MODULE_IDS);

        assertArrayEquals(edited, Files.readAllBytes(dir.resolve("deploy/project.yaml")));
        assertArrayEquals("a: 1".getBytes(), Files.readAllBytes(dir.resolve("deploy/new-untracked.yaml")));
    }

    /**
     * A failed project write must not leave the working copy without its non-module files.
     */
    @Test
    public void nonModuleFilesSurviveAFailedProjectWrite() throws Exception {
        Path dir = tmp.newFolder("project").toPath();
        Map<String, byte[]> files = writeRepoFiles(dir);

        try {
            GitProjectManager.importProject(deletingProjectManager(dir, MODULE_IDS, new ArrayList<>(), true),
                    dir, "TestProject", MODULE_IDS);
            fail("a failed project write must fail the import");
        } catch (RuntimeException expected) {
            // importProject wraps the write failure
        }

        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            assertArrayEquals(e.getKey() + " was not kept", e.getValue(),
                    Files.readAllBytes(dir.resolve(e.getKey())));
        }
    }

    /**
     * Symlinks under skipped dirs are kept as links and never followed: the target outside the
     * project is not copied into the project, and the link itself comes back after the write.
     */
    @Test
    public void symlinksUnderSkippedDirsAreKeptAsLinks() throws Exception {
        Path dir = tmp.newFolder("project").toPath();
        writeRepoFiles(dir);
        Path outside = tmp.newFolder("outside").toPath();
        write(outside.resolve("x/big.bin"), "outside");
        Path link = dir.resolve("docs/out");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException e) {
            Assume.assumeNoException("filesystem cannot create symlinks", e);
        }

        GitProjectManager.importProject(deletingProjectManager(dir, MODULE_IDS, new ArrayList<>(), false),
                dir, "TestProject", MODULE_IDS);

        assertTrue("the link is kept", Files.isSymbolicLink(link));
        assertEquals(outside, Files.readSymbolicLink(link));
        assertArrayEquals("the link target is untouched", "outside".getBytes(),
                Files.readAllBytes(outside.resolve("x/big.bin")));
    }

    /**
     * If the write leaves a dir where a saved file was, the file cannot be put back. That must
     * count as a failed restore: the saved copy is kept and the failure is reported, instead of
     * the backup being deleted with the uncommitted edit in it.
     */
    @Test
    public void restoreConflictKeepsBackupAndReportsFailure() throws Exception {
        Path dir = tmp.newFolder("project").toPath();
        writeRepoFiles(dir);
        byte[] edited = "edited: true\n".getBytes();
        Files.write(dir.resolve("deploy/project.yaml"), edited);
        ProjectManager pm = deletingProjectManager(dir, MODULE_IDS, new ArrayList<>(), false,
                () -> Files.createDirectories(dir.resolve("deploy/project.yaml")));

        GitProjectManager.SavedEntries kept = GitProjectManager.copyNonModuleEntries(dir, MODULE_IDS);
        try {
            pm.createOrReplace("TestProject", null, new ArrayList<>());
            assertFalse("the restore reports the conflict",
                    GitProjectManager.restoreNonModuleEntries(kept, dir));
            assertArrayEquals("the saved copy is kept", edited,
                    Files.readAllBytes(kept.dir.resolve("deploy/project.yaml")));
        } finally {
            deleteTree(kept.dir);
        }
    }

    /**
     * Saved dirs come back with their own mode, so a 0700 dir is not restored world-readable.
     */
    @Test
    public void skippedDirModesAreKept() throws Exception {
        Path dir = tmp.newFolder("project").toPath();
        writeRepoFiles(dir);
        Assume.assumeNotNull(Files.getFileAttributeView(dir, java.nio.file.attribute.PosixFileAttributeView.class));
        Path secret = dir.resolve("deploy/private");
        write(secret.resolve("config.yaml"), "a: 1");
        Files.setPosixFilePermissions(secret, PosixFilePermissions.fromString("rwx------"));

        GitProjectManager.importProject(deletingProjectManager(dir, MODULE_IDS, new ArrayList<>(), false),
                dir, "TestProject", MODULE_IDS);

        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(secret)));
        assertArrayEquals("a: 1".getBytes(), Files.readAllBytes(secret.resolve("config.yaml")));
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
