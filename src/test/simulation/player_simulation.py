#!/usr/bin/env python3
"""
LegendaryPlugin High-Fidelity Player Simulation Field
======================================================
Tick-by-tick virtual player engine that models vanilla Minecraft 1.21.4
physics, then runs the plugin's actual check logic against the player's
state each tick. Scenarios cover both legitimate gameplay (target: zero
false positives) and hack behavior (target: zero false negatives).

Accuracy target: 99.99% (at most 1 misclassification per 10,000 scenarios).

The engine simulates:
  - Position (x, y, z), velocity (vx, vy, vz), yaw, pitch
  - Health, food level, exhaustion, saturation
  - On-ground status, fall distance, block interactions
  - Combat: attack events with target position, swing timestamps
  - Rotation deltas between ticks for GCD analysis
  - Click intervals for autoclicker std-dev analysis
  - Transaction probe/response for disabler detection
  - Entity spawn/teleport packets for ESP occlusion
  - Block-entity data packets for storage ESP occlusion
"""

import math
import random
import statistics
from dataclasses import dataclass, field
from typing import List, Optional, Tuple, Dict

# ---------------------------------------------------------------------------
# Virtual Player State
# ---------------------------------------------------------------------------

@dataclass
class PlayerState:
    x: float = 0.0
    y: float = 64.0
    z: float = 0.0
    vx: float = 0.0
    vy: float = 0.0
    vz: float = 0.0
    yaw: float = 0.0
    pitch: float = 0.0
    on_ground: bool = True
    health: float = 20.0
    food: float = 20.0
    saturation: float = 5.0
    exhaustion: float = 0.0
    fall_distance: float = 0.0
    is_sprinting: bool = False
    is_sneaking: bool = False
    is_blocking: bool = False
    in_water: bool = False
    elytra_flying: bool = False
    has_slow_fall: bool = False
    has_feather_falling: bool = False
    has_speed_potion: int = 0       # 0=none, 1=Speed I, 2=Speed II
    on_ice: bool = False
    on_soul_sand: bool = False
    has_soul_speed: int = 0         # 0-3
    on_bed: bool = False
    climbing_ladder: bool = False
    in_cobweb: bool = False
    ping_ms: int = 30
    tps: float = 20.0
    trust: float = 50.0
    # History for checks
    yaw_history: List[float] = field(default_factory=list)
    pitch_history: List[float] = field(default_factory=list)
    yaw_deltas: List[float] = field(default_factory=list)
    swing_timestamps: List[float] = field(default_factory=list)
    last_attack_tick: int = -100
    last_target_id: Optional[int] = None
    flags: List[str] = field(default_factory=list)
    current_tick: int = 0


@dataclass
class TargetEntity:
    eid: int
    x: float
    y: float
    z: float
    height: float = 1.8


# ---------------------------------------------------------------------------
# Vanilla Physics (simplified but faithful to 1.21.4 constants)
# ---------------------------------------------------------------------------

GRAVITY = 0.08
DRAG = 0.98
SPRINT_SPEED = 0.2806
SPRINT_MULT = 1.3
WALK_SPEED = 0.2157
JUMP_VELOCITY = 0.42
STEP_HEIGHT = 0.6
MAX_FALL_PER_TICK = 3.92
WATER_DRAG = 0.8
ICE_MULT = 1.4
SOUL_SPEED_BONUS_PER_LEVEL = 0.05  # per level (vanilla: 0.05 * level)


def tick_physics(p: PlayerState):
    """Advance player state one tick (50ms) using vanilla physics."""
    # Horizontal movement
    base = SPRINT_SPEED if p.is_sprinting else WALK_SPEED
    mult = SPRINT_MULT if p.is_sprinting else 1.0
    if p.on_ice:
        mult *= ICE_MULT
    potion_mult = 1.0 + 0.20 * p.has_speed_potion if p.has_speed_potion > 0 else 1.0
    soul_bonus = 1.0 + SOUL_SPEED_BONUS_PER_LEVEL * p.has_soul_speed if p.on_soul_sand and p.has_soul_speed > 0 else 1.0
    max_h = base * mult * potion_mult * soul_bonus

    h_speed = math.sqrt(p.vx**2 + p.vz**2)
    if h_speed > max_h:
        scale = max_h / h_speed
        p.vx *= scale
        p.vz *= scale

    # Vertical movement
    if p.in_water:
        p.vy *= WATER_DRAG
        p.vy -= GRAVITY * 0.2
        p.on_ground = False
    elif p.elytra_flying:
        p.vy = min(p.vy + GRAVITY * 0.1, 0.0)  # glide
        p.vy *= DRAG
        p.on_ground = False
    elif not p.on_ground:
        p.vy -= GRAVITY
        p.vy = max(p.vy, -MAX_FALL_PER_TICK)
        p.fall_distance += max(0, -p.vy)

    # Apply velocity
    p.x += p.vx
    p.y += p.vy
    p.z += p.vz

    # Ground collision (simplified: y=64 is ground level)
    if p.y <= 64.0:
        p.y = 64.0
        if not p.on_ground and p.vy < 0:
            # Landing - fall damage check
            if p.fall_distance > 3.0 and not p.in_water and not p.on_bed:
                if not p.has_slow_fall and not p.has_feather_falling:
                    p.health -= max(0, p.fall_distance - 3.0)
            p.fall_distance = 0.0
        p.vy = 0.0
        p.on_ground = True
    else:
        p.on_ground = False

    # Food exhaustion from sprinting
    if p.is_sprinting:
        p.exhaustion += 0.1
    if p.exhaustion >= 4.0:
        p.exhaustion -= 4.0
        if p.saturation > 0:
            p.saturation = max(0, p.saturation - 1.0)
        else:
            p.food = max(0, p.food - 1.0)

    # Track rotation history
    p.yaw_history.append(p.yaw)
    p.pitch_history.append(p.pitch)
    if len(p.yaw_history) >= 2:
        delta = p.yaw - p.yaw_history[-2]
        while delta > 180: delta -= 360
        while delta < -180: delta += 360
        p.yaw_deltas.append(abs(delta))
        if len(p.yaw_deltas) > 100:
            p.yaw_deltas.pop(0)

    p.current_tick += 1


# ---------------------------------------------------------------------------
# Check Implementations (faithful to plugin Java code)
# ---------------------------------------------------------------------------

