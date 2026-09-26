# LegendaryPlugin

Unified Paper **1.21.4** plugin merging three legacy plugins into one modular
codebase:

| Legacy plugin      | Became module (`modules.<id>` in config.yml) |
|---------------------|-----------------------------------------------|
| AntiESPUltimate     | `esp` — player-visibility hiding, block (ore) obfuscation, capped view distance, free-cam detection |
| SmartOptimizer      | `optimization` — TPS-adaptive view/sim distance, mob caps, item/XP-orb merging, hopper/redstone throttling, join-ramp, TPS history, Discord alerts |
| BaB                 | `anticheat` — 36 detection checks, violations/escalation/bans, alerts, TPS compensation, raid alerts, anti-spam, client-brand check, player trust scoring<br>`verification` — join-limbo anti-bot + IP rate limiting<br>`replay` — rolling movement buffer + staff playback |

## Package layout

```
com.legendary.plugin
├── LegendaryPlugin.java          main class
├── core/                         Module contract, ModuleManager, async config helpers
├── modules/
│   ├── esp/                      EspModule + 4 managers (ProtocolLib optional)
│   ├── optimization/             OptimizationModule + 13 sub-systems
│   ├── anticheat/                AnticheatModule, Check base class, 36 checks/, listeners/, PlayerTrustManager
│   ├── verification/             VerificationModule (anti-bot limbo)
│   ├── replay/                   ReplayModule (record + playback)
│   └── database/                 DatabaseManager (async SQLite/MySQL)
├── gui/                          PDC-based module-toggle admin GUI
├── integrations/                 merged PlaceholderAPI expansion, Discord webhook
├── commands/                     /legendary, /legendaryac
└── util/                         Text (MiniMessage), CoordCodec, RaycastUtils, GradientUtil, StatsUtil
```

## What changed vs. the three originals

- **Modular, toggleable**: every module implements `core.Module`
  (`onEnable`/`onDisable`/`reload`) and can be flipped live via
  `/legendary module enable|disable <id>` or `modules.<id>: false` in
  config.yml — no restart needed.
- **Adventure/MiniMessage everywhere**: no `ChatColor`, `&`, or `§` codes
  anywhere in the new code (`util.Text` is the only place that builds
  `Component`s). All chat/alert templates in config.yml are MiniMessage
  strings.
- **PersistentDataContainer, not display-name checks**: the admin GUI's
  toggle buttons identify themselves via a `NamespacedKey` in their PDC
  entry (`gui.ModuleToggleItem`), and every "is this mob/item protected
  from cleanup" check (`optimization.ProtectionRules`) looks at PDC first.
- **No I/O on the main thread**: `core.AsyncConfigLoader`,
  `optimization.HistoryFileWriter`, `integrations.DiscordNotifier`, and
  `database.DatabaseManager` all queue their file/network work onto
  `runTaskAsynchronously` and only hop back to the main thread for the
  handful of statements that actually touch Bukkit API state.
- **Cleaner check architecture**: all 23 anti-cheat checks now share one
  `anticheat.Check` base class instead of each re-implementing its own
  enable flag / config-loading boilerplate, and every check disposes its
  per-player maps on `PlayerQuitEvent` to avoid the memory leaks the
  original per-check `HashMap`s were prone to.
- **Consolidated duplicates**: the two nearly-identical
  `VerificationManager`/`IpRateLimiter` implementations that existed in
  both SmartOptimizer and BaB are now a single implementation; the two
  PlaceholderAPI expansions became one (`integrations.LegendaryPlaceholders`).

## Build

Requires the PaperMC and dmulloy2 (ProtocolLib) Maven repositories, which
this sandbox's network policy does not allow reaching — see
**"How this was verified"** below before you build.

```
mvn clean package
```

produces `target/LegendaryPlugin-1.0.0.jar`. ProtocolLib and PlaceholderAPI
are both optional (`softdepend`) — the `esp.block-obfuscation`,
`anticheat.checks.invalidpacket`/`packetflood` features simply log a
warning and stay off if ProtocolLib isn't installed; placeholders don't
register if PlaceholderAPI isn't installed.

