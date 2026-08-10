package com.axone_io.ignition.git.commissioning;

import com.axone_io.ignition.git.commissioning.utils.GitCommissioningUtils;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Locks the YAML key -> ProjectConfig field mapping used by commissioning.
 *
 * The original bug (TEC-3635) was that 'commissioning_enforceBranch' silently
 * mapped to a non-existent 'commissioningEnforceBranch' field, swallowing a
 * NoSuchFieldException and forcing every gateway restart to switch developers
 * off feature branches. These tests prevent that drift from recurring.
 */
public class GitCommissioningUtilsTest {

    @Test
    public void everyMappedYamlKeyResolvesToARealProjectConfigField() throws NoSuchFieldException {
        for (Map.Entry<String, String> entry : GitCommissioningUtils.YAML_KEY_TO_FIELD.entrySet()) {
            String yamlKey = entry.getKey();
            String fieldName = entry.getValue();
            Field field = ProjectConfig.class.getDeclaredField(fieldName);
            assertNotNull("Field resolution returned null for " + yamlKey, field);
        }
    }

    @Test
    public void enforceBranchKeyMapsToActualField() throws NoSuchFieldException {
        String fieldName = GitCommissioningUtils.yamlKeyToFieldName("commissioning_enforceBranch");
        Field field = ProjectConfig.class.getDeclaredField(fieldName);
        assertEquals(Boolean.class, field.getType());
    }

    @Test
    public void tagsImportOnStartupKeyMapsToActualBooleanField() throws NoSuchFieldException {
        String fieldName = GitCommissioningUtils.yamlKeyToFieldName("tags_importOnStartup");
        Field field = ProjectConfig.class.getDeclaredField(fieldName);
        assertEquals(Boolean.class, field.getType());
    }

    /**
     * A String field here would still parse, then silently coerce every value — including
     * {@code false} — to a non-null truthy object, designating every project an owner.
     */
    @Test
    public void gatewayExportResourcesKeyMapsToActualBooleanField() throws NoSuchFieldException {
        String fieldName = GitCommissioningUtils.yamlKeyToFieldName("gateway_exportResources");
        Field field = ProjectConfig.class.getDeclaredField(fieldName);
        assertEquals(Boolean.class, field.getType());
    }

    @Test
    public void unknownYamlKeyFailsLoudly() {
        try {
            GitCommissioningUtils.yamlKeyToFieldName("this_key_does_not_exist");
            fail("Expected IllegalArgumentException for unknown YAML key");
        } catch (IllegalArgumentException expected) {
            // ok: misconfigured keys must surface, not be silently dropped
        }
    }

    // --- retired keys ---
    //
    // 'initDefaultBranch' was parsed into ProjectConfig and never read by anything, so a
    // fresh repo always landed on JGit's default branch ('master') no matter what git.yaml
    // said. Deleting the field outright would have been a breaking change: unmapped keys
    // hard-fail commissioning, and the key is present in live git.yaml files. It is now
    // retired — tolerated, ignored, and warned about.

    @Test
    public void retiredKeyIsNotInTheActiveMapping() {
        assertFalse("retired keys must not claim a ProjectConfig field",
                GitCommissioningUtils.YAML_KEY_TO_FIELD.containsKey("initDefaultBranch"));
        assertTrue(GitCommissioningUtils.isRetiredYamlKey("initDefaultBranch"));
    }

    @Test
    public void retiredKeyHasAnExplanation() {
        String explanation = GitCommissioningUtils.RETIRED_YAML_KEYS.get("initDefaultBranch");
        assertNotNull(explanation);
        // The message has to tell an operator what to do, not just that the key is dead.
        assertTrue("should name the branch repos actually get: " + explanation,
                explanation.contains("master"));
        assertTrue("should point at the real knob: " + explanation,
                explanation.contains("production_branch"));
    }

    @Test
    public void liveKeysAreNotAccidentallyRetired() {
        for (String yamlKey : GitCommissioningUtils.YAML_KEY_TO_FIELD.keySet()) {
            assertFalse("key is both active and retired: " + yamlKey,
                    GitCommissioningUtils.isRetiredYamlKey(yamlKey));
        }
    }

    // parseYaml is protected, so the end-to-end parse of a git.yaml still carrying the
    // retired key lives in RetiredYamlKeyParseTest, in the same package as the parser.

    @Test
    public void retiredKeyLookupExplainsItselfRatherThanReadingAsATypo() {
        try {
            GitCommissioningUtils.yamlKeyToFieldName("initDefaultBranch");
            fail("Expected IllegalArgumentException for a retired YAML key");
        } catch (IllegalArgumentException expected) {
            assertTrue("should say it is retired, not 'unknown': " + expected.getMessage(),
                    expected.getMessage().contains("retired"));
        }
    }

    @Test
    public void projectsWithTagImportOnStartup_filtersCorrectly() {
        ProjectConfigs configs = new ProjectConfigs();

        ProjectConfig enabled = new ProjectConfig();
        enabled.setIgnition_projectName("ProjA");
        enabled.setTags_importOnStartup(Boolean.TRUE);
        configs.addProject(enabled);

        ProjectConfig disabled = new ProjectConfig();
        disabled.setIgnition_projectName("ProjB");
        disabled.setTags_importOnStartup(Boolean.FALSE);
        configs.addProject(disabled);

        ProjectConfig unset = new ProjectConfig();
        unset.setIgnition_projectName("ProjC");
        configs.addProject(unset);

        ProjectConfig noName = new ProjectConfig();
        noName.setTags_importOnStartup(Boolean.TRUE);
        configs.addProject(noName);

        List<String> result = GitCommissioningUtils.projectsWithTagImportOnStartup(configs);
        assertEquals(List.of("ProjA"), result);
    }

    @Test
    public void projectsWithTagImportOnStartup_emptyAndNullSafe() {
        assertEquals(List.of(), GitCommissioningUtils.projectsWithTagImportOnStartup(new ProjectConfigs()));
        assertEquals(List.of(), GitCommissioningUtils.projectsWithTagImportOnStartup(null));
    }
}
