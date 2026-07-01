package com.axone_io.ignition.git.managers;

import com.inductiveautomation.ignition.common.gson.Gson;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class GitTagManagerTest {

    private static final Gson GSON = new Gson();

    private static Set<String> known(String... names) {
        return new HashSet<>(java.util.Arrays.asList(names));
    }

    private static JsonObject parse(String json) {
        return GSON.fromJson(json, JsonObject.class);
    }

    // ========================== reconcileUnknownTagGroups (fix #3) ==========================

    @Test
    public void reconcile_remapsUnknownGroupToFallback() {
        JsonObject tag = parse("{\"name\":\"Level\",\"tagType\":\"AtomicTag\"," +
                "\"tagGroup\":\"FactoryPacks Leased\"}");

        int remapped = GitTagManager.reconcileUnknownTagGroups(
                tag, known("Default", "Default Historical"), "Default");

        assertEquals(1, remapped);
        assertEquals("Default", tag.get("tagGroup").getAsString());
    }

    @Test
    public void reconcile_leavesKnownGroupUntouched() {
        JsonObject tag = parse("{\"name\":\"Level\",\"tagGroup\":\"Fast\"}");

        int remapped = GitTagManager.reconcileUnknownTagGroups(
                tag, known("Default", "Fast"), "Default");

        assertEquals(0, remapped);
        assertEquals("Fast", tag.get("tagGroup").getAsString());
    }

    @Test
    public void reconcile_recursesIntoNestedFoldersAndTags() {
        JsonObject root = parse("{\"tags\":[" +
                "{\"name\":\"Folder\",\"tagType\":\"Folder\",\"tags\":[" +
                "  {\"name\":\"A\",\"tagGroup\":\"Recipe\"}," +
                "  {\"name\":\"B\",\"tagGroup\":\"Default\"}" +
                "]}," +
                "{\"name\":\"C\",\"tagGroup\":\"Location\"}" +
                "]}");

        int remapped = GitTagManager.reconcileUnknownTagGroups(
                root, known("Default"), "Default");

        assertEquals(2, remapped);
    }

    @Test
    public void reconcile_ignoresEmptyAndMissingTagGroup() {
        // Empty tagGroup already means "use default" in Ignition; a node without the
        // property is a folder/container. Neither should be remapped or counted.
        JsonObject root = parse("{\"tags\":[" +
                "{\"name\":\"A\",\"tagGroup\":\"\"}," +
                "{\"name\":\"Folder\",\"tagType\":\"Folder\"}" +
                "]}");

        int remapped = GitTagManager.reconcileUnknownTagGroups(
                root, known("Default"), "Default");

        assertEquals(0, remapped);
    }

    @Test
    public void encodeFsName_leavesSafeNamesUnchanged() {
        assertEquals("Tank1_Level", GitTagManager.encodeFsName("Tank1_Level"));
        assertEquals("ns2_v1.0", GitTagManager.encodeFsName("ns2_v1.0"));
        assertEquals("foo-bar baz", GitTagManager.encodeFsName("foo-bar baz"));
    }

    @Test
    public void encodeFsName_escapesWindowsReservedChars() {
        // The actual failing case from production
        assertEquals("_0%3A0%3Awhk-development-gw",
                GitTagManager.encodeFsName("_0:0:whk-development-gw"));
        // Each Windows-reserved char must be escaped
        assertEquals("a%3Cb%3Ec%3Ad%22e%2Ff%5Cg%7Ch%3Fi%2Aj",
                GitTagManager.encodeFsName("a<b>c:d\"e/f\\g|h?i*j"));
    }

    @Test
    public void encodeFsName_escapesPercentItself() {
        // Percent must be escaped first so decoding is unambiguous
        assertEquals("100%25", GitTagManager.encodeFsName("100%"));
        assertEquals("%25%3A", GitTagManager.encodeFsName("%:"));
    }

    @Test
    public void encodeFsName_handlesNullAndEmpty() {
        assertNull(GitTagManager.encodeFsName(null));
        assertEquals("", GitTagManager.encodeFsName(""));
    }

    @Test
    public void decodeFsName_reversesEncoding() {
        String[] originals = {
                "_0:0:whk-development-gw",
                "ns=2;s=Foo:Bar",
                "100%",
                "a<b>c:d\"e/f\\g|h?i*j",
                "Tank1_Level",
                ""
        };
        for (String original : originals) {
            String roundtrip = GitTagManager.decodeFsName(GitTagManager.encodeFsName(original));
            assertEquals("round-trip failed for: " + original, original, roundtrip);
        }
    }

    @Test
    public void decodeFsName_leavesUnknownPercentsIntact() {
        // Legacy names with bare % that aren't valid hex escapes should pass through
        assertEquals("not%xxhex", GitTagManager.decodeFsName("not%xxhex"));
        assertEquals("trailing%", GitTagManager.decodeFsName("trailing%"));
        assertEquals("trailing%2", GitTagManager.decodeFsName("trailing%2"));
    }

    @Test
    public void decodeFsName_handlesNullAndEmpty() {
        assertNull(GitTagManager.decodeFsName(null));
        assertEquals("", GitTagManager.decodeFsName(""));
    }

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void isHiddenEntry_flagsDotPrefixedNames() {
        // Agent-state dirs that have poisoned imports in production (TEC-3647)
        assertTrue(GitTagManager.isHiddenEntry(".omc"));
        assertTrue(GitTagManager.isHiddenEntry(".claude"));
        assertTrue(GitTagManager.isHiddenEntry(".git"));
        // Internal config files we already skipped by exact name
        assertTrue(GitTagManager.isHiddenEntry(".tag-config.json"));
        assertTrue(GitTagManager.isHiddenEntry(".tag-groups.json"));
    }

    @Test
    public void isHiddenEntry_leavesNormalNamesAlone() {
        assertFalse(GitTagManager.isHiddenEntry("WHK01"));
        assertFalse(GitTagManager.isHiddenEntry("Cooker01"));
        assertFalse(GitTagManager.isHiddenEntry("_types_"));
        assertFalse(GitTagManager.isHiddenEntry("LoadRecipeTrigger.json"));
        assertFalse(GitTagManager.isHiddenEntry(""));
        assertFalse(GitTagManager.isHiddenEntry(null));
    }

    @Test
    public void readTagsFromDirectory_skipsDotFoldersAndDotFiles() throws Exception {
        // Reproduces the TEC-3647 scenario: a stray .omc/ agent-state folder lives
        // inside an authored tag-folder tree. The importer must ignore it (and any
        // .json file with a leading dot) instead of producing an invalid '.omc' tag.
        File providerRoot = tempFolder.newFolder("WHK01");

        File cooker = new File(providerRoot, "Cooker01");
        assertTrue(cooker.mkdirs());

        File realTag = new File(cooker, "LoadRecipeTrigger.json");
        Files.writeString(realTag.toPath(),
                "{\"tagType\":\"AtomicTag\",\"valueSource\":\"memory\",\"dataType\":\"Boolean\"}");

        // Agent-state folder that previously caused Error_Configuration on import.
        File omcDir = new File(cooker, ".omc");
        assertTrue(omcDir.mkdirs());
        Files.writeString(new File(omcDir, "state.json").toPath(), "{\"junk\":true}");

        // Hidden file directly under the provider root — also must be ignored.
        Files.writeString(new File(providerRoot, ".tag-config.json").toPath(), "{}");

        JsonObject result = GitTagManager.readTagsFromDirectory(providerRoot.toPath(), true);

        // Cooker01 folder is kept and contains the real tag.
        assertTrue("expected Cooker01 folder in result", result.has("Cooker01"));
        JsonObject cookerJson = result.getAsJsonObject("Cooker01");
        assertEquals("Folder", cookerJson.get("tagType").getAsString());
        assertTrue("expected child 'tags' array under Cooker01", cookerJson.has("tags"));

        // No '.omc' anywhere — neither at the root nor under Cooker01.
        assertFalse(".omc must not appear at provider root", result.has(".omc"));
        for (var entry : result.entrySet()) {
            assertFalse(".omc must not appear as a sibling folder",
                    entry.getKey().equals(".omc"));
        }

        var cookerChildren = cookerJson.getAsJsonArray("tags");
        boolean sawOmc = false;
        boolean sawRealTag = false;
        for (var child : cookerChildren) {
            String childName = child.getAsJsonObject().get("name").getAsString();
            if (".omc".equals(childName)) sawOmc = true;
            if ("LoadRecipeTrigger".equals(childName)) sawRealTag = true;
        }
        assertFalse(".omc must not appear under Cooker01", sawOmc);
        assertTrue("LoadRecipeTrigger.json should still be imported", sawRealTag);
    }
}
