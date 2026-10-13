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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

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
        Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE,
        Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE);

    private final Map<UUID, Long> totalBlocksMined = new ConcurrentHashMap<>();
    private final Map<UUID, Long> hiddenOresFound = new ConcurrentHashMap<>();
    private int minBlocksForRatio;
    private double hiddenOreRatioThreshold;
    private String alertPermission;

    public XrayCheck(AnticheatModule anticheat) {
        super(anticheat, "xray", "XRay");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        minBlocksForRatio = ConfigUtil.getBoundedInt(plugin, path("min-blocks-for-ratio"), 40, 5, 2000);
        hiddenOreRatioThreshold = plugin.getConfig().getDouble(path("hidden-ore-ratio"), 0.03);
        alertPermission = plugin.getConfig().getString(path("alert-permission"), "legendary.anticheat.alerts");
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        UUID uuid = player.getUniqueId();
        long total = totalBlocksMined.merge(uuid, 1L, Long::sum);

        Block block = event.getBlock();
        if (VALUABLE_ORES.contains(block.getType()) && isFullyHidden(block)) {
            long hidden = hiddenOresFound.merge(uuid, 1L, Long::sum);
            if (total >= minBlocksForRatio) {
                double ratio = hidden / (double) total;
                if (ratio > hiddenOreRatioThreshold) {
                    String detail = String.format("hiddenOreRatio=%.3f (%s)", ratio, prettyName(block.getType()));
                    flag(player, 0.5, detail);
                    writeReviewEntry(player, block, total, hidden, ratio, detail);
                }
            }
        }
        if (totalBlocksMined.size() > 10_000) {
            UUID oldest = totalBlocksMined.keySet().stream().findFirst().orElse(null);
            if (oldest != null && !oldest.equals(uuid)) {
                totalBlocksMined.remove(oldest);
                hiddenOresFound.remove(oldest);
            }
        }
    }

    private boolean isFullyHidden(Block block) {
        for (BlockFace face : new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH,
                BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block adjacent = block.getRelative(face);
            if (isOpenSpace(adjacent) || adjacent.getLightLevel() > 0) return false;
        }
        return true;
    }

    private boolean isOpenSpace(Block block) {
        Material type = block.getType();
        return type.isAir() || !type.isOccluding() || block.isPassable();
    }

    private String prettyName(Material m) {
        return m.name().toLowerCase().replace('_', ' ');
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // Keep session counts through reconnects to prevent a fixed-size reset window.
    }

    private void writeReviewEntry(Player player, Block block, long total, long hidden,
                                  double ratio, String detail) {
        Path path = plugin.getDataFolder().toPath().resolve("anticheat").resolve("xray-review.log");
        String line = System.currentTimeMillis() + "\t" + player.getUniqueId() + "\t"
            + player.getName() + "\t" + block.getWorld().getName() + "\t"
            + block.getX() + "," + block.getY() + "," + block.getZ() + "\t"
            + total + "\t" + hidden + "\t" + ratio + "\t" + detail + System.lineSeparator();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Files.createDirectories(path.getParent());
                Files.writeString(path, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (java.io.IOException exception) {
                plugin.getLogger().warning("Could not write X-ray review entry: " + exception.getMessage());
            }
        });
    }
}
