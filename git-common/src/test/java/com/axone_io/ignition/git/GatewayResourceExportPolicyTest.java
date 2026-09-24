package com.axone_io.ignition.git;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Decides which project on a gateway is allowed to export gateway-scoped resources.
 *
 * <p>Tags, themes and images are all gateway-scoped — {@code getTagProviders()},
 * {@code context.getImageManager()} and {@code data/modules/…/themes} respectively — but
 * {@code exportConfigImpl} runs per project. On a gateway with more than one git-backed project
 * every project therefore wrote a complete copy of the same gateway state into its own repo, each
 * wiping and rewriting that copy on its own schedule. Two projects committing at different moments
 * produce two divergent snapshots of one gateway, and whichever exports during a provider
 * registration gap propagates its truncated view as a deletion commit.</p>
 *
 * <p>Exactly one project owns these resources. It must be designated explicitly: guessing is how
 * the problem started.</p>
 */
public class GatewayResourceExportPolicyTest {

    @Test
    public void skipReason_whenThisProjectIsTheDesignatedOwner_isNull() {
        assertNull(GatewayResourceExportPolicy.describeSkipReason(
                "WHK-Global", Collections.singletonList("WHK-Global")));
    }

    @Test
    public void skipReason_whenAnotherProjectOwnsThem_namesTheOwner() {
        String reason = GatewayResourceExportPolicy.describeSkipReason(
                "WHK-Reporting", Collections.singletonList("WHK-Global"));

        assertNotNull("a non-owning project must not export gateway resources", reason);
        assertTrue("the reason should name the owning project: " + reason,
                reason.contains("WHK-Global"));
    }

    @Test
    public void skipReason_whenNoProjectIsDesignated_explainsHowToConfigure() {
        String reason = GatewayResourceExportPolicy.describeSkipReason(
                "WHK-Global", Collections.emptyList());

        assertNotNull("an unconfigured gateway must not export gateway resources", reason);
        assertTrue("the reason should point at the setting to change: " + reason,
                reason.contains("gateway_exportResources"));
    }

    @Test
    public void skipReason_whenOwnersAreUnknown_isTreatedAsUnconfigured() {
        String reason = GatewayResourceExportPolicy.describeSkipReason("WHK-Global", null);

        assertNotNull("a null owner list means nothing was configured, not 'anything goes'", reason);
    }

    @Test
    public void skipReason_whenSeveralProjectsClaimOwnership_refusesAndNamesThemAll() {
        String reason = GatewayResourceExportPolicy.describeSkipReason(
                "WHK-Global", Arrays.asList("WHK-Global", "WHK-Reporting"));

        assertNotNull("ambiguous ownership must not silently resolve in favour of the caller", reason);
        assertTrue("the reason should name every claimant: " + reason, reason.contains("WHK-Global"));
        assertTrue("the reason should name every claimant: " + reason, reason.contains("WHK-Reporting"));
    }

    @Test
    public void skipReason_whenSeveralProjectsClaimOwnership_refusesTheNonOwnersToo() {
        String reason = GatewayResourceExportPolicy.describeSkipReason(
                "WHK-Other", Arrays.asList("WHK-Global", "WHK-Reporting"));

        assertNotNull(reason);
    }

    @Test
    public void skipReason_ignoresBlankAndNullOwnerEntries() {
        String reason = GatewayResourceExportPolicy.describeSkipReason(
                "WHK-Global", Arrays.asList(null, "   ", "WHK-Global"));

        assertNull("blank entries are not competing claimants", reason);
    }

    @Test
    public void skipReason_whenProjectNameIsUnknown_refuses() {
        String reason = GatewayResourceExportPolicy.describeSkipReason(
                null, Collections.singletonList("WHK-Global"));

        assertNotNull("an unidentifiable project cannot be the designated owner", reason);
    }
}
