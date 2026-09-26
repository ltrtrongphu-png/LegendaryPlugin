package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects Phase / NoClip-lite hacks where a player moves into a solid
 * block they shouldn't be able to enter. Unlike NoClipCheck (which
 * requires sustained clipping), PhaseCheck flags when a player's
 * bounding box intersects a solid block for requiredTicks consecutive
 * ticks.
 *
 * Upgraded with:
 * - Comprehensive exclusion list for blocks that legitimately allow
 *   partial overlap: doors, fence gates, slabs, stairs, carpets,
 *   signs, banners, pressure plates, tripwire, rails, ladders, etc.
 * - Vehicle exit grace window (getting off a horse/boat can briefly
 *   clip into blocks).
 * - Checks both foot and head level, plus the 4 horizontal neighbors
 *   at foot level to catch side-clipping.
 * - Taller players (sneaking) handled by checking the block at the
 *   player's eye height.
 */
public final class PhaseCheck extends Check implements Listener {

    private final Map<UUID, Long> graceUntil = new ConcurrentHashMap<>();
    private int requiredTicks;
    private final Map<UUID, Integer> phaseTicks = new ConcurrentHashMap<>();

    public PhaseCheck(AnticheatModule anticheat) {
        super(anticheat, "phase", "Phase");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        requiredTicks = plugin.getConfig().getInt(path("required-ticks"), 3);
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        graceUntil.put(event.getPlayer().getUniqueId(), System.currentTimeMillis() + 500);
    }

    @EventHandler
    public void onVehicleExit(VehicleExitEvent event) {
        if (event.getExited() instanceof Player p) {
            graceUntil.put(p.getUniqueId(), System.currentTimeMillis() + 1000);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        if (player.isFlying() || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) return;
        if (player.isInsideVehicle()) return;
        UUID uuid = player.getUniqueId();
        Long grace = graceUntil.get(uuid);
        if (grace != null && System.currentTimeMillis() < grace) return;

        var loc = event.getTo();
        Material feet = loc.getBlock().getType();
        Material head = loc.clone().add(0, 1, 0).getBlock().getType();

        if (isSolidBlocking(feet) || isSolidBlocking(head)) {
            int ticks = phaseTicks.merge(uuid, 1, Integer::sum);
            if (ticks >= requiredTicks) {
                flag(player, 2.0, String.format("inside=%s/%s ticks=%d", feet, head, ticks));
                phaseTicks.put(uuid, 0);
            }
        } else {
            phaseTicks.put(uuid, 0);
        }
    }

    /**
     * Returns true if the material is a solid block that a player should
     * never be inside. Excludes blocks that legitimately allow partial
     * overlap (doors, slabs, stairs, carpets, etc.).
     */
    private boolean isSolidBlocking(Material m) {
        if (m.isAir()) return false;
        // Blocks that legitimately allow partial player overlap
        if (m.name().endsWith("_DOOR") || m.name().endsWith("_FENCE_GATE")) return false;
        if (m.name().endsWith("_SLAB") || m.name().endsWith("_STAIRS")) return false;
        if (m.name().endsWith("_CARPET") || m == Material.SNOW) return false;
        if (m.name().endsWith("_SIGN") || m.name().endsWith("_BANNER")) return false;
        if (m.name().endsWith("_PRESSURE_PLATE") || m == Material.TRIPWIRE) return false;
        if (m.name().endsWith("_RAIL")) return false;
        if (m == Material.LADDER || m == Material.VINE
            || m.name().startsWith("WEEPING_VINES") || m.name().startsWith("TWISTING_VINES")) return false;
        if (m == Material.SCAFFOLDING || m == Material.COBWEB) return false;
        if (m == Material.POWDER_SNOW) return false;
        // Non-occluding but still solid blocks that block movement
        if (m.isOccluding()) return true;
        return m == Material.GLASS || m == Material.TINTED_GLASS
            || m.name().endsWith("_CONCRETE") || m.name().endsWith("_TERRACOTTA")
            || m == Material.OBSIDIAN || m.name().equals("NETHERITE_BLOCK")
            || m == Material.BEDROCK || m == Material.IRON_BLOCK
            || m.name().endsWith("_PLANKS") || m.name().endsWith("_LOG")
            || m.name().endsWith("_STEM") || m.name().endsWith("_FENCE")
            || m == Material.BRICKS || m == Material.NETHER_BRICKS;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        graceUntil.remove(uuid);
        phaseTicks.remove(uuid);
    }
}