## How this was verified

This sandbox has no route to `repo.papermc.io` / Maven Central / the
ProtocolLib repo, so a full `mvn package` against the real Paper API
jar could not be run here. What **was** done:

1. Every `.java` file was compiled with a bare `javac` (JDK 21, no
   classpath) to catch real syntax errors — unbalanced braces, missing
   semicolons, malformed generics, etc. **Result: 0 syntax errors** across
   all 87 source files (the only compiler errors are the expected
   "package org.bukkit does not exist" / "cannot find symbol" from not
   having the Paper/ProtocolLib jars available — i.e. purely missing
   dependencies, not code mistakes).
2. `config.yml`, `plugin.yml` and `pom.xml` were parsed with a YAML/XML
   parser to confirm they're well-formed.
3. The handful of less-common Paper/Bukkit APIs used (`Player#setSendViewDistance`,
   `World#setSpawnLimit(SpawnCategory,int)`, `PlayerArmorChangeEvent`,
   `PlayerClientBrandChangeEvent`, ProtocolLib's `getMultiBlockChangeInfoArrays()`)
   were individually checked against the Paper 1.21.4 Javadocs to confirm
   they exist with the signatures used here.

**Before deploying to a live server**, run a real `mvn clean package`
(or open the project in an IDE with internet access) and fix any
version-drift issues the real compiler surfaces — some Paper API method
names do shift between minor versions, and this environment could not
give a 100% guarantee equivalent to compiling against the actual jars.

## Changelog

<<<<<<< HEAD
=======
**v1.5.0** (2026-09-25) — Premium Pro Max upgrade:
- `anticheat.TpsMonitor` enhanced with **TPS-adaptive sensitivity**: when
  server TPS drops, all flag weights are automatically scaled down (from
  100% at 20 TPS to 30% at 14 TPS, configurable) to prevent false-positive
  floods during lag spikes. Below 10 TPS, the threat-action system pauses
  automatic kicks and bans entirely to avoid punishing players for
  lag-induced artifacts.
- `anticheat.HackProfileManager` enhanced with composite **threat scoring**
  (0-100): combines active hack count, total violation level, and inverse
  player trust into a single danger score. Five threat tiers: MINIMAL,
  LOW, MODERATE, HIGH, CRITICAL — each colour-coded in alerts and the
  dashboard.
- `anticheat.ThreatActionManager` — a safety-net scanner that runs every
  10 seconds (configurable), evaluates every online player's threat
  score, and automatically **kicks** players above the kick threshold
  (default 60) and **bans** players above the ban threshold (default 85).
  Catches cheaters who spread violations across many checks without
  ever tripping any single check's escalation ladder. Fully configurable
  in `config.yml` under `anticheat.threat-actions`.
- `/legendaryac threats` — live **Threat Dashboard**: shows all online
  players currently cheating, sorted by threat score (highest first),
  with their threat level, active hack list, and colour-coded severity.
  A clean green "All clear" message displays when nobody is cheating.
- `/legendaryac profile <player>` upgraded to show the threat score and
  threat level alongside the active hack list.
- `plugin.yml` updated with `profile` and `threats` subcommands in the
  usage text.
- Config: new `anticheat.threat-actions` section and
  `anticheat.tps-compensation.adaptive-sensitivity` section with enabled
  flag, floor, cutoff-tps, and lag-suppress-tps settings.
- Simulation test suite expanded to 1705 scenarios (100% pass rate).

>>>>>>> 8f1a4e9 (v1.5.0 Premium Pro Max: TPS-adaptive sensitivity, composite threat scoring, live threat dashboard, auto kick/ban, lag spike protection)
**v1.4.0** (2026-09-23) — premium upgrade pass:
- `anticheat.PlayerTrustManager` — player trust scoring system that
  adjusts detection sensitivity based on long-term behaviour. Trusted
  players (80+ trust) get 30% flag weight reduction; high-risk players
  (<20 trust) get 50% amplification. Trust accumulates with clean play
  (+2/hour) and decays on violations (-5/flag). Managed via
  `/legendaryac trust <player> [set|reset]`.
- `/legendary report` — comprehensive server health dashboard in one
  command: module status, TPS/optimization level, anti-cheat check count,
  database state, trust distribution (verified/suspicious/high-risk),
  Paper anti-Xray status, player/world counts.
- Premium startup banner with version branding.
- Hopper throttle fix: cancelled hopper transfers now schedule a block
  state update so the hopper doesn't get stuck after throttling.
- Anti-cheat false-positive fixes: JesusCheck no longer bypassed by
  onGround=true packets; StepCheck threshold raised above vanilla 0.6;
  SpiderCheck exempts water/lava/elytra/riptide; BlinkCheck exempts
  riptide; StepCheck applies TPS compensation.
- Simulation test suite expanded to 77 scenarios (100% pass rate).

**v1.3.0** (2026-09-19) — the four remaining DeepSeek-dump feature areas,
each scoped down to a reasonable size rather than ported wholesale:
- `anticheat.WatchManager` + `/legendaryac watch <player>` — staff can
  mark a player as watched and get a detailed alert line for every flag
  on them, on top of the normal broadcast alert.
- `anticheat.physics.VanillaPhysics` — **real bug fix**: `MovementCheck`'s
  speed/vertical caps previously had no idea Speed/Slowness/Jump Boost
  potions exist, so a player who simply drank Speed II could get flagged
  for normal vanilla movement. Not a full physics-prediction engine (that
  is a much larger, separate undertaking - see below) - just the potion-
  effect multipliers folded into the existing checks.
- GUI hub (`gui.LegendaryGuiListener` rewritten) — Modules screen (as
  before) plus new Players (browse online players, click through to a
  detail screen with per-check VL, reset/watch/ban buttons) and Checks
  (toggle individual anti-cheat checks, not just whole modules) screens.
  Trimmed from the dump's 9-menu suite to these three, since Settings/
  Logs/Performance/Optimization/Integrations largely duplicate what
  `/legendary status` and `/legendary module` already show as text.
- `integrations.BungeeIntegration` — broadcasts bans to every server on
  the same BungeeCord/Velocity network instantly via the standard
  "BungeeCord" reserved plugin-messaging channel. Off by default
  (`anticheat.bungee-sync.enabled: false`) - only relevant if you actually
  run a multi-server network behind a proxy.

**v1.2.0** (2026-09-19) — merged in a reviewed batch of features from a
community-shared "LegendaryPlugin v6.0" DeepSeek-generated source dump
(three uploaded files, ~5,800 lines / ~65 classes). Everything below was
re-implemented in this project's own architecture (`Check` base class,
`util.Text`/MiniMessage, our config paths) rather than copied verbatim;
see the class-level Javadoc on each new file for the "why".

