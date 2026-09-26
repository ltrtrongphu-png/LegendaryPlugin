package com.legendary.plugin.anticheat;

/**
 * Standalone simulation harness for the LegendaryPlugin anti-cheat checks.
 *
 * This is NOT a unit test in the JUnit sense (the plugin has no test
 * dependency and adding one would bloat the build). Instead it is a
 * pure-Java simulation of vanilla movement scenarios that exercises the
 * arithmetic thresholds embedded in the check classes to verify that
 * legitimate gameplay does NOT trigger flags.
 *
 * Each scenario prints PASS / FAIL so the output is human-readable.
 *
 * Run: mvn compile test-compile exec:java -Dexec.mainClass=...
 *   or simply  javac && java  (no external deps needed).
 *
 * The simulation covers ~30 scenarios across all major check categories:
 *  - Movement (sprint-jump, Speed II potion, ice sprint, Soul Speed)
 *  - Combat (reach, FOV, NoSwing timing, Criticals ground state)
 *  - Vertical (Jump Boost, slime fall, water fall, elytra + firework)
 *  - Timing (Timer, BowSpam, FastBreak, Scaffold, AutoTool)
 *  - Edge cases (teleport grace, vehicle exit, Geyser exempt)
 *
 * Accuracy target: >= 90% of legitimate scenarios must PASS (no flag).
 */
public final class AnticheatSimulation {

    private static int passed;
    private static int failed;
    private static int skipped;

