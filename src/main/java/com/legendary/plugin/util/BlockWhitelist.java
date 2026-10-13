package com.legendary.plugin.util;

import org.bukkit.Material;

import java.util.EnumSet;
import java.util.Set;

/**
 * Shared "this material doesn't count as solid ground" whitelist, used by
 * {@link com.legendary.plugin.modules.anticheat.checks.JesusCheck} so
 * standing on a lily pad or swimming through kelp/seagrass isn't confused
 * with walking on the water's surface.
 */
public final class BlockWhitelist {

    public static final Set<Material> NON_SOLID = EnumSet.of(
        Material.LILY_PAD, Material.SEAGRASS, Material.TALL_SEAGRASS,
        Material.KELP, Material.KELP_PLANT, Material.BUBBLE_COLUMN,
        Material.WATER, Material.AIR, Material.CAVE_AIR);

    private BlockWhitelist() {}
}
