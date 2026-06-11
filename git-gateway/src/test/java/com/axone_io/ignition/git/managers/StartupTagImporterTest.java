package com.axone_io.ignition.git.managers;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests the injectable poll/import loop. The readiness check, the importer,
 * and the sleeper are all passed in, so no Ignition runtime and no real
 * sleeping is needed (TEC-3757).
 */
public class StartupTagImporterTest {

    @Test
    public void importsAllProjectsInOrder_whenProvidersReadyImmediately() {
        List<String> imported = new ArrayList<>();
        StartupTagImporter.runImport(
                Arrays.asList("A", "B"),
                () -> true,
                imported::add,
                millis -> { throw new AssertionError("must not sleep when ready"); },
                3);
        assertEquals(Arrays.asList("A", "B"), imported);
    }

    @Test
    public void waitsForReadiness_thenImports() {
        List<String> imported = new ArrayList<>();
        List<Long> sleeps = new ArrayList<>();
        AtomicInteger polls = new AtomicInteger();
        StartupTagImporter.runImport(
                Arrays.asList("A"),
                () -> polls.incrementAndGet() >= 3, // ready on 3rd poll
                imported::add,
                sleeps::add,
                10);
        assertEquals(Arrays.asList("A"), imported);
        assertEquals(2, sleeps.size()); // slept twice before ready
        assertEquals(Long.valueOf(StartupTagImporter.POLL_INTERVAL_MS), sleeps.get(0));
    }

    @Test
    public void givesUpAfterMaxPolls_neverImports() {
        List<String> imported = new ArrayList<>();
        List<Long> sleeps = new ArrayList<>();
        StartupTagImporter.runImport(
                Arrays.asList("A", "B"),
                () -> false,
                imported::add,
                sleeps::add,
                4);
        assertTrue(imported.isEmpty());
        assertEquals(4, sleeps.size()); // polled max times, slept between each
    }

    @Test
    public void oneFailingImportDoesNotStopOthers() {
        List<String> imported = new ArrayList<>();
        StartupTagImporter.runImport(
                Arrays.asList("A", "BOOM", "C"),
                () -> true,
                p -> {
                    if (p.equals("BOOM")) {
                        throw new RuntimeException("simulated import failure");
                    }
                    imported.add(p);
                },
                millis -> { },
                3);
        assertEquals(Arrays.asList("A", "C"), imported);
    }

    @Test
    public void abortsPollLoop_whenThreadInterrupted() {
        List<String> imported = new ArrayList<>();
        List<Long> sleeps = new ArrayList<>();
        try {
            StartupTagImporter.runImport(
                    Arrays.asList("A"),
                    () -> false,
                    imported::add,
                    millis -> {
                        sleeps.add(millis);
                        Thread.currentThread().interrupt();
                    },
                    100);
            assertTrue(imported.isEmpty());
            assertEquals(1, sleeps.size()); // aborted on first interrupted sleep, not after 100 burned polls
        } finally {
            Thread.interrupted(); // clear the flag so other tests are unaffected
        }
    }
}