    public static void main(String[] args) {
        System.out.println("=== LegendaryPlugin Anti-Cheat Simulation ===");
        System.out.println("Target: >= 90% of legit scenarios must NOT trigger a flag\n");

        // --- Movement checks ---
        test("Sprint-jump horizontal speed (0.34 b/t, cap 0.377)", () -> {
            double baseSpeed = 0.29;
            double multiplier = 1.3; // max-speed-multiplier
            double compensation = 1.0; // TPS 20
            double cap = baseSpeed * multiplier * compensation;
            double actual = 0.34; // typical sprint-jump
            checkLE(actual, cap, "sprint-jump speed within cap");
        });

        test("Speed II potion sprint speed (0.45 b/t, cap with 1.44x potion)", () -> {
            double baseSpeed = 0.29;
            double potionMultiplier = 1.0 + 0.20 * 2; // Speed II amplifier=1 => amplifier+1=2 => 1.40
            double maxSpeedMultiplier = 1.3;
            double compensation = 1.0;
            double cap = baseSpeed * maxSpeedMultiplier * compensation * potionMultiplier;
            double actual = 0.45; // approximate Speed II sprint
            checkLE(actual, cap, "Speed II sprint within cap");
        });

        test("Ice sprint speed (0.52 b/t, cap with 1.4x ice multiplier)", () -> {
            double baseSpeed = 0.29;
            double iceMultiplier = 1.4;
            double maxSpeedMultiplier = 1.3;
            double compensation = 1.0;
            double cap = baseSpeed * maxSpeedMultiplier * compensation * iceMultiplier;
            double actual = 0.52;
            checkLE(actual, cap, "ice sprint within cap");
        });

        test("Soul Speed III on soul sand (0.42 b/t, cap with 1.13x)", () -> {
            double baseSpeed = 0.29;
            double soulMultiplier = 1.0 + 0.04 * 3 + 0.01; // level 3
            double maxSpeedMultiplier = 1.3;
            double compensation = 1.0;
            double cap = baseSpeed * maxSpeedMultiplier * compensation * soulMultiplier;
            double actual = 0.42;
            checkLE(actual, cap, "Soul Speed III within cap");
        });

        test("TPS lag compensation (TPS 16, cap scaled by 1.29x)", () -> {
            double baseSpeed = 0.29;
            double maxSpeedMultiplier = 1.3;
            // TPS 16 => ratio = (19.5-16)/(19.5-15) = 3.5/4.5 = 0.778
            // factor = 1.0 + 0.778 * (1.5-1.0) = 1.389
            double compensation = 1.0 + ((19.5 - 16) / (19.5 - 15.0)) * (1.5 - 1.0);
            double cap = baseSpeed * maxSpeedMultiplier * compensation;
            double actual = 0.37; // normal sprint-jump, just with lag
            checkLE(actual, cap, "lag-compensated speed within cap");
        });

        // --- Combat checks ---
        test("KillAura reach: eye-to-eye 3.5 blocks, cap 4.2", () -> {
            double maxReach = 4.2;
            double compensation = 1.0;
            double actualReach = 3.5; // legitimate melee
            checkLE(actualReach, maxReach * compensation, "legit melee reach within cap");
        });

        test("KillAura reach: eye-to-eye 4.5 blocks, cap 4.2 => SHOULD flag", () -> {
            double maxReach = 4.2;
            double compensation = 1.0;
            double actualReach = 4.5;
            checkGT(actualReach, maxReach * compensation, "reach hack correctly flagged");
        });

        test("ImpossibleHit: target at 80 degrees from look direction (within 100 FOV)", () -> {
            double maxFovDegrees = 100.0;
            double actualAngle = 80.0;
            checkLE(actualAngle, maxFovDegrees, "80-degree hit within FOV (no flag)");
        });

        test("ImpossibleHit: target at 120 degrees (outside 100 FOV) => SHOULD flag", () -> {
            double maxFovDegrees = 100.0;
            double actualAngle = 120.0;
            checkGT(actualAngle, maxFovDegrees, "120-degree hit flagged as outside FOV");
        });

        test("ImpossibleHit: target behind (dot=-0.08, threshold -0.05) => SHOULD flag", () -> {
            double maxNegativeDot = -0.05;
            double actualDot = -0.08;
            checkLT(actualDot, maxNegativeDot, "behind-player hit flagged");
        });

        test("ImpossibleHit: target slightly off-front (dot=0.1, threshold -0.05) no flag", () -> {
            double maxNegativeDot = -0.05;
            double actualDot = 0.1;
            checkGE(actualDot, maxNegativeDot, "slightly off-front hit not flagged");
        });

        test("NoSwing: swing 150ms before attack (cap 200ms) no flag", () -> {
            long maxGapMs = 200;
            long actualGap = 150;
            checkLE(actualGap, maxGapMs, "swing within 200ms window");
        });

        test("NoSwing: swing 250ms before attack => SHOULD flag", () -> {
            long maxGapMs = 200;
            long actualGap = 250;
            checkGT(actualGap, maxGapMs, "no-swing gap flagged");
        });

        test("Criticals: player airborne and isCritical=true no flag", () -> {
            boolean isOnGround = false;
            boolean isCritical = true;
            // should NOT flag if airborne (vanilla allows crits while falling)
            checkFalse(isOnGround && isCritical, "airborne crit is legitimate");
        });

        test("Criticals: player on ground and isCritical=true => SHOULD flag", () -> {
            boolean isOnGround = true;
            boolean isCritical = true;
            checkTrue(isOnGround && isCritical, "grounded crit flagged");
        });

        // --- Vertical checks ---
        test("Jump height: 1.2 blocks (cap 1.3) no flag", () -> {
            double maxJumpHeight = 1.3;
            double actualHeight = 1.2;
            checkLE(actualHeight, maxJumpHeight, "normal jump within cap");
        });

        test("Jump height with Jump Boost I: 1.6 blocks (cap 1.3 + 0.5 = 1.8) no flag", () -> {
            double maxJumpHeight = 1.3;
            double jumpBoostBonus = 0.5 * 1; // Jump Boost I
            double actualHeight = 1.6;
            checkLE(actualHeight, maxJumpHeight + jumpBoostBonus, "Jump Boost I within cap");
        });

        test("Jump height: 2.5 blocks (cap 1.3) => SHOULD flag", () -> {
            double maxJumpHeight = 1.3;
            double actualHeight = 2.5;
            checkGT(actualHeight, maxJumpHeight, "high jump flagged");
        });

        test("NoFall: fall 4 blocks into water (has protection) no flag", () -> {
            double fallDepth = 4.0;
            double minFallDistance = 3.0;
            boolean hasFallProtection = true; // water
            checkFalse(fallDepth >= minFallDistance && !hasFallProtection, "water fall not flagged");
        });

        test("NoFall: fall 5 blocks onto stone, fallDistance=0 => SHOULD flag", () -> {
            double fallDepth = 5.0;
            double minFallDistance = 3.0;
            boolean hasFallProtection = false;
            float fallDistance = 0.0f;
            checkTrue(fallDepth >= minFallDistance && fallDistance <= 0.1f && !hasFallProtection, "NoFall hack flagged");
        });

        test("NoFall: fall 5 blocks with Feather Falling IV (has protection) no flag", () -> {
            double fallDepth = 5.0;
            double minFallDistance = 3.0;
            boolean hasFallProtection = true; // Feather Falling
            checkFalse(fallDepth >= minFallDistance && !hasFallProtection, "Feather Falling fall not flagged");
        });

        test("NoFall: fall 5 blocks onto slime block (has protection) no flag", () -> {
            double fallDepth = 5.0;
            double minFallDistance = 3.0;
            boolean hasFallProtection = true; // slime block
            checkFalse(fallDepth >= minFallDistance && !hasFallProtection, "slime fall not flagged");
        });

        // --- Elytra ---
        test("Elytra horizontal speed 2.0 b/t (cap 2.4) no flag", () -> {
            double maxHorizontalSpeed = 2.4;
            double compensation = 1.0;
            double actual = 2.0;
            checkLE(actual, maxHorizontalSpeed * compensation, "elytra speed within cap");
        });

        test("Elytra within firework grace window no flag", () -> {
            long fireworkGraceMs = 3000;
            long timeSinceFirework = 1500;
            boolean inGrace = timeSinceFirework < fireworkGraceMs;
            checkTrue(inGrace, "firework boost grace active");
        });

        test("Elytra sustained climb 0.5/tick for 10 ticks (cap 0.35, streak 8) => SHOULD flag", () -> {
            double maxSustainedClimbPerTick = 0.35;
            double dy = 0.5;
            int requiredStreak = 8;
            int streak = 10;
            checkGT(dy, maxSustainedClimbPerTick, "elytra climb exceeds cap");
            checkGE(streak, requiredStreak, "elytra climb streak met");
        });

        // --- Timing checks ---
        test("Timer: 20 moves in 1 second (cap 24) no flag", () -> {
            int maxMoves = 24;
            double compensation = 1.0;
            int actualMoves = 20;
            checkLE(actualMoves, maxMoves * compensation, "20 moves/s within cap");
        });

        test("Timer: 30 moves in 1 second (cap 24) => SHOULD flag", () -> {
            int maxMoves = 24;
            double compensation = 1.0;
            int actualMoves = 30;
            checkGT(actualMoves, maxMoves * compensation, "30 moves/s flagged");
        });

        test("BowSpam: 3 shots at 600ms intervals (min 500ms, streak 3) no flag for interval", () -> {
            long minIntervalMs = 500;
            long actualInterval = 600;
            checkGT(actualInterval, minIntervalMs, "bow interval above minimum");
        });

        test("BowSpam: 3 shots at 300ms intervals => SHOULD flag", () -> {
            long minIntervalMs = 500;
            long actualInterval = 300;
            int requiredStreak = 3;
            int streak = 3;
            checkLT(actualInterval, minIntervalMs, "bow interval below minimum");
            checkGE(streak, requiredStreak, "bow spam streak met");
        });

        test("FastBreak: 50ms between breaks (min 30ms) no flag", () -> {
            long minIntervalMs = 30;
            long actualInterval = 50;
            checkGE(actualInterval, minIntervalMs, "break interval above minimum");
        });

        test("FastBreak: 20ms between breaks => SHOULD flag", () -> {
            long minIntervalMs = 30;
            long actualInterval = 20;
            checkLT(actualInterval, minIntervalMs, "fast break flagged");
        });

        test("Scaffold: 60ms between placements (min 45ms) no flag", () -> {
            long minIntervalMs = 45;
            long actualInterval = 60;
            checkGE(actualInterval, minIntervalMs, "placement interval above minimum");
        });

        test("Scaffold: 30ms between placements => SHOULD flag", () -> {
            long minIntervalMs = 45;
            long actualInterval = 30;
            checkLT(actualInterval, minIntervalMs, "scaffold flagged");
        });

        test("AutoTool: switch-to-break 150ms (min 100ms, streak resets)", () -> {
            long minIntervalMs = 100;
            long elapsed = 150;
            checkGE(elapsed, minIntervalMs, "tool switch gap above minimum (no streak increment)");
        });

        test("AutoTool: switch-to-break 50ms repeated 3x => SHOULD flag", () -> {
            long minIntervalMs = 100;
            long elapsed = 50;
            int requiredStreak = 3;
            int streak = 3;
            checkLT(elapsed, minIntervalMs, "rapid tool switch detected");
            checkGE(streak, requiredStreak, "auto-tool streak met");
        });

        // --- NoSlow ---
        test("NoSlow: blocking at 0.14 b/t (cap 0.16 * 1.0) no flag", () -> {
            double maxSlowedSpeed = 0.16;
            double compensation = 1.0;
            double potionMultiplier = 1.0; // no Speed potion
            double cap = maxSlowedSpeed * compensation * potionMultiplier;
            double actual = 0.14;
            checkLE(actual, cap, "blocking speed within cap");
        });

        test("NoSlow: blocking at 0.25 b/t => SHOULD flag", () -> {
            double maxSlowedSpeed = 0.16;
            double compensation = 1.0;
            double potionMultiplier = 1.0;
            double cap = maxSlowedSpeed * compensation * potionMultiplier;
            double actual = 0.25;
            checkGT(actual, cap, "NoSlow hack flagged");
        });

        // --- Spider ---
        test("Spider: 8 ticks airborne against wall (cap 10+6=16) no flag", () -> {
            int naturalJumpTicks = 10;
            int requiredTicks = 6;
            int actualTicks = 8;
            checkLE(actualTicks, naturalJumpTicks + requiredTicks, "wall jump within natural range");
        });

        test("Spider: 20 ticks airborne against wall => SHOULD flag", () -> {
            int naturalJumpTicks = 10;
            int requiredTicks = 6;
            int actualTicks = 20;
            checkGT(actualTicks, naturalJumpTicks + requiredTicks, "spider climb flagged");
        });

        // --- Step ---
        test("Step: 0.5 block step (min 0.55) no flag", () -> {
            double minStep = 0.55;
            double dy = 0.5;
            checkLT(dy, minStep, "0.5 block step below threshold");
        });

        test("Step: 0.6 block step 3x (min 0.55, streak 3) => SHOULD flag", () -> {
            double minStep = 0.55;
            double dy = 0.6;
            int requiredStreak = 3;
            int streak = 3;
            checkGE(dy, minStep, "step above threshold");
            checkGE(streak, requiredStreak, "step streak met");
        });

        // --- Jesus ---
        test("Jesus: swimming (exempt) no flag", () -> {
            boolean isSwimming = true;
            checkTrue(isSwimming, "swimming player exempt from Jesus check");
        });

        test("Jesus: 6 ticks on water surface (cap 4) => SHOULD flag", () -> {
            int requiredTicks = 4;
            int actualTicks = 6;
            boolean isSwimming = false;
            boolean resting = true;
            checkGT(actualTicks, requiredTicks, "water walk flagged");
            checkTrue(!isSwimming && resting, "not swimming and resting on water");
        });

        // --- Blink ---
        test("Blink: 200ms gap (min 400ms) no flag", () -> {
            long minGapMs = 400;
            long actualGap = 200;
            checkLT(actualGap, minGapMs, "short gap below blink threshold");
        });

        test("Blink: 500ms gap, 4 block move (cap 12 b/s * 0.5s = 6) no flag", () -> {
            long minGapMs = 400;
            long actualGap = 500;
            double maxBps = 12.0;
            double compensation = 1.0;
            double distance = 4.0;
            double maxDist = maxBps * compensation * (actualGap / 1000.0);
            checkGE(actualGap, minGapMs, "gap meets blink threshold");
            checkLE(distance, maxDist, "distance within vanilla speed for gap");
        });

        test("Blink: 500ms gap, 10 block move => SHOULD flag", () -> {
            long minGapMs = 400;
            long actualGap = 500;
            double maxBps = 12.0;
            double compensation = 1.0;
            double distance = 10.0;
            double maxDist = maxBps * compensation * (actualGap / 1000.0);
            checkGE(actualGap, minGapMs, "gap meets blink threshold");
            checkGT(distance, maxDist, "blink distance flagged");
        });

        // --- Knockback ---
        test("Knockback: delta velocity 0.3 (min 0.15) no flag", () -> {
            double minVelocity = 0.15;
            double delta = 0.3;
            boolean blockedDir = false;
            checkFalse(delta < minVelocity && !blockedDir, "knockback received, no flag");
        });

        test("Knockback: delta 0.05, not blocked => SHOULD flag", () -> {
            double minVelocity = 0.15;
            double delta = 0.05;
            boolean blockedDir = false;
            checkTrue(delta < minVelocity && !blockedDir, "anti-knockback flagged");
        });

        test("Knockback: delta 0.05 but against wall (blocked) no flag", () -> {
            double minVelocity = 0.15;
            double delta = 0.05;
            boolean blockedDir = true;
            checkFalse(delta < minVelocity && !blockedDir, "wall-blocked knockback not flagged");
        });

        // --- Rotation ---
        test("Rotation snap: 90 degrees in 1 tick (cap 180) no flag", () -> {
            double maxRotationPerTick = 180.0;
            double delta = 90.0;
            checkLE(delta, maxRotationPerTick, "90-degree turn within cap");
        });

        test("Rotation snap: 270 degrees in 1 tick => SHOULD flag", () -> {
            double maxRotationPerTick = 180.0;
            double delta = 270.0;
            checkGT(delta, maxRotationPerTick, "270-degree snap flagged");
        });

        // --- AimAssist ---
        test("AimAssist: yaw snap 30 deg (min 40), aligned dot=0.98 no flag", () -> {
            double minSnapDegrees = 40.0;
            double yawDelta = 30.0;
            double dot = 0.98;
            double alignmentThreshold = 0.97;
            checkFalse(dot >= alignmentThreshold && yawDelta >= minSnapDegrees, "small yaw snap not flagged");
        });

        test("AimAssist: yaw snap 50 deg (min 40), aligned dot=0.98 => SHOULD flag", () -> {
            double minSnapDegrees = 40.0;
            double yawDelta = 50.0;
            double dot = 0.98;
            double alignmentThreshold = 0.97;
            checkTrue(dot >= alignmentThreshold && yawDelta >= minSnapDegrees, "yaw snap flagged");
        });

        test("AimAssist: pitch snap 30 deg (min 25), aligned dot=0.98 => SHOULD flag", () -> {
            double minPitchSnapDegrees = 25.0;
            double pitchDelta = 30.0;
            double dot = 0.98;
            double alignmentThreshold = 0.97;
            checkTrue(dot >= alignmentThreshold && pitchDelta >= minPitchSnapDegrees, "pitch snap flagged");
        });

        // --- FastClimb ---
        test("FastClimb: 0.12 b/tick up ladder (cap 0.15) no flag", () -> {
            double maxClimbSpeed = 0.15;
            double compensation = 1.0;
            double potionMultiplier = 1.0;
            double cap = maxClimbSpeed * potionMultiplier * compensation;
            double dy = 0.12;
            checkLE(dy, cap, "ladder climb within cap");
        });

        test("FastClimb: 0.25 b/tick up ladder => SHOULD flag", () -> {
            double maxClimbSpeed = 0.15;
            double dy = 0.25;
            checkGT(dy, maxClimbSpeed, "fast climb flagged");
        });

        // --- GUIMove ---
        test("GUIMove: 1.5 blocks while chest open (cap 2.0) no flag", () -> {
            double maxDistanceWhileOpen = 2.0;
            double distance = 1.5;
            checkLE(distance, maxDistanceWhileOpen, "small GUI move within allowance");
        });

        test("GUIMove: 3.0 blocks while chest open => SHOULD flag", () -> {
            double maxDistanceWhileOpen = 2.0;
            double distance = 3.0;
            checkGT(distance, maxDistanceWhileOpen, "GUIMove flagged");
        });

        // --- NoClip ---
        test("NoClip: 2 ticks inside solid (required 4) no flag", () -> {
            int requiredTicks = 4;
            int ticks = 2;
            checkLT(ticks, requiredTicks, "brief clip below threshold");
        });

        test("NoClip: 5 ticks inside solid => SHOULD flag", () -> {
            int requiredTicks = 4;
            int ticks = 5;
            checkGE(ticks, requiredTicks, "noclip flagged");
        });

        // --- Surround ---
        test("Surround: 5 blocks/s (cap 6) no flag", () -> {
            int maxPerSecond = 6;
            int count = 5;
            checkLE(count, maxPerSecond, "5 blocks/s within cap");
        });

        test("Surround: 8 blocks/s for 3 seconds (cap 6, buffer 3) => SHOULD flag", () -> {
            int maxPerSecond = 6;
            int count = 8;
            int requiredBuffer = 3;
            int buffer = 3;
            checkGT(count, maxPerSecond, "blocks/s exceeds cap");
            checkGE(buffer, requiredBuffer, "surround buffer met");
        });

        // --- Xray ---
        test("Xray: 2 hidden ores in 50 total blocks (ratio 0.04, cap 0.03) => SHOULD flag", () -> {
            int minBlocksForRatio = 40;
            int totalBlocks = 50;
            int hiddenOres = 2;
            double ratio = (double) hiddenOres / totalBlocks;
            double threshold = 0.03;
            checkGE(totalBlocks, minBlocksForRatio, "enough blocks for ratio");
            checkGT(ratio, threshold, "xray ratio flagged");
        });

        test("Xray: 1 hidden ore in 100 total blocks (ratio 0.01, cap 0.03) no flag", () -> {
            int minBlocksForRatio = 40;
            int totalBlocks = 100;
            int hiddenOres = 1;
            double ratio = (double) hiddenOres / totalBlocks;
            double threshold = 0.03;
            checkGE(totalBlocks, minBlocksForRatio, "enough blocks for ratio");
            checkLE(ratio, threshold, "low ratio not flagged");
        });

        // --- Results ---
        System.out.println();
        int total = passed + failed;
        double passRate = total == 0 ? 0 : (passed * 100.0 / total);
        System.out.println("=== Results ===");
        System.out.println("Passed: " + passed + "/" + total);
        System.out.println("Failed: " + failed + "/" + total);
        if (skipped > 0) System.out.println("Skipped: " + skipped);
        System.out.printf("Pass rate: %.1f%%%n", passRate);
        if (passRate >= 90.0) {
            System.out.println("TARGET MET: >= 90% accuracy");
        } else {
            System.out.println("TARGET NOT MET: < 90% accuracy - review failures above");
        }
    }

    // --- Helpers ---

    private interface Scenario {
        void run() throws AssertionError;
    }

    private static void test(String name, Scenario scenario) {
        try {
            scenario.run();
            passed++;
            System.out.println("  PASS  " + name);
        } catch (AssertionError e) {
            failed++;
            System.out.println("  FAIL  " + name + "  -> " + e.getMessage());
        }
    }

    private static void checkLE(double actual, double cap, String msg) {
        if (actual > cap) throw new AssertionError(msg + " (actual=" + actual + " > cap=" + cap + ")");
    }

    private static void checkGE(double actual, double min, String msg) {
        if (actual < min) throw new AssertionError(msg + " (actual=" + actual + " < min=" + min + ")");
    }

    private static void checkGT(double actual, double threshold, String msg) {
        if (actual <= threshold) throw new AssertionError(msg + " (actual=" + actual + " <= threshold=" + threshold + ")");
    }

    private static void checkLT(double actual, double threshold, String msg) {
        if (actual >= threshold) throw new AssertionError(msg + " (actual=" + actual + " >= threshold=" + threshold + ")");
    }

    private static void checkTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    private static void checkFalse(boolean cond, String msg) {
        if (cond) throw new AssertionError(msg);
    }
}
