# LegendaryPlugin Architecture

## Module overview

```
LegendaryPlugin (main class)
  │
  ├── core/ModuleManager ── owns lifecycle of all modules
  │     ├── Module interface: onEnable / onDisable / reload / isEnabled
  │     └── Modules sorted by priority, enabled ascending, disabled reverse
  │
  ├── modules/anticheat/ ── Anti-Cheat (50 checks)
  │     ├── AnticheatModule ── single entry point; Check subclasses call flag()
  │     ├── Check (base class) ── shared config-loading, shouldSkip(), TPS compensation
  │     ├── CheckRegistry ── registers/unregisters listeners per check
  │     ├── ViolationManager ── per-player per-check VL, persists to DatabaseManager
  │     ├── EscalationManager ── per-check escalation ladder (alert→kick→ban)
  │     ├── ThreatActionManager ── READ-ONLY dashboard; does NOT punish
  │     ├── AlertManager ── broadcast alerts to staff with legendary.anticheat.alerts
  │     ├── BanExecutor ── executes ban command, syncs to Bungee if enabled
  │     ├── BypassGuard ── centralized bypass-permission logic + critical-check protection
  │     ├── LockoutManager ── freezes players flagged by critical checks
  │     ├── TpsMonitor ── TPS sampling + adaptive sensitivity multiplier
  │     ├── HackProfileManager ── tracks active hacks per player, computes threat score
  │     ├── WatchManager ── per-player detailed alert subscription for staff
  │     ├── CheckProfile ── preset-based check enable/disable + weight multiplier
  │     ├── checks/ ── 50 detection implementations
  │     ├── listeners/ ── ProtocolLib packet checks (InvalidPacket, PacketFlood, Disabler)
  │     └── physics/VanillaPhysics ── potion-effect speed/jump modifiers
  │
  ├── modules/esp/ ── Anti-ESP / Anti-Xray
  │     ├── EspModule ── coordinates visibility, obfuscation, view distance
  │     ├── BlockObfuscationManager ── rewrites BLOCK_CHANGE/MULTI_BLOCK_CHANGE packets
  │     ├── EntityOcclusionManager ── hides entities behind walls (ProtocolLib)
  │     ├── PlayerVisibilityManager ── hides distant players beyond render distance
  │     ├── ViewDistanceManager ── per-player send-view-distance scaling
  │     ├── AntiFreeCamManager ── detects freecam via packet-level position checks
  │     └── PaperAntiXraySupport ── advisory check for Paper's built-in anti-xray
  │
  ├── modules/optimization/ ── Server Optimization
  │     ├── OptimizationModule ── main tick loop, TPS monitoring, level transitions
  │     ├── OptimizationEngine ── maps TPS → Level → Settings
  │     ├── WorldOptimizer ── view/simulation distance scaling
  │     ├── EntityOptimizer ── mob spawn multiplier scaling
  │     ├── EntitySweepTask ── single shared entity scan feeding 5 sub-systems
  │     ├── MobCapEnforcer, ArmorStandLimiter, FallingBlockLimiter
  │     ├── ItemMergeTask, XpOrbMergeTask ── entity merging to reduce counts
  │     ├── ClearEntitiesTask ── periodic or admin-triggered entity cleanup
  │     ├── EntityAiSkipManager ── disables AI for distant mobs during lag
  │     ├── HopperThrottleListener, RedstoneLimiterListener ── event throttling
  │     ├── JoinRampOptimizer ── staged view-distance ramp on player join
  │     ├── ChunkHeatmapManager ── activity-based chunk priority for entity sweeps
  │     ├── ChunkHealthReporter ── per-chunk diagnostic reports
  │     ├── MemoryPressureMonitor ── JVM heap monitor, triggers emergency optimization
  │     ├── HistoryFileWriter ── async TPS history to CSV
  │     └── ProtectionRules ── shared "is this entity/item protected?" rule set
  │
  ├── modules/verification/ ── Join Verification (anti-bot)
  │     ├── VerificationModule ── join-limbo flow (move or clickblock method)
  │     ├── VerificationManager ── tracks verification state per player
  │     ├── VerificationListener ── event handling for limbo players
  │     ├── IpRateLimiter ── per-IP and global join rate limiting
  │     └── VoidGenerator ── generates the void world for limbo
  │
  ├── modules/replay/ ── Replay Recording
  │     ├── ReplayModule ── rolling buffer + clip saving on flag
  │     ├── ReplayRecorder ── per-player snapshot recording
  │     ├── ReplayPlaybackManager ── staff playback of saved clips
  │     ├── ReplayClip ── a saved clip (metadata + snapshots)
  │     └── ReplaySnapshot ── single tick of player state
  │
  ├── modules/database/ ── Database (SQLite/MySQL)
  │     └── DatabaseManager ── async violation + ban persistence, batched inserts
  │
  ├── modules/security/ ── Security
  │     ├── AntiDdosModule + AntiDdosManager ── connection rate limiting, blacklist
  │     └── AntiReverseEngineeringModule + Manager ── debugger/tampering detection, lockdown
  │
  ├── gui/ ── Admin GUI (Modules / Players / Checks screens)
  ├── integrations/ ── PlaceholderAPI expansion, Discord webhook, Bungee ban sync
  ├── commands/ ── /legendary and /legendaryac command handlers
  └── util/ ── Text (MiniMessage), RaycastUtils, CoordCodec, etc.
```

