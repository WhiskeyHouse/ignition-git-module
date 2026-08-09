package com.axone_io.ignition.git.managers;

import com.axone_io.ignition.git.TagExportConfig;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TagChangeWatcherTest {

    // ---------- debounce ----------

    @Test
    public void burstOfEventsProducesExactlyOneExport() throws Exception {
        AtomicInteger exports = new AtomicInteger();
        CountDownLatch fired = new CountDownLatch(1);
        TagChangeWatcher watcher = new TagChangeWatcher(60L, () -> {
            exports.incrementAndGet();
            fired.countDown();
        });
        try {
            for (int i = 0; i < 20; i++) {
                watcher.onTagResourceChanged();
            }
            assertTrue("export should fire after the burst settles", fired.await(5, TimeUnit.SECONDS));
            // Let any wrongly-scheduled extra runs land before asserting.
            Thread.sleep(300L);
            assertEquals(1, exports.get());
        } finally {
            watcher.shutdown();
        }
    }

    @Test
    public void separatedBurstsProduceOneExportEach() throws Exception {
        AtomicInteger exports = new AtomicInteger();
        CountDownLatch twice = new CountDownLatch(2);
        TagChangeWatcher watcher = new TagChangeWatcher(60L, () -> {
            exports.incrementAndGet();
            twice.countDown();
        });
        try {
            watcher.onTagResourceChanged();
            Thread.sleep(400L);
            watcher.onTagResourceChanged();
            assertTrue(twice.await(5, TimeUnit.SECONDS));
            assertEquals(2, exports.get());
        } finally {
            watcher.shutdown();
        }
    }

    @Test
    public void eventsDuringSuppressionNeverExport() throws Exception {
        AtomicInteger exports = new AtomicInteger();
        TagChangeWatcher watcher = new TagChangeWatcher(60L, exports::incrementAndGet);
        try {
            // Zero grace: this test is about events arriving *inside* the import, and the
            // production grace would otherwise keep suppression asserted for seconds after.
            ImportSuppression.run(() -> {
                for (int i = 0; i < 5; i++) {
                    watcher.onTagResourceChanged();
                }
            }, 0L);
            Thread.sleep(500L);
            assertEquals(0, exports.get());
        } finally {
            watcher.shutdown();
        }
    }

    @Test
    public void eventsArrivingJustAfterAnImportAreStillSuppressed() throws Exception {
        // The config collection can dispatch an import's resource events after the import
        // call returns. Releasing suppression at the closing brace would export — and then
        // prompt the user to commit — exactly what they had just pulled.
        AtomicInteger exports = new AtomicInteger();
        TagChangeWatcher watcher = new TagChangeWatcher(60L, exports::incrementAndGet);
        try {
            // Grace outlasts the 60ms debounce, so the event fires while still suppressed,
            // then releases on its own — force-draining it here would let the pending
            // scheduled release push the depth negative for later tests.
            ImportSuppression.run(() -> { }, 400L);
            watcher.onTagResourceChanged();
            Thread.sleep(800L);
            assertEquals(0, exports.get());
            assertFalse("the grace period must release on its own",
                    ImportSuppression.isSuppressed());
        } finally {
            watcher.shutdown();
        }
    }

    @Test
    public void exportFailureDoesNotKillTheWatcher() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch twice = new CountDownLatch(2);
        TagChangeWatcher watcher = new TagChangeWatcher(60L, () -> {
            attempts.incrementAndGet();
            twice.countDown();
            throw new RuntimeException("export exploded");
        });
        try {
            watcher.onTagResourceChanged();
            Thread.sleep(400L);
            watcher.onTagResourceChanged();
            assertTrue("a throwing export must not cancel future runs", twice.await(5, TimeUnit.SECONDS));
            assertEquals(2, attempts.get());
        } finally {
            watcher.shutdown();
        }
    }

    // ---------- eligibility ----------

    @Test
    public void singleProjectGateway_alwaysEligible() {
        assertTrue(TagChangeWatcher.isAutoExportEligible(new TagExportConfig(), 1));
    }

    @Test
    public void multiProjectGateway_ineligibleWithoutIncludedProviders() {
        assertFalse("auto-export would write every provider's tags into one repo",
                TagChangeWatcher.isAutoExportEligible(new TagExportConfig(), 3));
    }

    @Test
    public void multiProjectGateway_eligibleWithIncludedProviders() {
        TagExportConfig config = new TagExportConfig(
                Collections.singletonList("PlantA"), new ArrayList<>(), "o");
        assertTrue(TagChangeWatcher.isAutoExportEligible(config, 3));
    }

    @Test
    public void nullConfig_ineligibleOnMultiProjectGateway() {
        assertFalse(TagChangeWatcher.isAutoExportEligible(null, 3));
    }

    // ---------- per-project export ----------

    @Test
    public void exportsOnlyEligibleProjects() {
        List<String> exported = new ArrayList<>();
        int count = TagChangeWatcher.runExport(
                Arrays.asList("A", "B", "C"),
                name -> !name.equals("B"),
                exported::add);
        assertEquals(Arrays.asList("A", "C"), exported);
        assertEquals(2, count);
    }

    @Test
    public void oneProjectFailureDoesNotStopTheRest() {
        List<String> exported = new ArrayList<>();
        int count = TagChangeWatcher.runExport(
                Arrays.asList("A", "B", "C"),
                name -> true,
                name -> {
                    if (name.equals("A")) {
                        throw new RuntimeException("export failed for A");
                    }
                    exported.add(name);
                });
        assertEquals(Arrays.asList("B", "C"), exported);
        assertEquals("failed projects must not be counted as exported", 2, count);
    }
}
