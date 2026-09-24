package com.axone_io.ignition.git.managers;

import com.axone_io.ignition.git.TagExportConfig;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for the staged, non-destructive tag export.
 *
 * <p>Context: {@code exportTag} used to {@code cleanDirectory(tags/)} and then rebuild from
 * whatever providers happened to be registered. Two independent failures fell out of that.</p>
 *
 * <ul>
 *   <li><b>Partial export.</b> {@code getTagProviders()} populates progressively during gateway
 *       startup, so an export in that window legitimately sees a subset and silently deletes the
 *       rest. This destroyed every provider directory in a production repo.</li>
 *   <li><b>Failed export.</b> Any exception in the write loop happens <em>after</em> the wipe, so
 *       the repo is left holding a half-written tag tree with no rollback.</li>
 * </ul>
 *
 * <p>Staging fixes the second. The completeness guard fixes the first. Both are required — a
 * successful export of an incomplete provider set is exactly as destructive as a crash.</p>
 */
public class GitTagExportStagingTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private TagExportConfig allProviders() {
        return new TagExportConfig();
    }

    private TagExportConfig onlyIncluding(String... providers) {
        TagExportConfig config = new TagExportConfig();
        config.setIncludedProviders(Arrays.asList(providers));
        return config;
    }

    private static void writeProvider(Path root, String provider, String tagName) throws IOException {
        Path dir = root.resolve(provider);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(tagName + ".json"), "{\"name\":\"" + tagName + "\"}");
    }

    // --- completeness guard: the startup-race defect ---

    @Test
    public void providerLoss_whenStagingIsMissingAnIncludedProvider_isReported() throws Exception {
        Path dest = tempFolder.newFolder("tags").toPath();
        writeProvider(dest, "WHK01", "TankLevel");
        writeProvider(dest, "Spark", "Databases");

        Path staging = tempFolder.newFolder("tags.tmp").toPath();
        writeProvider(staging, "WHK01", "TankLevel");   // Spark not yet registered

        String loss = GitTagManager.describeProviderLoss(staging, dest, allProviders());

        assertNotNull("a provider disappearing from the export must be reported", loss);
        assertTrue("the message should name the lost provider: " + loss, loss.contains("Spark"));
    }

    @Test
    public void providerLoss_whenStagingHasEveryProvider_isNull() throws Exception {
        Path dest = tempFolder.newFolder("tags").toPath();
        writeProvider(dest, "WHK01", "TankLevel");

        Path staging = tempFolder.newFolder("tags.tmp").toPath();
        writeProvider(staging, "WHK01", "TankLevel");
        writeProvider(staging, "NewProvider", "Fresh");   // gaining a provider is fine

        assertNull(GitTagManager.describeProviderLoss(staging, dest, allProviders()));
    }

    @Test
    public void providerLoss_whenTheMissingProviderIsExcludedByConfig_isNull() throws Exception {
        Path dest = tempFolder.newFolder("tags").toPath();
        writeProvider(dest, "WHK01", "TankLevel");
        writeProvider(dest, "Spark", "Databases");

        Path staging = tempFolder.newFolder("tags.tmp").toPath();
        writeProvider(staging, "WHK01", "TankLevel");

        // Deliberately dropping Spark from the export is a configuration decision, not data loss.
        assertNull(GitTagManager.describeProviderLoss(staging, dest, onlyIncluding("WHK01")));
    }

    @Test
    public void providerLoss_whenTheMissingProviderIsBuiltInSystem_isNull() throws Exception {
        Path dest = tempFolder.newFolder("tags").toPath();
        writeProvider(dest, "WHK01", "TankLevel");
        writeProvider(dest, "System", "Gateway");

        Path staging = tempFolder.newFolder("tags.tmp").toPath();
        writeProvider(staging, "WHK01", "TankLevel");

        assertNull("the built-in System provider is intentionally never exported",
                GitTagManager.describeProviderLoss(staging, dest, allProviders()));
    }

    @Test
    public void providerLoss_whenStagingIsEmptyButDestinationIsNot_isReported() throws Exception {
        Path dest = tempFolder.newFolder("tags").toPath();
        writeProvider(dest, "WHK01", "TankLevel");
        writeProvider(dest, "Gateway", "ServicesInfo");

        Path staging = tempFolder.newFolder("tags.tmp").toPath();

        String loss = GitTagManager.describeProviderLoss(staging, dest, allProviders());

        assertNotNull("exporting zero providers over a populated repo must never be published", loss);
        assertTrue(loss.contains("WHK01"));
        assertTrue(loss.contains("Gateway"));
    }

    @Test
    public void providerLoss_whenDestinationDoesNotYetExist_isNull() throws Exception {
        Path dest = tempFolder.getRoot().toPath().resolve("tags-absent");
        Path staging = tempFolder.newFolder("tags.tmp").toPath();
        writeProvider(staging, "WHK01", "TankLevel");

        assertNull("a first export has nothing to lose",
                GitTagManager.describeProviderLoss(staging, dest, allProviders()));
    }

    // --- staged publish: the crash-mid-export defect ---

    @Test
    public void stagedExport_whenWriterSucceeds_publishesTheNewTree() throws Exception {
        Path dest = tempFolder.newFolder("tags").toPath();
        writeProvider(dest, "WHK01", "OldTag");

        GitTagManager.exportStaged(dest, allProviders(), staging -> {
            writeProvider(staging, "WHK01", "NewTag");
        });

        assertTrue("the new tree should be published",
                Files.exists(dest.resolve("WHK01").resolve("NewTag.json")));
        assertFalse("stale files should not survive the swap",
                Files.exists(dest.resolve("WHK01").resolve("OldTag.json")));
    }

    @Test
    public void stagedExport_whenWriterThrows_leavesDestinationUntouched() throws Exception {
        Path dest = tempFolder.newFolder("tags").toPath();
        writeProvider(dest, "WHK01", "TankLevel");
        writeProvider(dest, "Spark", "Databases");

        try {
            GitTagManager.exportStaged(dest, allProviders(), staging -> {
                writeProvider(staging, "WHK01", "TankLevel");
                throw new IllegalStateException("provider fetch blew up mid-export");
            });
            fail("a failed export must propagate, not silently publish a partial tree");
        } catch (Exception expected) {
            // fall through to the assertions that matter
        }

        assertTrue("existing tags must survive a failed export",
                Files.exists(dest.resolve("WHK01").resolve("TankLevel.json")));
        assertTrue("a provider not reached before the failure must survive",
                Files.exists(dest.resolve("Spark").resolve("Databases.json")));
    }

    @Test
    public void stagedExport_whenProvidersWouldBeLost_leavesDestinationUntouched() throws Exception {
        Path dest = tempFolder.newFolder("tags").toPath();
        writeProvider(dest, "WHK01", "TankLevel");
        writeProvider(dest, "Spark", "Databases");

        try {
            // Simulates the startup race: the export succeeds, but only sees one provider.
            GitTagManager.exportStaged(dest, allProviders(), staging -> {
                writeProvider(staging, "WHK01", "TankLevel");
            });
            fail("publishing an export that drops a provider must be refused");
        } catch (Exception expected) {
            // fall through
        }

        assertTrue("the provider missing from the export must not be deleted",
                Files.exists(dest.resolve("Spark").resolve("Databases.json")));
    }

    @Test
    public void stagedExport_doesNotLeaveAStagingDirectoryBehind() throws Exception {
        Path dest = tempFolder.newFolder("tags").toPath();
        writeProvider(dest, "WHK01", "TankLevel");

        try {
            GitTagManager.exportStaged(dest, allProviders(), staging -> {
                throw new IllegalStateException("boom");
            });
        } catch (Exception expected) {
            // fall through
        }

        Path siblingStaging = dest.getParent().resolve(dest.getFileName() + ".tmp");
        assertFalse("a failed export must not litter the repo with a staging directory",
                Files.exists(siblingStaging));
    }

    @Test
    public void stagedExport_whenDestinationDoesNotExist_createsIt() throws Exception {
        Path dest = tempFolder.getRoot().toPath().resolve("tags");

        GitTagManager.exportStaged(dest, allProviders(), staging -> {
            writeProvider(staging, "WHK01", "TankLevel");
        });

        assertTrue(Files.exists(dest.resolve("WHK01").resolve("TankLevel.json")));
    }

    @Test
    public void stagedExport_removesTagsDeletedSinceTheLastExport() throws Exception {
        Path dest = tempFolder.newFolder("tags").toPath();
        writeProvider(dest, "WHK01", "Kept");
        Files.writeString(dest.resolve("WHK01").resolve("Deleted.json"), "{}");

        GitTagManager.exportStaged(dest, allProviders(), staging -> {
            writeProvider(staging, "WHK01", "Kept");
        });

        assertTrue(Files.exists(dest.resolve("WHK01").resolve("Kept.json")));
        assertFalse("a tag genuinely removed from the provider should leave the repo",
                Files.exists(dest.resolve("WHK01").resolve("Deleted.json")));
    }

    @Test
    public void stagedExport_preservesDotFilesThatLiveOutsideProviderDirectories() throws Exception {
        Path dest = tempFolder.newFolder("tags").toPath();
        writeProvider(dest, "WHK01", "TankLevel");

        GitTagManager.exportStaged(dest, allProviders(), staging -> {
            writeProvider(staging, "WHK01", "TankLevel");
            Files.writeString(staging.resolve(".tag-config.json"), "{\"includedProviders\":[]}");
        });

        assertTrue("config written during export must be published with the tree",
                Files.exists(dest.resolve(".tag-config.json")));
    }
}