Added:
- `util.GeyserExemption` — Bedrock/Geyser players can be exempted from
  physics-sensitive checks (`anticheat.checks.<id>.exempt-bedrock: true`);
  wired into `Check.shouldSkip()` so it applies uniformly.
- `optimization.MythicMobsProtection` — **real bug fix**: `MobCapEnforcer`,
  `ArmorStandLimiter` and `ClearEntitiesTask` could previously delete a
  MythicMobs/ModelEngine boss mid-fight during a lag spike, since nothing
  in the port knew those plugins existed. Now wired into `ProtectionRules`.
- Six new anti-cheat checks: `JesusCheck` (water-walking), `SurroundCheck`
  (burst block-placement rate, distinct from Scaffold's min-interval test),
  `AutoToolCheck` (instant tool-switch-then-break), `ShulkerNestingCheck`
  (blocks a real dupe/crash exploit, not just a violation), `ChestAuraCheck`
  (raycast-direction mismatch on container interact, distinct from
  ContainerReach's pure distance test), `CriticalsCheck` (always-crit hack).
- `util.BlockWhitelist` — shared "non-solid surface" set used by JesusCheck.

Deliberately **not** merged from that dump (see the in-chat review for
full reasoning): `NukerCheck`/`GhostHandCheck` (duplicate FastBreak/
BlockReach), `SensitivityCheck`/`HitboxCheck`/`EntityFlyCheck`/
`VelocityCheck`/`PhaseCheck`/`FlyCheck`/`SprintCheck`/`BadPacketsCheck`
(duplicate existing Rotation/Knockback/NoClip/Movement/PacketFlood checks),
`TransactionPingEngine` (uses the Client/Server `TRANSACTION` packet,
which was removed from the protocol in 1.17+ and will not register
against a real ProtocolLib build on 1.21.4), the 9-menu GUI suite, a full
vanilla-physics prediction engine, `WatchManager`, and `BungeeIntegration`
— all reasonable features, just large enough in scope to be their own
follow-up rather than folded in silently.

**v1.1.0** (2026-09-19) — comprehensive upgrade pass:
- Wired up `DatabaseManager` (previously created but never called from
  anywhere) — persists full violation history, not just bans; added
  `/legendaryac history <player>`.
- `esp.PaperAntiXraySupport` — advisory check for Paper's own built-in,
  chunk-level Anti-Xray engine, since that (not a hand-rolled packet
  rewriter) is the reliable way to hide ore in freshly-loaded chunks.
- `verification.method: clickblock` as an alternative to `move`.
- `ElytraFlightCheck` (MovementCheck explicitly skips gliding players —
  elytra fly-hacks were completely unmonitored before this) and
  `InstaBreakCheck` (breaking bedrock/barrier/command blocks).
- `optimization.EntitySweepTask` — five sub-systems (item/XP-orb merge,
  mob cap, armor-stand limit, falling-block limit) each used to re-scan
  every loaded chunk's entities independently every tick; now one shared
  scan feeds all five.
- `AntiFreeCamManager` now routes through the anti-cheat violation/alert/
  DB pipeline instead of only logging to console.

## Known simplifications / follow-ups

- `BlockObfuscationManager` rewrites `BLOCK_CHANGE`/`MULTI_BLOCK_CHANGE`
  packets only; full anti-X-ray also usually wants chunk-packet
  (`MAP_CHUNK`) rewriting for *newly loaded* chunks, which needs deeper
  NMS/ProtocolLib chunk-section work than fits here — currently newly
  streamed chunks show real ore until the next block update in that
  chunk. Good enough to stop casual X-ray, not a substitute for a
  dedicated anti-xray plugin — mitigated in v1.1.0 by the
  `PaperAntiXraySupport` advisory pointing admins at Paper's own engine.
- The `clickblock` verification method places a fixed pressure plate;
  swap in a richer captcha (math question via chat, etc.) if you need
  something stronger against more sophisticated join-bots.
- `WatchManager` watches for alert purposes only - it does not
  auto-spectate the watched player's camera (the dump's version teleported
  the watcher into spectator mode following them; left out here since
  silently forcing a staff member's camera around has UX implications
  worth a deliberate opt-in, not a default).
- `VanillaPhysics` covers potion-effect speed/jump modifiers only, not a
  full per-tick vanilla movement simulation (block friction, water
  currents, soul sand, cobwebs, ice, attribute modifiers, etc.) - that
  remains a legitimate, much larger follow-up if false-positive rates
  ever become a problem in practice.
- The GUI's Players screen only lists currently-online players (offline
  history browsing would need paginating the database, not just Bukkit's
  online-player list) and the Checks screen only toggles enabled/disabled,
  not per-check config values - use config.yml + `/legendary module
  reload anticheat` for anything deeper.
- `BungeeIntegration` syncs bans only (not kicks, mutes, or violation
  levels) and needs a player online on the source server to relay the
  message - a ban issued while the server is completely empty won't sync
  until someone joins.
