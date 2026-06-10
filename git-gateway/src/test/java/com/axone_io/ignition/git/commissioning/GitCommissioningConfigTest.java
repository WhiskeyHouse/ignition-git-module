package com.axone_io.ignition.git.commissioning;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Locks the ProjectConfig -> GitCommissioningConfig mapping for the
 * tags_importOnStartup flag (TEC-3757). Null and false must both disable
 * the feature; only an explicit true enables it.
 */
public class GitCommissioningConfigTest {

    private static GitCommissioningConfig loaded(Boolean flag) {
        ProjectConfig pc = new ProjectConfig();
        pc.setTags_importOnStartup(flag);
        GitCommissioningConfig config = new GitCommissioningConfig();
        config.loadFromProjectConfig(pc);
        return config;
    }

    @Test
    public void importTagsOnStartup_defaultsToFalse() {
        assertFalse(new GitCommissioningConfig().isImportTagsOnStartup());
    }

    @Test
    public void importTagsOnStartup_nullMeansDisabled() {
        assertFalse(loaded(null).isImportTagsOnStartup());
    }

    @Test
    public void importTagsOnStartup_falseMeansDisabled() {
        assertFalse(loaded(Boolean.FALSE).isImportTagsOnStartup());
    }

    @Test
    public void importTagsOnStartup_trueEnables() {
        assertTrue(loaded(Boolean.TRUE).isImportTagsOnStartup());
    }
}
