package com.redhat.quayoktasyncooperator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class QuayOktaSyncTest {

    @Test
    void specHasExpectedSafeDefaults() {

        QuayOktaSyncSpec spec = new QuayOktaSyncSpec();

        assertEquals(Integer.valueOf(900), spec.intervalSeconds);
        assertEquals(Boolean.TRUE, spec.dryRun);
        assertEquals("^[^-]+-[^-]+-([^-]+)-[^-]+$", spec.groupNamePattern);
        assertEquals("login", spec.oktaUsernameAttribute);
        assertEquals("member", spec.quayRole);
        assertEquals(Boolean.FALSE, spec.removeExtraMembers);
        assertNull(spec.teamNameOverrides);
    }

    @Test
    void overrideContainsExplicitGroupAndTeamNames() {

        TeamNameOverride override = new TeamNameOverride();
        override.oktaGroup = "legacy-farmers";
        override.quayTeam = "farmers";

        assertEquals("legacy-farmers-admins", override.oktaGroup);
        assertEquals("farmers", override.quayTeam);
    }

}
