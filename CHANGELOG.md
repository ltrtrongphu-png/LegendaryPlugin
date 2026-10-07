# Changelog

All notable changes to LegendaryPlugin are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/).

## [Pro v1.0.1] — 2026-10-07

### Phase 0b — Optimization bug fixes (all default-ON)
- **Fixed chunk-unload thrash**: `suggestChunkUnloads()` now tracks per-chunk
  "empty since" timestamps and skips any chunk within render distance of an
  online player. Previously unloaded any empty chunk immediately regardless of
  the configured `min-empty-seconds: 30` grace period, causing unload→reload
  thrashing during SEVERE lag — exactly when the server can least afford it.
- **Fixed adaptive interval feedback loop**: `computeAdaptiveInterval()` now
  *widens* the tick interval as lag worsens (NORMAL=base, MILD=base,
  MODERATE=1.5×, SEVERE=2×) instead of narrowing it (÷2, ÷3). Running
  mitigation work more often during the worst lag was a plausible feedback
  loop making lag worse.
- **Removed duplicate `isPriority()` checks** in `EntityAiSkipManager` — the
  inline custom-name/PDC/leash/passenger/tameable checks duplicated
  `ProtectionRules.isProtectedMob()` which checks the exact same things.
  Now calls `ProtectionRules.isProtectedMob()` directly.
- **Removed dead `chunkTickSkipPercent` field** from `OptimizationEngine.Settings`
  — it was populated from config and included in `toString()` but never read
  by anything that actually skips a chunk tick.

### Phase 6 — Operational hardening
- **GUI click-rate protection**: `LegendaryGuiListener` now enforces a 150ms
  cooldown on all destructive GUI actions (ban, reset VL, toggle, watch) to
  prevent macro click-spam from firing the same action dozens of times per
  second.
- **/reload idempotency**: `AnticheatModule.onEnable()` and
  `OptimizationModule.onEnable()` now unregister all stale listeners at the
  start of the method before re-registering, so a Bukkit `/reload` cannot
  produce duplicate listener registrations.

### Phase 5 — Documentation
- Added `ARCHITECTURE.md` with module overview diagram and the exact
  flag→escalate→punish pipeline.
- Added `CHANGELOG.md` (this file).
- VL decay tradeoff documented in `config.yml` (already present, confirmed).
- BypassGuard critical-checks list documented in `README.md` (already present,
  confirmed).

## [Pro v1.0.0] — 2026-10-04

### Added — ML-powered detection engine
- **5 new ML/statistical checks**:
  - **BadSessions** — detects rapid session cycling (join/quit spam)
  - **PacketFingerprint** — fingerprints packet timing interval distributions
  - **BehaviorProfile** — per-player behavioral profiling with deviation detection
  - **MccAnalyze** — Monte Carlo Consistency Analysis
  - **MlDetect** — sliding-window ML-style anomaly detection
- 50 checks total (up from 45). 144 source files.
- 2500+ simulation scenarios, 100% pass rate.
- Rebranded as Pro edition.

## [v2.1.0] — 2026-10-04

### Added — Composite heuristic detection + ultra-optimization
- **3 composite heuristic checks**: CombatHeuristics, MovementHeuristics, BlockRate
- **ChunkHeatmapManager** — activity-based chunk optimization
- **MemoryPressureMonitor** — JVM heap-pressure monitor with emergency optimization
- 45 checks total. 15 optimization sub-systems.
- 2312 simulation scenarios, 100% pass rate.

## [v2.0.0] — 2026-10-04

### Changed — Research-grade overhaul
- Removed player trust scoring system (`PlayerTrustManager`)
- Threat scoring recalibrated (hack count + total VL only)
- VL persists without passive decay; all players face equal sensitivity

## [v1.8.0] — 2026-10-03

### Added — Security modules
- Anti Reverse-Engineering module (debugger detection, bytecode tampering, lockdown)
- Anti-DDoS module (rate limiting, auto-blacklist, emergency lockdown)
- `/legendaryac security` and `/legendaryac lockdown` commands
- 7 modules total (up from 5)

## [v1.7.0] — 2026-10-02

### Added — Aggressive anti-cheat + performance
- 3 new checks: GhostHand, NoKnockback, FastHeal. Total: 45 checks.
- KillAura enhanced with rotation-snap and pre-aim detection
- Lockout system — freezes players flagged by critical checks
- Aggressive escalation for critical checks
- Database: SQLite WAL mode + batch violation inserts
- ESP and replay performance optimizations

## [v1.6.0] — 2026-09-28

### Changed — Performance + reliability
- DatabaseManager: SQLite WAL, batch inserts, MySQL rewriteBatchedStatements
- ESP module: entity cache refresh halved, distance pre-checks
- Replay buffer pre-allocation

## [v1.5.0] — 2026-09-25

### Added — Premium upgrade
- TPS-adaptive sensitivity (scales flag weights down during lag)
- Composite threat scoring (0-100) with five tiers
- ThreatActionManager safety-net scanner
- `/legendaryac threats` live dashboard

## [v1.4.0] — 2026-09-23

### Added — Premium pass
- `/legendary report` server health dashboard
- Premium startup banner
- Hopper throttle fix
- Anti-cheat false-positive fixes (Jesus, Step, Spider, Blink)

## [v1.3.0] — 2026-09-19

### Added — DeepSeek-dump feature merge
- WatchManager + `/legendaryac watch`
- VanillaPhysics potion-effect multipliers
- GUI hub (Modules / Players / Checks screens)
- BungeeIntegration ban sync

## [v1.2.0] — 2026-09-19

### Added — Community features
- GeyserExemption for Bedrock players
- MythicMobsProtection
- Six new checks: Jesus, Surround, AutoTool, ShulkerNesting, ChestAura, Criticals
- BlockWhitelist utility

## [v1.1.0] — 2026-09-19

### Added — Comprehensive upgrade
- DatabaseManager wired up for violation persistence
- PaperAntiXraySupport advisory
- Verification `clickblock` method
- ElytraFlightCheck and InstaBreakCheck
- EntitySweepTask shared scan

## [v1.0.0] — 2026-09-19

### Initial release
- Modular architecture (esp, optimization, anticheat, verification, replay, database)
- 40 anti-cheat checks
- TPS-adaptive optimization engine
- Join verification anti-bot
- Replay recording and playback
