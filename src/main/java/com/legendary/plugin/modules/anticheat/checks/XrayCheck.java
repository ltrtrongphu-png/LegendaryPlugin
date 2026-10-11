package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.core.ConfigUtil;
import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects X-ray usage (statistically): flags players who mine a
 * suspiciously high ratio of valuable ores that were fully enclosed by
 * opaque blocks (i.e. never visually exposed) versus their total blocks
 * mined. Complements the ESP module's block-obfuscation defense with
 * detection for players who exploited some other blind spot.
 */
public final class XrayCheck extends Check implements Listener {

    private static final Set<Material> VALUABLE_ORES = EnumSet.of(
        Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,
        Material.ANCIENT_DEBRIS, Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE,
        Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE);

    private final Map<UUID, Integer> totalBlocksMined = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> hiddenOresFound = new ConcurrentHashMap<>();
    private int minBlocksForRatio;
    private double hiddenOreRatioThreshold;
    private int blockResetInterval;
    private String alertPermission;

    public XrayCheck(AnticheatModule anticheat) {
        super(anticheat, "xray", "XRay");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        minBlocksForRatio = ConfigUtil.getBoundedInt(plugin, path("min-blocks-for-ratio"), 40, 5, 2000);
        hiddenOreRatioThreshold = plugin.getConfig().getDouble(path("hidden-ore-ratio"), 0.03);
        blockResetInterval = ConfigUtil.getBoundedInt(plugin, path("block-reset-interval"), 500, 50, 10000);
        alertPermission = plugin.getConfig().getString(path("alert-permission"), "legendary.anticheat.alerts");
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        UUID uuid = player.getUniqueId();
        int total = totalBlocksMined.merge(uuid, 1, Integer::sum);

        if (total >= blockResetInterval) {
            totalBlocksMined.put(uuid, 0);
            hiddenOresFound.put(uuid, 0);
            total = 0;
        }

        Block block = event.getBlock();
        if (VALUABLE_ORES.contains(block.getType()) && isFullyHidden(block)) {
            int hidden = hiddenOresFound.merge(uuid, 1, Integer::sum);
            if (total >= minBlocksForRatio) {
                double ratio = hidden / (double) total;
                if (ratio > hiddenOreRatioThreshold) {
                    flag(player, 0.5, String.format("hiddenOreRatio=%.3f (%s)", ratio, prettyName(block.getType())));
                }
            }
        }
    }

    private boolean isFullyHidden(Block block) {
        for (BlockFace face : new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH,
                BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            if (isOpenSpace(block.getRelative(face).getType())) return false;
        }
        return true;
    }

    private boolean isOpenSpace(Material m) {
        return m == Material.AIR || m == Material.CAVE_AIR || m == Material.VOID_AIR
            || m == Material.WATER || m == Material.LAVA
            || m == Material.POWDER_SNOW;
    }

    private String prettyName(Material m) {
        return m.name().toLowerCase().replace('_', ' ');
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        totalBlocksMined.remove(uuid);
        hiddenOresFound.remove(uuid);
    }
}
