package com.axone_io.ignition.git.managers;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the non-destructive tag-group export behaviour (fix #1).
 *
 * <p>Regression context: on the production (8.3) gateway, {@code exportTag} cleared the
 * tags directory and then failed to fetch tag groups, leaving every provider's
 * {@code .tag-groups.json} deleted. These tests pin the guarantee that a failed/empty
 * fetch preserves the previously-committed group file instead of losing it.</p>
 */
public class GitTagGroupManagerTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private static final String WHK01_JSON =
            "{\n  \"tagGroups\": [\n    { \"name\": \"Fast\", \"rate\": 250 },\n" +
            "    { \"name\": \"Modbus\", \"rate\": 1000 }\n  ]\n}";
    private static final String DEFAULT_JSON =
            "{\n  \"tagGroups\": [\n    { \"name\": \"Default\", \"rate\": 1000 }\n  ]\n}";

    @Test
    public void snapshotExistingGroupFiles_readsGroupFilePerProvider() throws Exception {
        Path tagsDir = tempFolder.newFolder("tags").toPath();
        writeGroupFile(tagsDir, "WHK01", WHK01_JSON);
        writeGroupFile(tagsDir, "default", DEFAULT_JSON);
        // A provider directory with tags but no group file must not appear in the snapshot.
        Files.createDirectories(tagsDir.resolve("Spark"));

        Map<String, String> snapshot = GitTagGroupManager.snapshotExistingGroupFiles(tagsDir);

        assertEquals(2, snapshot.size());
        assertEquals(WHK01_JSON, snapshot.get("WHK01"));
        assertEquals(DEFAULT_JSON, snapshot.get("default"));
        assertFalse("provider without a group file must be absent", snapshot.containsKey("Spark"));
    }

    @Test
    public void writeGroupFiles_preservesExistingWhenFreshFetchFailed() throws Exception {
        // Simulates the post-clearDirectory state: the tags dir is empty and the fresh
        // fetch for WHK01 came back null (timeout/exception/empty on the source gateway).
        Path tagsDir = tempFolder.newFolder("tags").toPath();
        Map<String, String> preserved = new HashMap<>();
        preserved.put("WHK01", WHK01_JSON);
        Map<String, String> fresh = new HashMap<>();
        fresh.put("WHK01", null); // fetch failed

        GitTagGroupManager.writeGroupFiles(tagsDir, fresh, preserved);

        Path file = tagsDir.resolve("WHK01").resolve(GitTagGroupManager.TAG_GROUPS_FILENAME);
        assertTrue("failed fetch must not delete the prior group file", Files.exists(file));
        assertEquals(WHK01_JSON, Files.readString(file));
    }

    @Test
    public void writeGroupFiles_writesFreshContentWhenFetchSucceeded() throws Exception {
        Path tagsDir = tempFolder.newFolder("tags").toPath();
        Map<String, String> preserved = new HashMap<>();
        preserved.put("WHK01", DEFAULT_JSON); // stale prior content
        Map<String, String> fresh = new HashMap<>();
        fresh.put("WHK01", WHK01_JSON); // successful fetch

        GitTagGroupManager.writeGroupFiles(tagsDir, fresh, preserved);

        Path file = tagsDir.resolve("WHK01").resolve(GitTagGroupManager.TAG_GROUPS_FILENAME);
        assertEquals("fresh content must overwrite preserved", WHK01_JSON, Files.readString(file));
    }

    @Test
    public void writeGroupFiles_skipsProviderWithNeitherFreshNorPreserved() throws Exception {
        Path tagsDir = tempFolder.newFolder("tags").toPath();
        Map<String, String> fresh = new HashMap<>();
        fresh.put("Ghost", null);
        Map<String, String> preserved = new HashMap<>();

        GitTagGroupManager.writeGroupFiles(tagsDir, fresh, preserved);

        assertFalse(Files.exists(tagsDir.resolve("Ghost")));
    }

    private void writeGroupFile(Path tagsDir, String provider, String content) throws Exception {
        Path dir = tagsDir.resolve(provider);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(GitTagGroupManager.TAG_GROUPS_FILENAME), content);
    }
}
