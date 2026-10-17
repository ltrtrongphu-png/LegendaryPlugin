package com.legendary.plugin.integrations;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BungeeIntegrationTest {

    @Test
    void acceptsOnlyPositiveBoundedDurationFormats() {
        assertTrue(BungeeIntegration.validDuration("30d"));
        assertTrue(BungeeIntegration.validDuration("15M"));
        assertFalse(BungeeIntegration.validDuration("0s"));
        assertFalse(BungeeIntegration.validDuration("-1d"));
        assertFalse(BungeeIntegration.validDuration("30 days"));
        assertFalse(BungeeIntegration.validDuration("100000d"));
        assertFalse(BungeeIntegration.validDuration(null));
    }
}
