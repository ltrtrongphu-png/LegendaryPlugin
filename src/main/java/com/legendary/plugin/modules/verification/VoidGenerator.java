package com.legendary.plugin.modules.verification;

import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.util.List;
import java.util.Random;

/**
 * Empty-void chunk generator for the join-verification limbo world - no
 * terrain to generate keeps join-time chunk generation essentially free.
 */
public final class VoidGenerator extends ChunkGenerator {

    @Override
    public boolean shouldGenerateNoise() { return false; }

    @Override
    public boolean shouldGenerateSurface() { return false; }

    @Override
    public boolean shouldGenerateCaves() { return false; }

    @Override
    public boolean shouldGenerateDecorations() { return false; }

    @Override
    public boolean shouldGenerateMobs() { return false; }

    @Override
    public boolean shouldGenerateStructures() { return false; }

    @Override
    public BiomeProvider getDefaultBiomeProvider(WorldInfo worldInfo) {
        return new BiomeProvider() {
            @Override
            public org.bukkit.block.Biome getBiome(WorldInfo worldInfo, int x, int y, int z) {
                return org.bukkit.block.Biome.THE_VOID;
            }

            @Override
            public List<org.bukkit.block.Biome> getBiomes(WorldInfo worldInfo) {
                return List.of(org.bukkit.block.Biome.THE_VOID);
            }
        };
    }
}