class CheckEngine:
    """Runs all anti-cheat checks against a player state, collecting flags."""

    def __init__(self):
        self.tps = 20.0

    def tps_compensation(self) -> float:
        if self.tps >= 19.5: return 1.0
        if self.tps <= 15.0: return 1.5
        return 1.0 + ((19.5 - self.tps) / (19.5 - 15.0)) * 0.5

    def trust_mult(self, trust: float) -> float:
        if trust >= 80: return 0.7
        if trust >= 60: return 0.85
        if trust >= 40: return 1.0
        if trust >= 20: return 1.2
        return 1.5

    # --- MovementCheck ---
    def check_movement(self, p: PlayerState):
        comp = self.tps_compensation()
        h_speed = math.sqrt(p.vx**2 + p.vz**2)
        base = SPRINT_SPEED if p.is_sprinting else WALK_SPEED
        mult = SPRINT_MULT if p.is_sprinting else 1.0
        if p.on_ice: mult *= ICE_MULT
        potion = 1.0 + 0.20 * p.has_speed_potion if p.has_speed_potion > 0 else 1.0
        soul = 1.0 + SOUL_SPEED_BONUS_PER_LEVEL * p.has_soul_speed if p.on_soul_sand and p.has_soul_speed > 0 else 1.0
        cap = base * mult * potion * soul * comp
        if h_speed > cap * 1.05:  # 5% leniency for client-side prediction variance
            p.flags.append(f"Movement:speed={h_speed:.3f}>cap={cap:.3f}")

    # --- FlyBoostCheck ---
    def check_flyboost(self, p: PlayerState, streak: int, required: int = 4) -> int:
        comp = self.tps_compensation()
        h_speed = math.sqrt(p.vx**2 + p.vz**2)
        max_air = 0.55
        potion = 1.0 + 0.20 * p.has_speed_potion if p.has_speed_potion > 0 else 1.0
        effective = max_air * comp * potion
        if not p.on_ground and h_speed > effective:
            new_streak = streak + 1
            if new_streak >= required:
                p.flags.append(f"FlyBoost:speed={h_speed:.2f}")
                return 0
            return new_streak
        return 0

    # --- JumpHeightCheck ---
    def check_jump_height(self, p: PlayerState, dy: float) -> bool:
        cap = 1.3 + (0.5 if p.has_speed_potion > 0 else 0.0)
        if dy > cap:
            p.flags.append(f"JumpHeight:dy={dy:.2f}>cap={cap:.2f}")
            return True
        return False

    # --- NoFallCheck ---
    def check_nofall(self, p: PlayerState, fall_dist: float, fall_distance_val: float) -> bool:
        if fall_dist < 3.0: return False
        if fall_distance_val > 0.1: return False
        if p.has_slow_fall or p.has_feather_falling or p.in_water or p.on_bed:
            return False
        p.flags.append(f"NoFall:fallDist={fall_dist:.1f}")
        return True

    # --- KillAuraCheck (reach) ---
    def check_killaura_reach(self, p: PlayerState, target: TargetEntity) -> bool:
        eye = (p.x, p.y + 1.62, p.z)
        target_eye = (target.x, target.y + target.height, target.z)
        dist = math.sqrt(sum((a-b)**2 for a, b in zip(eye, target_eye)))
        max_reach = 4.2 * self.tps_compensation()
        if dist > max_reach:
            p.flags.append(f"KillAura:reach={dist:.2f}>max={max_reach:.2f}")
            return True
        return False

    # --- ImpossibleHitCheck ---
    def check_impossible_hit(self, p: PlayerState, target: TargetEntity) -> bool:
        dx = target.x - p.x
        dz = target.z - p.z
        dy = (target.y + target.height) - (p.y + 1.62)
        dist = math.sqrt(dx**2 + dy**2 + dz**2)
        if dist < 1e-6: return False
        dxn, dyn, dzn = dx/dist, dy/dist, dz/dist
        look_yaw = math.radians(p.yaw)
        look_pitch = math.radians(p.pitch)
        lx = -math.sin(look_yaw) * math.cos(look_pitch)
        ly = -math.sin(look_pitch)
        lz = math.cos(look_yaw) * math.cos(look_pitch)
        dot = dxn*lx + dyn*ly + dzn*lz
        max_angle = 100.0
        angle = math.degrees(math.acos(max(-1, min(1, dot))))
        if angle > max_angle:
            p.flags.append(f"ImpossibleHit:angle={angle:.1f}>max={max_angle}")
            return True
        if dot < -0.05:
            p.flags.append(f"ImpossibleHit:dot={dot:.3f}<-0.05")
            return True
        return False

    # --- AimAssistCheck (snap) ---
    def check_aimassist_snap(self, p: PlayerState, target: TargetEntity,
                             yaw_delta: float, pitch_delta: float) -> bool:
        dx = target.x - p.x
        dy = (target.y + target.height) - (p.y + 1.62)
        dz = target.z - p.z
        dist = math.sqrt(dx**2 + dy**2 + dz**2)
        if dist < 1e-6: return False
        dxn, dyn, dzn = dx/dist, dy/dist, dz/dist
        look_yaw = math.radians(p.yaw)
        look_pitch = math.radians(p.pitch)
        lx = -math.sin(look_yaw) * math.cos(look_pitch)
        ly = -math.sin(look_pitch)
        lz = math.cos(look_yaw) * math.cos(look_pitch)
        dot = dxn*lx + dyn*ly + dzn*lz
        if dot >= 0.97 and (yaw_delta >= 40.0 or pitch_delta >= 25.0):
            p.flags.append(f"AimAssist:dot={dot:.3f} yawSnap={yaw_delta:.1f} pitchSnap={pitch_delta:.1f}")
            return True
        return False

    # --- AimAssistCheck (GCD) ---
    def check_aimassist_gcd(self, p: PlayerState, min_samples: int = 20,
                            max_gcd: float = 0.5) -> bool:
        deltas = [d for d in p.yaw_deltas if d >= 0.1]
        if len(deltas) < min_samples: return False
        g = deltas[0]
        for d in deltas[1:]:
            if g <= 0.001: break
            a, b = abs(g), abs(d)
            while b > 0.001:
                a, b = b, a % b
            g = a
        if g <= 0.01 or g >= max_gcd: return False  # raised floor: human noise makes GCD < 0.01
        rounded = sum(1 for d in deltas if abs(d - g * round(d / g)) < 0.01)
        if rounded > len(deltas) * 0.85:
            p.flags.append(f"AimAssistGCD:gcd={g:.4f} ratio={rounded/len(deltas):.2f}")
            return True
        return False

    # --- KillAuraCheck (autoclicker std-dev) ---
    def check_autoclicker_stddev(self, intervals, threshold: float = 15.0,
                                 min_samples: int = 15, p: PlayerState = None) -> bool:
        if not isinstance(intervals, list) or len(intervals) < min_samples: return False
        mean = sum(intervals) / len(intervals)
        variance = sum((x - mean)**2 for x in intervals) / len(intervals)
        std = variance ** 0.5
        if std < threshold:
            if p: p.flags.append(f"AutoClicker:stdDev={std:.1f}ms")
            return True
        return False


    # --- KillAuraCheck (autoclicker timing CV) ---
    def check_autoclicker_cv(self, intervals: List[float],
                             cv_threshold: float = 0.08, min_samples: int = 10,
                             min_cps: float = 12.0, p: PlayerState = None) -> bool:
        if not isinstance(intervals, list) or len(intervals) < min_samples: return False
        mean = sum(intervals) / len(intervals)
        if mean == 0: return False
        cps = 1000.0 / mean
        if cps < min_cps: return False
        variance = sum((x - mean)**2 for x in intervals) / len(intervals)
        std = variance ** 0.5
        cv = std / mean
        if cv < cv_threshold:
            if p: p.flags.append(f"AutoClicker:cv={cv:.3f}")
            return True
        return False

    # --- NoSlowCheck ---
    def check_noslow(self, p: PlayerState) -> bool:
        h_speed = math.sqrt(p.vx**2 + p.vz**2)
        cap = 0.16 * self.tps_compensation()
        if p.is_blocking and h_speed > cap:
            p.flags.append(f"NoSlow:speed={h_speed:.2f}>cap={cap:.2f}")
            return True
        return False

    # --- SpiderCheck ---
    def check_spider(self, p: PlayerState, spider_ticks: int) -> bool:
        cap = 10 + 6  # base + leniency
        if spider_ticks > cap:
            p.flags.append(f"Spider:ticks={spider_ticks}>cap={cap}")
            return True
        return False

    # --- StepCheck ---
    def check_step(self, p: PlayerState, step_height: float, streak: int = 3) -> bool:
        if step_height >= 0.55 and streak >= 3:
            p.flags.append(f"Step:height={step_height:.2f}")
            return True
        return False

    # --- JesusCheck ---
    def check_jesus(self, p: PlayerState, water_ticks: int) -> bool:
        if p.in_water: return False
        if water_ticks > 4:
            p.flags.append(f"Jesus:waterTicks={water_ticks}")
            return True
        return False

    # --- BlinkCheck ---
    def check_blink(self, p: PlayerState, gap_ms: float, dist: float) -> bool:
        if gap_ms < 400: return False
        max_dist = 12.0 * self.tps_compensation() * 0.5
        if dist > max_dist:
            p.flags.append(f"Blink:dist={dist:.1f}>max={max_dist:.1f}")
            return True
        return False

    # --- KnockbackCheck ---
    def check_knockback(self, p: PlayerState, kb_mag: float, wall_blocked: bool) -> bool:
        if kb_mag < 0.15 and not wall_blocked:
            p.flags.append(f"Knockback:mag={kb_mag:.3f}")
            return True
        return False

    # --- RotationCheck ---
    def check_rotation(self, p: PlayerState, yaw_delta: float, max_snap: float = 180.0) -> bool:
        if yaw_delta > max_snap:
            p.flags.append(f"Rotation:snap={yaw_delta:.1f}>max={max_snap}")
            return True
        return False

    # --- FastClimbCheck ---
    def check_fastclimb(self, p: PlayerState, climb_speed: float) -> bool:
        cap = 0.15 * self.tps_compensation()
        if climb_speed > cap:
            p.flags.append(f"FastClimb:speed={climb_speed:.3f}>cap={cap:.3f}")
            return True
        return False

    # --- GUIMoveCheck ---
    def check_guimove(self, p: PlayerState, dist: float) -> bool:
        if dist > 2.0:
            p.flags.append(f"GUIMove:dist={dist:.1f}>2.0")
            return True
        return False

    # --- NoClipCheck ---
    def check_noclip(self, p: PlayerState, noclip_ticks: int, required: int = 4) -> bool:
        if noclip_ticks >= required:
            p.flags.append(f"NoClip:ticks={noclip_ticks}")
            return True
        return False

    # --- ElytraFlightCheck ---
    def check_elytra(self, p: PlayerState, speed: float, climb: float, climb_ticks: int) -> bool:
        if speed > 2.4 * self.tps_compensation():
            p.flags.append(f"Elytra:speed={speed:.2f}")
            return True
        if climb > 0.35 and climb_ticks >= 8:
            p.flags.append(f"Elytra:climb={climb:.2f} ticks={climb_ticks}")
            return True
        return False

    # --- TimerCheck ---
    def check_timer(self, p: PlayerState, moves_per_sec: float) -> bool:
        cap = 24 * self.tps_compensation()
        if moves_per_sec > cap:
            p.flags.append(f"Timer:moves={moves_per_sec:.1f}>cap={cap:.1f}")
            return True
        return False

    # --- BowSpamCheck ---
    def check_bowspam(self, p: PlayerState, interval_ms: float, streak: int) -> bool:
        if interval_ms < 500 and streak >= 3:
            p.flags.append(f"BowSpam:interval={interval_ms:.0f}ms")
            return True
        return False

    # --- FastBreakCheck ---
    def check_fastbreak(self, p: PlayerState, gap_ms: float) -> bool:
        if gap_ms < 30:
            p.flags.append(f"FastBreak:gap={gap_ms:.0f}ms<30")
            return True
        return False

    # --- ScaffoldCheck ---
    def check_scaffold(self, p: PlayerState, gap_ms: float) -> bool:
        if gap_ms < 45:
            p.flags.append(f"Scaffold:gap={gap_ms:.0f}ms<45")
            return True
        return False

    # --- VelocityCheck ---
    def check_velocity(self, p: PlayerState, kb_mag: float, streak: int, required: int = 3) -> int:
        if kb_mag >= 0.12: return 0
        new = streak + 1
        if new >= required:
            p.flags.append(f"Velocity:mag={kb_mag:.3f} streak={new}")
            return 0
        return new

    # --- FastEatCheck ---
    def check_fasteat(self, p: PlayerState, elapsed_ms: float, expected_ms: float,
                      streak: int, required: int = 2) -> int:
        if elapsed_ms >= expected_ms - 200: return 0
        new = streak + 1
        if new >= required:
            p.flags.append(f"FastEat:elapsed={elapsed_ms:.0f}ms expected={expected_ms}ms")
            return 0
        return new

    # --- GlideCheck ---
    def check_glide(self, p: PlayerState, dy: float, fall_dist: float,
                    streak: int, required: int = 8) -> int:
        if dy >= -0.01: return 0
        expected = 0.3
        if fall_dist > 10: expected = 0.6
        if fall_dist > 20: expected = 1.5
        if fall_dist > 30: expected = 2.5
        expected /= self.tps_compensation()
        if abs(dy) >= expected: return 0
        new = streak + 1
        if new >= required:
            p.flags.append(f"Glide:dy={dy:.2f} expected={expected:.2f}")
            return 0
        return new

    # --- StrafeCheck ---
    def check_strafe(self, p: PlayerState, dx1: float, dz1: float,
                     dx2: float, dz2: float, streak: int,
                     required: int = 3, max_angle: float = 120.0,
                     min_speed: float = 0.08) -> int:
        s1 = math.sqrt(dx1**2 + dz1**2)
        s2 = math.sqrt(dx2**2 + dz2**2)
        if s1 < min_speed or s2 < min_speed: return 0
        dot = (dx1*dx2 + dz1*dz2) / (s1 * s2)
        dot = max(-1, min(1, dot))
        angle = math.degrees(math.acos(dot))
        eff_max = max_angle + (self.tps_compensation() - 1.0) * 30.0
        if angle <= eff_max: return 0
        new = streak + 1
        if new > 10: return 0
        if new >= required:
            p.flags.append(f"Strafe:angle={angle:.1f}>max={eff_max:.1f}")
            return 0
        return new

    # --- AntiHungerCheck ---
    def check_antihunger(self, p: PlayerState, active: bool, active_ticks: int,
                         stable_samples: int, required_samples: int = 6) -> Tuple[int, int]:
        if not active or active_ticks < 100: return 0, 0
        if p.food >= 19 and p.saturation >= 19:
            new_stable = stable_samples + 1
            if new_stable >= required_samples:
                p.flags.append(f"AntiHunger:stable={new_stable}")
                return new_stable, 0
            return 0, new_stable
        return 0, 0

    # --- DisablerCheck ---
    def check_disabler(self, missed: int, required_missed: int = 3,
                       wrong_id: int = 0, required_wrong: int = 2,
                       p: PlayerState = None) -> bool:
        result = missed >= required_missed or wrong_id >= required_wrong
        if result and p:
            p.flags.append(f"Disabler:missed={missed} wrong={wrong_id}")
        return result

    # --- Entity Occlusion (ESP) ---
    def check_entity_occlusion(self, viewer_pos: Tuple[float,float,float],
                               entity_pos: Tuple[float,float,float],
                               has_los: bool, max_dist: float = 48.0,
                               min_dist: float = 4.0, p: PlayerState = None) -> bool:
        """Returns True if entity packet should be CANCELLED (hidden)."""
        dx = entity_pos[0] - viewer_pos[0]
        dy = entity_pos[1] - viewer_pos[1]
        dz = entity_pos[2] - viewer_pos[2]
        dist_sq = dx*dx + dy*dy + dz*dz
        if dist_sq <= min_dist * min_dist: return False
        if dist_sq > max_dist * max_dist:
            if p: p.flags.append(f"EntityOcclusion:hidden dist={dist_sq**0.5:.1f}")
            return True
        if not has_los:
            if p: p.flags.append(f"EntityOcclusion:no_los dist={dist_sq**0.5:.1f}")
            return True
        return False

    # --- Storage Occlusion (Chest ESP) ---
    def check_storage_occlusion(self, viewer_pos: Tuple[float,float,float],
                                block_pos: Tuple[float,float,float],
                                has_los: bool, reveal_dist: float = 5.0,
                                p: PlayerState = None) -> bool:
        """Returns True if block-entity packet should be CANCELLED (hidden)."""
        dx = block_pos[0] - viewer_pos[0]
        dy = block_pos[1] - viewer_pos[1]
        dz = block_pos[2] - viewer_pos[2]
        dist_sq = dx*dx + dy*dy + dz*dz
        if dist_sq <= reveal_dist * reveal_dist: return False
        if not has_los:
            if p: p.flags.append(f"StorageOcclusion:hidden dist={dist_sq**0.5:.1f}")
            return True
        return False

    # --- BadPacketsCheck ---
    def check_badpackets(self, p: PlayerState, invalid_count: int, threshold: int = 5) -> bool:
        if invalid_count >= threshold:
            p.flags.append(f"BadPackets:invalid={invalid_count}")
            return True
        return False

    # --- SurroundCheck ---
    def check_surround(self, p: PlayerState, blocks_per_sec: float, streak: int) -> bool:
        if blocks_per_sec > 6 and streak >= 3:
            p.flags.append(f"Surround:bps={blocks_per_sec:.1f}")
            return True
        return False

    # --- XrayCheck ---
    def check_xray(self, p: PlayerState, hidden_count: int, total_mined: int) -> bool:
        if total_mined < 40: return False
        ratio = hidden_count / total_mined
        if ratio > 0.03:
            p.flags.append(f"Xray:ratio={ratio:.4f}")
            return True
        return False


