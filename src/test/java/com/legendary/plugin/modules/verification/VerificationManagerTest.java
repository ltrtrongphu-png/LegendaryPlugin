package com.legendary.plugin.modules.verification;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VerificationManagerTest {

    @Test
    void verificationDistanceIgnoresVerticalMovement() {
        Location start = new Location(null, 0.5, 65, 0.5);
        Location verticallyDisplaced = new Location(null, 0.5, 90, 0.5);
        Location movedHorizontally = new Location(null, 3.5, 90, 0.5);

        assertEquals(0.0, VerificationManager.horizontalDistanceSquared(start, verticallyDisplaced));
        assertEquals(9.0, VerificationManager.horizontalDistanceSquared(start, movedHorizontally));
    }
}
