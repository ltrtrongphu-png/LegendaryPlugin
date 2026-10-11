package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.core.ConfigUtil;
import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.RayTraceResult;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NEW CHECK (2026-09-19 DeepSeek-dump merge review). Distinct signal from
 * {@link ContainerReachCheck}: that check only measures eye-to-block
 * straight-line distance. ChestAura instead raycasts along the player's
 * actual look direction and flags when the container they opened is NOT
 * what their crosshair was pointing at - i.e. interacting with a chest
 * while visibly looking somewhere else, a classic "chest aura" / auto-loot
 * hack signature that a pure distance check cannot catch (a hacked
 * client can stand close enough to pass the distance check while looking
 * at the sky).
 */
public final class ChestAuraCheck extends Check implements Listener {

    private static final Set<Material> CONTAINERS = EnumSet.of(
        Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL, Material.FURNACE,
        Material.BLAST_FURNACE, Material.SMOKER, Material.HOPPER, Material.DISPENSER,
        Material.DROPPER, Material.SHULKER_BOX, Material.ENDER_CHEST, Material.BREWING_STAND);

    private final Map<UUID, Integer> buffer = new ConcurrentHashMap<>();
    private double raycastDistance;
    private int requiredBuffer;

    public ChestAuraCheck(AnticheatModule anticheat) {
        super(anticheat, "chestaura", "ChestAura");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        raycastDistance = plugin.getConfig().getDouble(path("raycast-distance"), 6.0);
        requiredBuffer = ConfigUtil.getBoundedInt(plugin, path("required-buffer"), 2, 1, 10);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        Block clicked = event.getClickedBlock();
        if (clicked == null || !CONTAINERS.contains(clicked.getType())) return;

        RayTraceResult ray = player.rayTraceBlocks(raycastDistance);
        boolean raycastMatches = ray != null && ray.getHitBlock() != null && ray.getHitBlock().equals(clicked);
        UUID uuid = player.getUniqueId();
        if (!raycastMatches) {
            int count = buffer.merge(uuid, 1, Integer::sum);
            if (count >= requiredBuffer) {
                flag(player, 1.0, "opened " + clicked.getType() + " without a matching raycast hit");
                buffer.put(uuid, 0);
            }
        } else {
            buffer.merge(uuid, -1, (a, b) -> Math.max(0, a + b));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        buffer.remove(event.getPlayer().getUniqueId());
    }
}