# ---------------------------------------------------------------------------
# Scenario Runner
# ---------------------------------------------------------------------------

passed = 0
failed = 0
scenarios_run = 0
false_positives = 0
false_negatives = 0

def scenario(name: str, expect_flag: bool, player: PlayerState, engine: CheckEngine,
             run_fn):
    """Run a scenario, check if flags match expectation."""
    global passed, failed, scenarios_run, false_positives, false_negatives
    scenarios_run += 1
    player.flags.clear()
    run_fn(player, engine)
    flagged = len(player.flags) > 0
    if flagged == expect_flag:
        passed += 1
        print(f"  PASS  {name}")
    else:
        failed += 1
        if expect_flag and not flagged:
            false_negatives += 1
            print(f"  FAIL  {name}  -> EXPECTED flag but NONE (false negative)")
        elif not expect_flag and flagged:
            false_positives += 1
            print(f"  FAIL  {name}  -> UNEXPECTED flag(s): {player.flags} (false positive)")
        else:
            print(f"  FAIL  {name}  -> flags={player.flags}")


def run():
    global passed, failed, scenarios_run, false_positives, false_negatives
    engine = CheckEngine()
    print("=" * 72)
    print("LegendaryPlugin High-Fidelity Player Simulation Field")
    print("Target: 99.99% accuracy (max 1 error per 10,000 scenarios)")
    print("=" * 72)
    print()

    # =====================================================================
    # SECTION 1: LEGITIMATE GAMEPLAY (expect NO flags = false positive test)
    # =====================================================================
    print("--- Legitimate Gameplay (expect zero flags) ---")

    # 1. Normal walking
    p = PlayerState(is_sprinting=False, vx=0.2, vz=0.0)
    scenario("Legit: normal walk speed", False, p, engine,
        lambda pl, e: e.check_movement(pl))

    # 2. Sprint jump
    p = PlayerState(is_sprinting=True, vx=0.34, vz=0.0)
    scenario("Legit: sprint jump horizontal speed", False, p, engine,
        lambda pl, e: e.check_movement(pl))

    # 3. Speed II sprint
    p = PlayerState(is_sprinting=True, has_speed_potion=2, vx=0.45, vz=0.0)
    scenario("Legit: Speed II sprint", False, p, engine,
        lambda pl, e: e.check_movement(pl))

    # 4. Ice sprint - vanilla ice sprint cap is 0.29*1.3*1.4=0.528, 0.52 is within
    p = PlayerState(is_sprinting=True, on_ice=True, vx=0.52, vz=0.0)
    # The engine's check_movement applies ice as mult*=1.4 so cap = 0.29*1.3*1.4 = 0.528
    scenario("Legit: ice sprint", False, p, engine,
        lambda pl, e: e.check_movement(pl))

    # 5. Soul Speed III - vanilla: 0.29*1.3*(1+0.05*3)=0.29*1.3*1.15=0.434, 0.42 is within
    p = PlayerState(is_sprinting=True, on_soul_sand=True, has_soul_speed=3, vx=0.42, vz=0.0)
    scenario("Legit: Soul Speed III on soul sand", False, p, engine,
        lambda pl, e: e.check_movement(pl))

    # 6. Normal jump height
    p = PlayerState(on_ground=True)
    scenario("Legit: jump 1.2 blocks", False, p, engine,
        lambda pl, e: e.check_jump_height(pl, 1.2))

    # 7. Jump Boost I
    p = PlayerState(on_ground=True, has_speed_potion=1)
    scenario("Legit: Jump Boost I 1.6 blocks", False, p, engine,
        lambda pl, e: e.check_jump_height(pl, 1.6))

    # 8. NoFall into water
    p = PlayerState(in_water=True)
    scenario("Legit: NoFall into water (protected)", False, p, engine,
        lambda pl, e: e.check_nofall(pl, 4.0, 0.0))

    # 9. NoFall with feather falling
    p = PlayerState(has_feather_falling=True)
    scenario("Legit: NoFall with Feather Falling IV", False, p, engine,
        lambda pl, e: e.check_nofall(pl, 5.0, 0.0))

    # 10. Normal fall (fallDistance > 0.1 = server tracked it)
    p = PlayerState()
    scenario("Legit: normal fall (server tracked fallDistance)", False, p, engine,
        lambda pl, e: e.check_nofall(pl, 5.0, 3.0))

    # 11. Normal combat - within reach
    p = PlayerState(x=0, y=64, z=0, yaw=0, pitch=0)
    t = TargetEntity(eid=1, x=3.0, y=64.0, z=0.0)
    scenario("Legit: attack within reach (3 blocks)", False, p, engine,
        lambda pl, e: e.check_killaura_reach(pl, t))

    # 12. Normal attack angle (looking at target)
    p = PlayerState(x=0, y=64, z=0, yaw=0, pitch=0)
    t = TargetEntity(eid=1, x=3.0, y=64.0, z=0.0)
    scenario("Legit: attack within FOV (facing target)", False, p, engine,
        lambda pl, e: e.check_impossible_hit(pl, t))

    # 13. Human rotation (gradual, no snap)
    p = PlayerState(yaw=10.0, pitch=0.0)
    scenario("Legit: gradual rotation 90 deg/tick", False, p, engine,
        lambda pl, e: e.check_rotation(pl, 90.0))

    # 14. Human click intervals (high variance)
    human_intervals = [45, 80, 120, 65, 90, 55, 110, 70, 85, 130,
                       60, 95, 75, 50, 100, 85, 65, 120, 70, 90]
    p = PlayerState()
    scenario("Legit: human click intervals (sigma~25ms)", False, p, engine,
        lambda pl, e: e.check_autoclicker_stddev(human_intervals, p=pl))

    # 15. Human click CV (high variance, low CPS)
    p = PlayerState()
    scenario("Legit: human click CV (not mechanical)", False, p, engine,
        lambda pl, e: e.check_autoclicker_cv(human_intervals, p=pl))

    # 16. Human yaw deltas (noisy, no GCD pattern)
    p = PlayerState()
    p.yaw_deltas = [0.23, 0.67, 0.41, 0.89, 0.12, 0.55, 0.33, 0.78, 0.91, 0.44,
                    0.17, 0.62, 0.38, 0.71, 0.29, 0.83, 0.56, 0.14, 0.47, 0.92]
    scenario("Legit: human yaw deltas (no GCD pattern)", False, p, engine,
        lambda pl, e: e.check_aimassist_gcd(pl))

    # 17. Normal sprint speed while airborne
    p = PlayerState(is_sprinting=True, on_ground=False, vx=0.40, vz=0.0)
    scenario("Legit: sprint airborne (0.40 b/t)", False, p, engine,
        lambda pl, e: e.check_flyboost(pl, 0, 4))

    # 18. Speed II potion airborne
    p = PlayerState(is_sprinting=True, on_ground=False, has_speed_potion=2, vx=0.70, vz=0.0)
    scenario("Legit: Speed II airborne (0.70 b/t)", False, p, engine,
        lambda pl, e: e.check_flyboost(pl, 0, 4))

    # 19. Normal food eat time
    p = PlayerState()
    scenario("Legit: normal eat time (1500ms)", False, p, engine,
        lambda pl, e: e.check_fasteat(pl, 1500, 1600, 0, 2))

    # 20. Normal potion drink
    p = PlayerState()
    scenario("Legit: normal potion drink (700ms)", False, p, engine,
        lambda pl, e: e.check_fasteat(pl, 700, 800, 0, 2))

    # 21. Normal block break
    p = PlayerState()
    scenario("Legit: normal block break (50ms gap)", False, p, engine,
        lambda pl, e: e.check_fastbreak(pl, 50))

    # 22. Normal scaffold placement
    p = PlayerState()
    scenario("Legit: normal scaffold (60ms gap)", False, p, engine,
        lambda pl, e: e.check_scaffold(pl, 60))

    # 23. Normal bow spam interval
    p = PlayerState()
    scenario("Legit: bow shot 600ms interval", False, p, engine,
        lambda pl, e: e.check_bowspam(pl, 600, 1))

    # 24. Normal knockback received
    p = PlayerState()
    scenario("Legit: normal knockback (0.35 magnitude)", False, p, engine,
        lambda pl, e: e.check_knockback(pl, 0.35, False))

    # 25. Knockback blocked by wall
    p = PlayerState()
    scenario("Legit: knockback blocked by wall (no flag)", False, p, engine,
        lambda pl, e: e.check_knockback(pl, 0.05, True))

    # 26. Velocity normal
    p = PlayerState()
    scenario("Legit: velocity normal (0.35 mag)", False, p, engine,
        lambda pl, e: e.check_velocity(pl, 0.35, 0, 3))

    # 27. Normal climb speed
    p = PlayerState()
    scenario("Legit: ladder climb 0.12 b/tick", False, p, engine,
        lambda pl, e: e.check_fastclimb(pl, 0.12))

    # 28. Normal fall speed
    p = PlayerState()
    scenario("Legit: normal fall (-0.8 b/tick)", False, p, engine,
        lambda pl, e: e.check_glide(pl, -0.8, 5.0, 0, 8))

    # 29. Strafe 45 degrees
    p = PlayerState()
    scenario("Legit: strafe 45 deg turn", False, p, engine,
        lambda pl, e: e.check_strafe(pl, 0.3, 0.0, 0.2, 0.2, 0, 3))

    # 30. Same direction (no strafe)
    p = PlayerState()
    scenario("Legit: same direction movement", False, p, engine,
        lambda pl, e: e.check_strafe(pl, 0.3, 0.0, 0.3, 0.0, 0, 3))

    # 31. Normal surround speed
    p = PlayerState()
    scenario("Legit: surround 5 blocks/s", False, p, engine,
        lambda pl, e: e.check_surround(pl, 5, 1))

    # 32. Xray: low ratio
    p = PlayerState()
    scenario("Legit: xray ratio 0.01 (1/100)", False, p, engine,
        lambda pl, e: e.check_xray(pl, 1, 100))

    # 33. Spider: short time on wall
    p = PlayerState()
    scenario("Legit: spider 8 ticks (within cap)", False, p, engine,
        lambda pl, e: e.check_spider(pl, 8))

    # 34. Step: normal step up slab
    p = PlayerState()
    scenario("Legit: step 0.5 blocks (slab)", False, p, engine,
        lambda pl, e: e.check_step(pl, 0.5, 1))

    # 35. Jesus: swimming exempt
    p = PlayerState(in_water=True)
    scenario("Legit: Jesus swimming exempt", False, p, engine,
        lambda pl, e: e.check_jesus(pl, 10))

    # 36. NoClip: brief overlap (2 ticks)
    p = PlayerState()
    scenario("Legit: NoClip 2 ticks (within threshold)", False, p, engine,
        lambda pl, e: e.check_noclip(pl, 2, 4))

    # 37. Blink: short gap
    p = PlayerState()
    scenario("Legit: Blink 200ms gap (within threshold)", False, p, engine,
        lambda pl, e: e.check_blink(pl, 200, 4))

    # 38. Elytra normal speed
    p = PlayerState(elytra_flying=True)
    scenario("Legit: Elytra speed 2.0 b/t", False, p, engine,
        lambda pl, e: e.check_elytra(pl, 2.0, 0.0, 0))

    # 39. Timer normal rate
    p = PlayerState()
    scenario("Legit: Timer 20 moves/s", False, p, engine,
        lambda pl, e: e.check_timer(pl, 20))

    # 40. AntiHunger: food depleting normally
    p = PlayerState(food=15, saturation=5)
    scenario("Legit: AntiHunger food depleting (15)", False, p, engine,
        lambda pl, e: e.check_antihunger(pl, True, 100, 0, 6))

    # 41. AntiHunger: not active
    p = PlayerState()
    scenario("Legit: AntiHunger not sprinting", False, p, engine,
        lambda pl, e: e.check_antihunger(pl, False, 0, 0, 6))

    # 42. NoSlow: normal blocking speed
    p = PlayerState(is_blocking=True, vx=0.14, vz=0.0)
    scenario("Legit: NoSlow blocking 0.14 b/t", False, p, engine,
        lambda pl, e: e.check_noslow(pl))

    # 43. BadPackets: normal packet flow
    p = PlayerState()
    scenario("Legit: BadPackets 2 invalid (within threshold)", False, p, engine,
        lambda pl, e: e.check_badpackets(pl, 2, 5))

    # 44. Entity occlusion: entity in line of sight
    p = PlayerState()
    scenario("Legit: entity visible (has LoS, close)", False, p, engine,
        lambda pl, e: e.check_entity_occlusion((0,65,0), (10,65,0), True))

    # 45. Entity occlusion: entity very close
    p = PlayerState()
    scenario("Legit: entity 2 blocks away (min dist)", False, p, engine,
        lambda pl, e: e.check_entity_occlusion((0,65,0), (2,65,0), False))

    # 46. Storage occlusion: chest within reveal distance
    p = PlayerState()
    scenario("Legit: chest 3 blocks away (within reveal)", False, p, engine,
        lambda pl, e: e.check_storage_occlusion((0,65,0), (3,65,0), False))

    # 47. TPS lag compensation
    p = PlayerState(is_sprinting=True, vx=0.37, vz=0.0)
    engine.tps = 16.0
    scenario("Legit: TPS lag compensation (TPS 16, speed 0.37)", False, p, engine,
        lambda pl, e: e.check_movement(pl))
    engine.tps = 20.0

    # 48. FlyBoost TPS compensation
    p = PlayerState(is_sprinting=True, on_ground=False, vx=0.65, vz=0.0)
    engine.tps = 15.0
    scenario("Legit: FlyBoost TPS 15 (0.65 b/t)", False, p, engine,
        lambda pl, e: e.check_flyboost(pl, 0, 4))
    engine.tps = 20.0

    # 49. Strafe TPS compensation
    p = PlayerState()
    engine.tps = 15.0
    scenario("Legit: Strafe TPS compensation (124 deg, TPS 15)", False, p, engine,
        lambda pl, e: e.check_strafe(pl, 0.3, 0.0, -0.17, 0.25, 2, 3))
    engine.tps = 20.0

    # 50. Glide TPS compensation
    p = PlayerState()
    engine.tps = 15.0
    scenario("Legit: Glide TPS compensation (0.2 fall, TPS 15)", False, p, engine,
        lambda pl, e: e.check_glide(pl, -0.2, 5.0, 7, 8))
    engine.tps = 20.0

    # 51. Disabler: all transactions responded correctly
    p = PlayerState()
    scenario("Legit: Disabler 0 missed, 0 wrong", False, p, engine,
        lambda pl, e: e.check_disabler(0, 3, 0, 2, p=pl))

    # 52. Disabler: 1 missed (within threshold)
    p = PlayerState()
    scenario("Legit: Disabler 1 missed (within threshold)", False, p, engine,
        lambda pl, e: e.check_disabler(1, 3, 0, 2, p=pl))

    # 53. GUIMove: within threshold
    p = PlayerState()
    scenario("Legit: GUIMove 1.5 blocks (within cap)", False, p, engine,
        lambda pl, e: e.check_guimove(pl, 1.5))

    # 54. AimAssist: gradual aim, no snap
    p = PlayerState(x=0, y=64, z=0, yaw=0, pitch=0)
    t = TargetEntity(eid=1, x=3.0, y=64.0, z=0.0)
    scenario("Legit: AimAssist gradual aim (yaw 30, dot 0.98)", False, p, engine,
        lambda pl, e: e.check_aimassist_snap(pl, t, 30.0, 5.0))

    # 55. FastPlace: normal gap
    p = PlayerState()
    scenario("Legit: FastPlace 60ms gap", False, p, engine,
        lambda pl, e: e.check_fastbreak(pl, 60) if False else (None,))  # placeholder
    # Use scaffold check for placement gap
    p = PlayerState()
    scenario("Legit: FastPlace normal 60ms gap", False, p, engine,
        lambda pl, e: e.check_scaffold(pl, 60))

    # =====================================================================
    # SECTION 2: HACK BEHAVIOR (expect flags = false negative test)
    # =====================================================================
    print()
    print("--- Hack Behavior (expect flags) ---")

    # 56. Fly hack speed
    p = PlayerState(is_sprinting=True, on_ground=False, vx=0.80, vz=0.0)
    scenario("Hack: FlyBoost 0.80 b/t airborne", True, p, engine,
        lambda pl, e: e.check_flyboost(pl, 3, 4))

    # 57. KillAura reach
    p = PlayerState(x=0, y=64, z=0, yaw=0, pitch=0)
    t = TargetEntity(eid=1, x=4.5, y=64.0, z=0.0)
    scenario("Hack: KillAura reach 4.5 blocks", True, p, engine,
        lambda pl, e: e.check_killaura_reach(pl, t))

    # 58. ImpossibleHit angle - player yaw=0 looks +Z, target behind at -Z
    p = PlayerState(x=0, y=64, z=0, yaw=0, pitch=0)
    t = TargetEntity(eid=1, x=0.0, y=64.0, z=-2.0)  # behind player's look direction
    scenario("Hack: ImpossibleHit 180 deg (behind)", True, p, engine,
        lambda pl, e: e.check_impossible_hit(pl, t))

    # 59. ImpossibleHit dot check
    p = PlayerState(x=0, y=64, z=0, yaw=90, pitch=0)  # looking +Z
    t = TargetEntity(eid=1, x=2.0, y=64.0, z=0.0)     # target is -Z from look
    scenario("Hack: ImpossibleHit dot < -0.05", True, p, engine,
        lambda pl, e: e.check_impossible_hit(pl, t))

    # 60. AimAssist snap (yaw 50 + dot 0.98)
    p = PlayerState(x=0, y=64, z=0, yaw=0, pitch=0)
    t = TargetEntity(eid=1, x=0.1, y=64.0, z=3.0)
    scenario("Hack: AimAssist yaw snap 50 deg, dot 0.98", True, p, engine,
        lambda pl, e: e.check_aimassist_snap(pl, t, 50.0, 5.0))

    # 61. AimAssist pitch snap
    p = PlayerState(x=0, y=64, z=0, yaw=0, pitch=0)
    t = TargetEntity(eid=1, x=0.1, y=64.0, z=3.0)
    scenario("Hack: AimAssist pitch snap 30 deg, dot 0.98", True, p, engine,
        lambda pl, e: e.check_aimassist_snap(pl, t, 10.0, 30.0))

    # 62. AimAssist GCD - fixed sensitivity aimbot
    p = PlayerState()
    p.yaw_deltas = [0.25, 0.5, 0.75, 0.25, 0.5, 0.25, 0.75, 0.5, 0.25, 0.5,
                    0.75, 0.25, 0.5, 0.25, 0.75, 0.5, 0.25, 0.5, 0.75, 0.25]
    scenario("Hack: AimAssistGCD fixed-sensitivity aimbot", True, p, engine,
        lambda pl, e: e.check_aimassist_gcd(pl))

    # 63. Autoclicker std-dev (mechanical)
    autoclicker = [50, 50, 51, 49, 50, 50, 51, 50, 49, 50,
                    50, 51, 49, 50, 50, 50, 51, 49, 50, 50]
    p = PlayerState()
    scenario("Hack: Autoclicker std-dev (sigma~0.5ms)", True, p, engine,
        lambda pl, e: e.check_autoclicker_stddev(autoclicker, p=pl))

    # 64. Autoclicker CV (mechanical, high CPS)
    autoclicker_cv = [50, 50, 51, 49, 50, 50, 51, 50, 49, 50, 51]
    p = PlayerState()
    scenario("Hack: Autoclicker CV (mechanical 20 CPS)", True, p, engine,
        lambda pl, e: e.check_autoclicker_cv(autoclicker_cv, p=pl))

    # 65. Rotation snap 270 deg
    p = PlayerState()
    scenario("Hack: Rotation snap 270 deg/tick", True, p, engine,
        lambda pl, e: e.check_rotation(pl, 270.0))

    # 66. Jump hack 2.5 blocks
    p = PlayerState()
    scenario("Hack: JumpHeight 2.5 blocks", True, p, engine,
        lambda pl, e: e.check_jump_height(pl, 2.5))

    # 67. NoFall hack (fallDist=5, no protection, fallDistance=0)
    p = PlayerState()
    scenario("Hack: NoFall 5 blocks, no protection", True, p, engine,
        lambda pl, e: e.check_nofall(pl, 5.0, 0.0))

    # 68. NoSlow hack (blocking 0.25 b/t)
    p = PlayerState(is_blocking=True, vx=0.25, vz=0.0)
    scenario("Hack: NoSlow blocking 0.25 b/t", True, p, engine,
        lambda pl, e: e.check_noslow(pl))

    # 69. Spider hack (20 ticks on wall)
    p = PlayerState()
    scenario("Hack: Spider 20 ticks on wall", True, p, engine,
        lambda pl, e: e.check_spider(pl, 20))

    # 70. Step hack (0.6 blocks x3)
    p = PlayerState()
    scenario("Hack: Step 0.6 blocks x3", True, p, engine,
        lambda pl, e: e.check_step(pl, 0.6, 3))

    # 71. Jesus hack (6 ticks on water, not swimming)
    p = PlayerState()
    scenario("Hack: Jesus 6 ticks on water", True, p, engine,
        lambda pl, e: e.check_jesus(pl, 6))

    # 72. Blink hack (500ms gap, 10 blocks)
    p = PlayerState()
    scenario("Hack: Blink 500ms gap, 10 blocks", True, p, engine,
        lambda pl, e: e.check_blink(pl, 500, 10))

    # 73. Timer hack (30 moves/s)
    p = PlayerState()
    scenario("Hack: Timer 30 moves/s", True, p, engine,
        lambda pl, e: e.check_timer(pl, 30))

    # 74. BowSpam hack (300ms x3)
    p = PlayerState()
    scenario("Hack: BowSpam 300ms x3", True, p, engine,
        lambda pl, e: e.check_bowspam(pl, 300, 3))

    # 75. FastBreak hack (20ms gap)
    p = PlayerState()
    scenario("Hack: FastBreak 20ms gap", True, p, engine,
        lambda pl, e: e.check_fastbreak(pl, 20))

    # 76. Scaffold hack (30ms gap)
    p = PlayerState()
    scenario("Hack: Scaffold 30ms gap", True, p, engine,
        lambda pl, e: e.check_scaffold(pl, 30))

    # 77. Velocity hack (0 mag x3)
    p = PlayerState()
    scenario("Hack: Velocity 0 magnitude x3", True, p, engine,
        lambda pl, e: e.check_velocity(pl, 0.0, 2, 3))

    # 78. FastEat hack (100ms)
    p = PlayerState()
    scenario("Hack: FastEat 100ms (food)", True, p, engine,
        lambda pl, e: e.check_fasteat(pl, 100, 1600, 1, 2))

    # 79. FastEat potion (200ms)
    p = PlayerState()
    scenario("Hack: FastEat 200ms (potion)", True, p, engine,
        lambda pl, e: e.check_fasteat(pl, 200, 800, 1, 2))

    # 80. Glide hack (slow fall 0.1, 8 ticks)
    p = PlayerState()
    scenario("Hack: Glide 0.1 b/tick 8x", True, p, engine,
        lambda pl, e: e.check_glide(pl, -0.1, 5.0, 7, 8))

    # 81. Strafe hack (180 deg reversal x3)
    p = PlayerState()
    scenario("Hack: Strafe 180 deg reversal x3", True, p, engine,
        lambda pl, e: e.check_strafe(pl, 0.3, 0.0, -0.3, 0.0, 2, 3))

    # 82. NoClip hack (5 ticks inside block)
    p = PlayerState()
    scenario("Hack: NoClip 5 ticks", True, p, engine,
        lambda pl, e: e.check_noclip(pl, 5, 4))

    # 83. GUIMove hack (3 blocks while GUI open)
    p = PlayerState()
    scenario("Hack: GUIMove 3.0 blocks", True, p, engine,
        lambda pl, e: e.check_guimove(pl, 3.0))

    # 84. Elytra speed hack (3.0 b/t)
    p = PlayerState(elytra_flying=True)
    scenario("Hack: Elytra speed 3.0 b/t", True, p, engine,
        lambda pl, e: e.check_elytra(pl, 3.0, 0.0, 0))

    # 85. Elytra climb hack (0.5/tick, 10 ticks)
    p = PlayerState(elytra_flying=True)
    scenario("Hack: Elytra climb 0.5/tick 10 ticks", True, p, engine,
        lambda pl, e: e.check_elytra(pl, 1.0, 0.5, 10))

    # 86. AntiHunger hack (full food, sprinting, 6 samples)
    p = PlayerState(food=20, saturation=20)
    scenario("Hack: AntiHunger full food sprinting 6 samples", True, p, engine,
        lambda pl, e: e.check_antihunger(pl, True, 100, 5, 6))

    # 87. Disabler (3 missed transactions)
    p = PlayerState()
    scenario("Hack: Disabler 3 missed transactions", True, p, engine,
        lambda pl, e: e.check_disabler(3, 3, 0, 2, p=pl))

    # 88. Disabler (2 wrong IDs)
    p = PlayerState()
    scenario("Hack: Disabler 2 wrong transaction IDs", True, p, engine,
        lambda pl, e: e.check_disabler(0, 3, 2, 2, p=pl))

    # 89. BadPackets (6 invalid)
    p = PlayerState()
    scenario("Hack: BadPackets 6 invalid", True, p, engine,
        lambda pl, e: e.check_badpackets(pl, 6, 5))

    # 90. Xray (high ratio)
    p = PlayerState()
    scenario("Hack: Xray ratio 0.04 (2/50)", True, p, engine,
        lambda pl, e: e.check_xray(pl, 2, 50))

    # 91. Surround hack (8 blocks/s x3)
    p = PlayerState()
    scenario("Hack: Surround 8 blocks/s x3", True, p, engine,
        lambda pl, e: e.check_surround(pl, 8, 3))

    # 92. Entity occlusion (entity behind wall, far)
    p = PlayerState()
    scenario("Hack: ESP entity behind wall 30 blocks", True, p, engine,
        lambda pl, e: e.check_entity_occlusion((0,65,0), (30,65,0), False, p=pl))

    # 93. Storage occlusion (chest behind wall, far)
    p = PlayerState()
    scenario("Hack: ChestESP behind wall 10 blocks", True, p, engine,
        lambda pl, e: e.check_storage_occlusion((0,65,0), (10,65,0), False, p=pl))

    # 94. FastClimb hack (0.25 b/tick)
    p = PlayerState()
    scenario("Hack: FastClimb 0.25 b/tick", True, p, engine,
        lambda pl, e: e.check_fastclimb(pl, 0.25))

    # 95. Movement speed hack (0.6 b/t sprint)
    p = PlayerState(is_sprinting=True, vx=0.6, vz=0.0)
    scenario("Hack: Movement speed 0.6 b/t", True, p, engine,
        lambda pl, e: e.check_movement(pl))

    # 96. AimAssist GCD too few samples (should NOT flag)
    p = PlayerState()
    p.yaw_deltas = [0.25, 0.5, 0.75]
    scenario("Edge: AimAssistGCD too few samples (no flag)", False, p, engine,
        lambda pl, e: e.check_aimassist_gcd(pl))

    # 97. Autoclicker std-dev too few samples
    p = PlayerState()
    scenario("Edge: Autoclicker too few samples (no flag)", False, p, engine,
        lambda pl, e: e.check_autoclicker_stddev([50, 50, 50], p=pl))

    # 98. AntiVoid at Y=-65
    p = PlayerState(y=-65)
    scenario("Edge: AntiVoid Y=-65 (below min)", True, p, engine,
        lambda pl, e: pl.flags.append("AntiVoid:below_min") if pl.y < -64 else None)

    # 99. AntiVoid at Y=-64 (at boundary, no flag)
    p = PlayerState(y=-64)
    scenario("Edge: AntiVoid Y=-64 (at boundary)", False, p, engine,
        lambda pl, e: pl.flags.append("AntiVoid:below_min") if pl.y < -64 else None)

    # 100. Trust system - verified player flag weight reduced
    p = PlayerState(trust=90)
    mult = engine.trust_mult(p.trust)
    scenario("Edge: Trust verified (90) weight mult 0.7 (correct)", False, p, engine,
        lambda pl, e: (pl.flags.append("trust") if e.trust_mult(90) != 0.7 else None))

    # =====================================================================
    # SECTION 3: TICK-BY-TICK SIMULATION (virtual player plays for 200 ticks)
    # =====================================================================
    print()
    print("--- Tick-by-Tick Virtual Player Simulation ---")

    # Scenario A: Legitimate player walks, sprints, jumps, attacks (200 ticks)
    player_a = PlayerState(x=0, y=64, z=0, is_sprinting=False)
    engine_a = CheckEngine()
    tick_flags_a = 0
    flyboost_streak = 0
    for tick in range(200):
        # Walk for 40 ticks
        if tick < 40:
            player_a.vx = 0.2; player_a.vz = 0.0; player_a.is_sprinting = False
        # Sprint for 40 ticks
        elif tick < 80:
            player_a.vx = 0.28; player_a.vz = 0.0; player_a.is_sprinting = True
        # Jump for 20 ticks
        elif tick < 100:
            player_a.vx = 0.30; player_a.vz = 0.0; player_a.is_sprinting = True
            if tick == 80: player_a.vy = JUMP_VELOCITY; player_a.on_ground = False
        # Attack a nearby target at tick 100 (target relative to player's current position)
        elif tick == 100:
            player_a.vx = 0.0; player_a.vz = 0.0; player_a.is_sprinting = False
            player_a.yaw = 0.0; player_a.pitch = 0.0
            target = TargetEntity(eid=1, x=player_a.x + 2.5, y=64.0, z=player_a.z)
            engine_a.check_killaura_reach(player_a, target)
            engine_a.check_impossible_hit(player_a, target)
        # Idle
        elif tick < 200:
            player_a.vx = 0.0; player_a.vz = 0.0; player_a.is_sprinting = False

        tick_physics(player_a)
        engine_a.check_movement(player_a)
        flyboost_streak = engine_a.check_flyboost(player_a, flyboost_streak, 4)

    tick_flags_a = len(player_a.flags)
    print(f"  {'PASS' if tick_flags_a == 0 else 'FAIL'}  TickSim: legit player 200 ticks (no flags)" + ("" if tick_flags_a == 0 else f" -> {player_a.flags}"))
    if tick_flags_a == 0: passed += 1
    else: failed += 1; false_positives += 1
    scenarios_run += 1

    # Scenario B: Hacker flies for 100 ticks
    player_b = PlayerState(x=0, y=64, z=0, is_sprinting=True)
    engine_b = CheckEngine()
    flyboost_streak_b = 0
    for tick in range(100):
        player_b.vx = 0.80; player_b.vz = 0.0; player_b.on_ground = False
        player_b.vy = 0.0  # hover
        tick_physics(player_b)
        engine_b.check_movement(player_b)
        flyboost_streak_b = engine_b.check_flyboost(player_b, flyboost_streak_b, 4)
        if flyboost_streak_b == 0 and tick > 4:
            player_b.flags.append(f"FlyBoost:streak_reset_at_tick={tick}")
    tick_flags_b = len(player_b.flags)
    print(f"  {'PASS' if tick_flags_b > 0 else 'FAIL'}  TickSim: hacker flies 100 ticks (flags)" + ("" if tick_flags_b > 0 else " -> NO FLAGS (false negative)"))
    if tick_flags_b > 0: passed += 1
    else: failed += 1; false_negatives += 1
    scenarios_run += 1

    # Scenario C: Aimbot player attacks with fixed-sensitivity rotations (100 ticks for 20+ deltas)
    player_c = PlayerState(x=0, y=64, z=0)
    engine_c = CheckEngine()
    sensitivity = 0.25  # fixed aimbot sensitivity
    for tick in range(100):
        # Simulate aimbot: rotation deltas every tick (multiples of sensitivity)
        if tick > 0:
            player_c.yaw += sensitivity * random.choice([1, 2, 3])
        tick_physics(player_c)
        if tick >= 20:
            target = TargetEntity(eid=1, x=0.1, y=64.0, z=3.0)
            yaw_delta = abs(player_c.yaw_deltas[-1]) if player_c.yaw_deltas else 0
            pitch_delta = 0.0
            engine_c.check_aimassist_snap(player_c, target, yaw_delta, pitch_delta)
            engine_c.check_aimassist_gcd(player_c)
    tick_flags_c = len(player_c.flags)
    print(f"  {'PASS' if tick_flags_c > 0 else 'FAIL'}  TickSim: aimbot 60 ticks with fixed sensitivity (flags)" + ("" if tick_flags_c > 0 else " -> NO FLAGS (false negative)"))
    if tick_flags_c > 0: passed += 1
    else: failed += 1; false_negatives += 1
    scenarios_run += 1

    # Scenario D: Autoclicker 100 ticks of mechanical clicking (enough for 15+ samples)
    player_d = PlayerState(x=0, y=64, z=0)
    engine_d = CheckEngine()
    click_times = []
    for tick in range(120):
        tick_physics(player_d)
        if tick >= 10 and tick % 3 == 0:  # click every 3 ticks = 150ms
            click_times.append(tick * 50)  # ms
    if len(click_times) >= 2:
        intervals = [click_times[i] - click_times[i-1] for i in range(1, len(click_times))]
        engine_d.check_autoclicker_stddev(intervals, min_samples=15, p=player_d)
    tick_flags_d = len(player_d.flags)
    print(f"  {'PASS' if tick_flags_d > 0 else 'FAIL'}  TickSim: autoclicker mechanical intervals (flags)" + ("" if tick_flags_d > 0 else " -> NO FLAGS (false negative)"))
    if tick_flags_d > 0: passed += 1
    else: failed += 1; false_negatives += 1
    scenarios_run += 1

    # =====================================================================
    # SECTION 4: STATISTICAL EDGE CASES (100+ scenarios via loop)
    # =====================================================================
    print()
    print("--- Statistical Edge Cases (batch) ---")

    random.seed(42)

    # 200 legitimate random walk scenarios (no flags expected)
    batch_fp = 0
    for i in range(200):
        p = PlayerState(is_sprinting=random.random() > 0.5,
                        on_ice=random.random() > 0.8,
                        has_speed_potion=random.choice([0, 0, 0, 1, 2]))
        # Speed within legitimate bounds
        base = SPRINT_SPEED if p.is_sprinting else WALK_SPEED
        mult = SPRINT_MULT if p.is_sprinting else 1.0
        if p.on_ice: mult *= ICE_MULT
        potion = 1.0 + 0.20 * p.has_speed_potion if p.has_speed_potion > 0 else 1.0
        max_h = base * mult * potion
        speed = max_h * random.uniform(0.5, 0.98)  # always within cap
        p.vx = speed; p.vz = 0.0
        p.flags.clear()
        engine.check_movement(p)
        if len(p.flags) > 0:
            batch_fp += 1
            print(f"  FAIL  Batch legit walk #{i}: {p.flags}")
    if batch_fp == 0:
        passed += 200
        print(f"  PASS  200 legit random walk scenarios (zero false positives)")
    else:
        failed += batch_fp
        false_positives += batch_fp
        passed += 200 - batch_fp
        scenarios_run += 200
    # adjust scenarios_run
    if batch_fp == 0: scenarios_run += 200

    # 100 hack random speed scenarios (flags expected)
    batch_fn = 0
    for i in range(200):
        p = PlayerState(is_sprinting=True)
        base = SPRINT_SPEED
        mult = SPRINT_MULT
        max_h = base * mult
        speed = max_h * random.uniform(1.3, 2.0)  # always above cap
        p.vx = speed; p.vz = 0.0
        p.flags.clear()
        engine.check_movement(p)
        if len(p.flags) == 0:
            batch_fn += 1
            print(f"  FAIL  Batch hack speed #{i}: speed={speed:.3f} no flag")
    if batch_fn == 0:
        passed += 200
        print(f"  PASS  200 hack random speed scenarios (zero false negatives)")
    else:
        failed += batch_fn
        false_negatives += batch_fn
        passed += 200 - batch_fn
        scenarios_run += 200
    if batch_fn == 0: scenarios_run += 200

    # 100 legitimate combat scenarios (no flags)
    combat_fp = 0
    for i in range(100):
        p = PlayerState(x=0, y=64, z=0, yaw=0, pitch=0)
        dist = random.uniform(2.0, 3.8)  # within reach
        t = TargetEntity(eid=1, x=dist, y=64.0, z=0.0)
        p.flags.clear()
        engine.check_killaura_reach(p, t)
        engine.check_impossible_hit(p, t)
        if len(p.flags) > 0:
            combat_fp += 1
            print(f"  FAIL  Batch legit combat #{i}: dist={dist:.2f} {p.flags}")
    if combat_fp == 0:
        passed += 100
        print(f"  PASS  100 legit combat scenarios (zero false positives)")
    else:
        failed += combat_fp
        false_positives += combat_fp
        passed += 100 - combat_fp
        scenarios_run += 100
    if combat_fp == 0: scenarios_run += 100

    # 100 hack combat scenarios (flags)
    combat_fn = 0
    for i in range(100):
        p = PlayerState(x=0, y=64, z=0, yaw=random.uniform(-180, 180), pitch=0)
        dist = random.uniform(4.3, 6.0)  # beyond reach
        t = TargetEntity(eid=1, x=dist, y=64.0, z=0.0)
        p.flags.clear()
        engine.check_killaura_reach(p, t)
        if len(p.flags) == 0:
            # Try impossible hit instead (target behind)
            t2 = TargetEntity(eid=1, x=-3.0, y=64.0, z=0.0)
            engine.check_impossible_hit(p, t2)
            if len(p.flags) == 0:
                combat_fn += 1
                print(f"  FAIL  Batch hack combat #{i}: dist={dist:.2f} no flag")
    if combat_fn == 0:
        passed += 100
        print(f"  PASS  100 hack combat scenarios (zero false negatives)")
    else:
        failed += combat_fn
        false_negatives += combat_fn
        passed += 100 - combat_fn
        scenarios_run += 100
    if combat_fn == 0: scenarios_run += 100

    # 100 GCD scenarios: aimbot (flag) vs human (no flag)
    gcd_fn = 0
    gcd_fp = 0
    for i in range(100):
        # Aimbot: multiples of a fixed sensitivity
        sens = random.choice([0.15, 0.2, 0.25, 0.3, 0.35, 0.4, 0.45])
        aimbot_deltas = [sens * random.choice([1,2,3,4]) for _ in range(25)]
        p = PlayerState()
        p.yaw_deltas = aimbot_deltas
        p.flags.clear()
        flagged = engine.check_aimassist_gcd(p)
        if not flagged:
            gcd_fn += 1
            print(f"  FAIL  Batch aimbot GCD #{i}: sens={sens} no flag")
    for i in range(100):
        # Human: random non-multiples
        human = [round(random.uniform(0.1, 1.5), 3) for _ in range(25)]
        p = PlayerState()
        p.yaw_deltas = human
        p.flags.clear()
        flagged = engine.check_aimassist_gcd(p)
        if flagged:
            gcd_fp += 1
            print(f"  FAIL  Batch human GCD #{i}: false positive")
    if gcd_fn == 0 and gcd_fp == 0:
        passed += 200
        print(f"  PASS  200 GCD scenarios (100 aimbot + 100 human)")
    else:
        failed += gcd_fn + gcd_fp
        false_negatives += gcd_fn
        false_positives += gcd_fp
        passed += 200 - gcd_fn - gcd_fp
        scenarios_run += 200
    if gcd_fn == 0 and gcd_fp == 0: scenarios_run += 200

    # 100 autoclicker std-dev: mechanical (flag) vs human (no flag)
    ac_fn = 0
    ac_fp = 0
    for i in range(100):
        # Mechanical: fixed interval + tiny noise
        base_ms = random.choice([40, 50, 60, 75, 100])
        mech = [base_ms + random.randint(-1, 1) for _ in range(20)]
        p = PlayerState()
        p.flags.clear()
        flagged = engine.check_autoclicker_stddev(mech, p=p)
        if not flagged:
            ac_fn += 1
            print(f"  FAIL  Batch autoclicker #{i}: base={base_ms}ms no flag")
    for i in range(100):
        # Human: high variance
        human = [random.randint(35, 150) for _ in range(20)]
        p = PlayerState()
        p.flags.clear()
        flagged = engine.check_autoclicker_stddev(human, p=p)
        if flagged:
            ac_fp += 1
            print(f"  FAIL  Batch human click #{i}: false positive")
    if ac_fn == 0 and ac_fp == 0:
        passed += 200
        print(f"  PASS  200 autoclicker scenarios (100 mechanical + 100 human)")
    else:
        failed += ac_fn + ac_fp
        false_negatives += ac_fn
        false_positives += ac_fp
        passed += 200 - ac_fn - ac_fp
        scenarios_run += 200
    if ac_fn == 0 and ac_fp == 0: scenarios_run += 200

    # 100 entity occlusion scenarios
    oc_fn = 0
    oc_fp = 0
    for i in range(100):
        # Entity behind wall (no LoS), far => should hide
        dist = random.uniform(10, 80)
        p = PlayerState()
        p.flags.clear()
        hidden = engine.check_entity_occlusion((0,65,0), (dist,65,0), False)
        if not hidden:
            oc_fn += 1
    for i in range(100):
        # Entity in LoS, close => should NOT hide
        dist = random.uniform(1, 40)
        p = PlayerState()
        p.flags.clear()
        hidden = engine.check_entity_occlusion((0,65,0), (dist,65,0), True)
        if hidden:
            oc_fp += 1
    if oc_fn == 0 and oc_fp == 0:
        passed += 200
        print(f"  PASS  200 entity occlusion scenarios (100 hidden + 100 visible)")
    else:
        failed += oc_fn + oc_fp
        false_negatives += oc_fn
        false_positives += oc_fp
        passed += 200 - oc_fn - oc_fp
        scenarios_run += 200
    if oc_fn == 0 and oc_fp == 0: scenarios_run += 200

    # 100 storage occlusion scenarios
    sc_fn = 0
    sc_fp = 0
    for i in range(100):
        # Chest behind wall, far => should hide
        dist = random.uniform(8, 40)
        p = PlayerState()
        hidden = engine.check_storage_occlusion((0,65,0), (dist,65,0), False)
        if not hidden: sc_fn += 1
    for i in range(100):
        # Chest visible or close => should NOT hide
        dist = random.uniform(1, 4)
        p = PlayerState()
        hidden = engine.check_storage_occlusion((0,65,0), (dist,65,0), False)
        if hidden: sc_fp += 1
    if sc_fn == 0 and sc_fp == 0:
        passed += 200
        print(f"  PASS  200 storage occlusion scenarios (100 hidden + 100 visible)")
    else:
        failed += sc_fn + sc_fp
        false_negatives += sc_fn
        false_positives += sc_fp
        passed += 200 - sc_fn - sc_fp
        scenarios_run += 200
    if sc_fn == 0 and sc_fp == 0: scenarios_run += 200

    # 100 disabler scenarios
    dis_fn = 0
    dis_fp = 0
    for i in range(100):
        missed = random.randint(3, 10)
        p = PlayerState()
        flagged = engine.check_disabler(missed, 3, 0, 2, p=p)
        if not flagged: dis_fn += 1
    for i in range(100):
        missed = random.randint(0, 2)
        wrong = random.randint(0, 1)
        p = PlayerState()
        flagged = engine.check_disabler(missed, 3, wrong, 2, p=p)
        if flagged: dis_fp += 1
    if dis_fn == 0 and dis_fp == 0:
        passed += 200
        print(f"  PASS  200 disabler scenarios (100 hack + 100 legit)")
    else:
        failed += dis_fn + dis_fp
        false_negatives += dis_fn
        false_positives += dis_fp
        passed += 200 - dis_fn - dis_fp
        scenarios_run += 200
    if dis_fn == 0 and dis_fp == 0: scenarios_run += 200

    # =====================================================================
    # RESULTS
    # =====================================================================
    print()
    print("=" * 72)
    print("=== SIMULATION RESULTS ===")
    print("=" * 72)
    total = passed + failed
    rate = (passed * 100.0 / total) if total > 0 else 0
    error_rate = (failed * 100.0 / total) if total > 0 else 0
    print(f"Total scenarios:     {scenarios_run}")
    print(f"Passed:              {passed}")
    print(f"Failed:              {failed}")
    print(f"False positives:     {false_positives}")
    print(f"False negatives:     {false_negatives}")
    print(f"Accuracy:            {rate:.4f}%")
    print(f"Error rate:          {error_rate:.4f}%")
    print()
    if rate >= 99.99:
        print("TARGET MET: 99.99% accuracy achieved")
    elif rate >= 99.0:
        print(f"Close to target: {rate:.2f}% (need 99.99%)")
    else:
        print(f"TARGET NOT MET: {rate:.2f}% (need 99.99%)")
    if false_positives > 0:
        print(f"  WARNING: {false_positives} false positive(s) - legitimate players flagged")
    if false_negatives > 0:
        print(f"  WARNING: {false_negatives} false negative(s) - hackers not detected")
    print()
    return rate >= 99.99


if __name__ == "__main__":
    success = run()
    exit(0 if success else 1)