## Flag → Escalate → Punish pipeline

There is exactly **one** code path from "check flags a player" to "player gets punished":

```
Check.flag(player, weight, detail)
  │
  ▼
AnticheatModule.flag()
  ├── ViolationManager.addViolation() → VL increased, persisted to DB
  ├── HackProfileManager.recordFlag() → tracks active hacks
  ├── ThreatActionManager.onFlag() → triggers rapid-response mode (READ-ONLY, no punish)
  ├── BypassGuard.onFlagCheck() → OP self-check for compromised accounts
  ├── ReplayModule.saveClipOnFlag() → saves replay clip
  └── AnticheatModule.flagRaw()
        ├── AlertManager.alertViolation() → broadcasts to staff
        ├── DatabaseManager.recordViolationAsync() → persists full context
        ├── WatchManager.notifyWatchers() → detailed alert for watched players
        ├── EscalationManager.escalate() → per-check ladder: alert→alert→kick→ban
        └── LockoutManager.lock() (if check is in lockout list) → freezes player

ThreatActionManager (read-only dashboard)
  └── Runs periodic scan, logs high-threat players, powers /legendaryac threats
  └── Does NOT kick or ban — punishment is exclusively via EscalationManager
```

A single flag from a single check can only reach a ban through `EscalationManager`'s
per-check escalation ladder. `ThreatActionManager` is read-only by design.

## VL persistence model

- VL **does not decay** within a session. Violations persist until manually reset
  or the player relogs (which clears all VL).
- Tradeoff: a false positive early in a long session stays on the player's record
  for that whole session. Staff can clear via `/legendaryac clearvl <player>`.
- `ThreatActionManager`'s threat score weighting accounts for this stickiness by
  requiring a minimum per-check VL before any dashboard alert.

## /reload safety

`AnticheatModule.onEnable()` and `OptimizationModule.onEnable()` unregister all
stale listeners at the start of the method before re-registering, so a Bukkit
`/reload` (which re-runs `onEnable()` without a matching `onDisable()`) cannot
produce duplicate listener registrations.

## GUI click-rate protection

`LegendaryGuiListener` enforces a 150ms cooldown on all destructive GUI actions
(ban, reset VL, toggle check, toggle module, watch) to prevent macro click-spam
from firing the same action dozens of times per second. Navigation clicks
(open:main, open:players, etc.) are exempt from the cooldown.
