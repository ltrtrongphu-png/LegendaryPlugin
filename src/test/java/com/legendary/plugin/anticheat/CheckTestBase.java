package com.legendary.plugin.anticheat;

import com.legendary.plugin.LegendaryPlugin;
import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.BypassGuard;
import com.legendary.plugin.modules.anticheat.TpsMonitor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

public abstract class CheckTestBase {

    protected LegendaryPlugin plugin;
    protected FileConfiguration config;
    protected AnticheatModule anticheat;
    protected TpsMonitor tpsMonitor;
    protected BypassGuard bypassGuard;

    private org.mockito.MockedStatic<com.legendary.plugin.modules.anticheat.physics.VanillaPhysics> vanillaPhysicsMock;
    private org.mockito.MockedStatic<com.legendary.plugin.util.GeyserExemption> geyserMock;

    protected final List<FlagCall> flagCalls = new ArrayList<>();

    protected static final class FlagCall {
        final String checkId;
        final String playerName;
        final double weight;
        final String detail;

        FlagCall(String checkId, String playerName, double weight, String detail) {
            this.checkId = checkId;
            this.playerName = playerName;
            this.weight = weight;
            this.detail = detail;
        }
    }

    @BeforeEach
    void setUpBase() {
        flagCalls.clear();
        vanillaPhysicsMock = mockStatic(com.legendary.plugin.modules.anticheat.physics.VanillaPhysics.class);
        vanillaPhysicsMock.when(() -> com.legendary.plugin.modules.anticheat.physics.VanillaPhysics.horizontalSpeedMultiplier(any())).thenReturn(1.0);
        vanillaPhysicsMock.when(() -> com.legendary.plugin.modules.anticheat.physics.VanillaPhysics.hasVerticalOverrideEffect(any())).thenReturn(false);
        vanillaPhysicsMock.when(() -> com.legendary.plugin.modules.anticheat.physics.VanillaPhysics.extraVerticalTolerance(any())).thenReturn(0.0);

        geyserMock = mockStatic(com.legendary.plugin.util.GeyserExemption.class);
        geyserMock.when(() -> com.legendary.plugin.util.GeyserExemption.isBedrock(any())).thenReturn(false);
        geyserMock.when(com.legendary.plugin.util.GeyserExemption::isLoaded).thenReturn(false);

        plugin = mock(LegendaryPlugin.class);
        config = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(config);

        when(config.getBoolean(anyString(), Mockito.anyBoolean())).thenAnswer(inv -> inv.getArgument(1));
        when(config.getDouble(anyString(), anyDouble())).thenAnswer(inv -> inv.getArgument(1));
        when(config.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        when(config.getLong(anyString(), anyLong())).thenAnswer(inv -> inv.getArgument(1));
        when(config.getString(anyString(), Mockito.any())).thenAnswer(inv -> inv.getArgument(1));
        when(config.getStringList(anyString())).thenReturn(List.of());

        tpsMonitor = mock(TpsMonitor.class);
        when(tpsMonitor.getCompensationFactor()).thenReturn(1.0);
        when(tpsMonitor.getSensitivityMultiplier()).thenReturn(1.0);
        when(tpsMonitor.getTps()).thenReturn(20.0);
        when(tpsMonitor.isLagSpike()).thenReturn(false);

        bypassGuard = mock(BypassGuard.class);
        when(bypassGuard.shouldBypass(any(), anyString())).thenReturn(false);

        anticheat = mock(AnticheatModule.class);
        when(anticheat.getPlugin()).thenReturn(plugin);
        when(anticheat.getTpsMonitor()).thenReturn(tpsMonitor);
        when(anticheat.getBypassGuard()).thenReturn(bypassGuard);

        Mockito.doAnswer(inv -> {
            Object checkArg = inv.getArgument(0);
            Player playerArg = inv.getArgument(1);
            double weight = inv.getArgument(2);
            String detail = inv.getArgument(3);
            String checkId = checkArg instanceof com.legendary.plugin.modules.anticheat.Check
                ? ((com.legendary.plugin.modules.anticheat.Check) checkArg).getId()
                : String.valueOf(checkArg);
            flagCalls.add(new FlagCall(checkId, playerArg.getName(), weight, detail));
            return null;
        }).when(anticheat).flag(any(), any(Player.class), anyDouble(), anyString());
    }

    @org.junit.jupiter.api.AfterEach
    void tearDownBase() {
        if (vanillaPhysicsMock != null) vanillaPhysicsMock.close();
        if (geyserMock != null) geyserMock.close();
    }

    @SuppressWarnings("unchecked")
    protected Player makePlayer(UUID uuid, String name, World world, Location loc) {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(uuid);
        when(p.getName()).thenReturn(name);
        when(p.getWorld()).thenReturn(world);
        when(p.getLocation()).thenReturn(loc);
        when(p.getEyeLocation()).thenReturn(loc);
        when(p.isDead()).thenReturn(false);
        when(p.isOnline()).thenReturn(true);
        when(p.getGameMode()).thenReturn(org.bukkit.GameMode.SURVIVAL);
        when(p.getAllowFlight()).thenReturn(false);
        when(p.isFlying()).thenReturn(false);
        when(p.isGliding()).thenReturn(false);
        when(p.isInsideVehicle()).thenReturn(false);
        when(p.isOnGround()).thenReturn(true);
        when(p.isSneaking()).thenReturn(false);
        when(p.isSprinting()).thenReturn(false);
        when(p.isInWater()).thenReturn(false);
        when(p.isClimbing()).thenReturn(false);
        when(p.getFallDistance()).thenReturn(0f);
        when(p.getPing()).thenReturn(20);
        when(p.hasPermission(anyString())).thenReturn(false);
        when(p.isOp()).thenReturn(false);
        when(p.getPotionEffect(any())).thenReturn(null);
        when(p.hasPotionEffect(any())).thenReturn(false);

        PlayerInventory inv = mock(PlayerInventory.class);
        org.bukkit.inventory.ItemStack air = mock(org.bukkit.inventory.ItemStack.class);
        when(air.getType()).thenReturn(org.bukkit.Material.AIR);
        when(air.containsEnchantment(any())).thenReturn(false);
        when(inv.getBoots()).thenReturn(air);
        when(inv.getLeggings()).thenReturn(air);
        when(p.getInventory()).thenReturn(inv);

        return p;
    }

    protected Player makePlayer(String name, double x, double y, double z) {
        World world = mock(World.class);
        Location loc = makeLocation(world, x, y, z);
        return makePlayer(UUID.randomUUID(), name, world, loc);
    }

    protected Location makeLocation(World world, double x, double y, double z) {
        Location loc = mock(Location.class);
        when(loc.getWorld()).thenReturn(world);
        when(loc.getX()).thenReturn(x);
        when(loc.getY()).thenReturn(y);
        when(loc.getZ()).thenReturn(z);
        when(loc.getYaw()).thenReturn(0f);
        when(loc.getPitch()).thenReturn(0f);
        when(loc.clone()).thenReturn(loc);
        when(loc.subtract(anyDouble(), anyDouble(), anyDouble())).thenReturn(loc);
        when(loc.add(anyDouble(), anyDouble(), anyDouble())).thenReturn(loc);

        org.bukkit.block.Block airBlock = mock(org.bukkit.block.Block.class);
        when(airBlock.getType()).thenReturn(org.bukkit.Material.AIR);
        when(loc.getBlock()).thenReturn(airBlock);

        when(loc.distance(any(Location.class))).thenAnswer(inv -> {
            Location other = inv.getArgument(0);
            double dx = x - other.getX();
            double dy = y - other.getY();
            double dz = z - other.getZ();
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        });
        when(loc.distanceSquared(any(Location.class))).thenAnswer(inv -> {
            Location other = inv.getArgument(0);
            double dx = x - other.getX();
            double dy = y - other.getY();
            double dz = z - other.getZ();
            return dx * dx + dy * dy + dz * dz;
        });

        return loc;
    }

    protected org.bukkit.event.player.PlayerMoveEvent makeMoveEvent(Player p, Location from, Location to) {
        return new org.bukkit.event.player.PlayerMoveEvent(p, from, to);
    }

    protected org.bukkit.event.entity.EntityDamageByEntityEvent makeDamageEvent(
            org.bukkit.entity.Entity damager, org.bukkit.entity.Entity target, boolean critical) {
        org.bukkit.event.entity.EntityDamageByEntityEvent event = mock(org.bukkit.event.entity.EntityDamageByEntityEvent.class);
        when(event.getDamager()).thenReturn(damager);
        when(event.getEntity()).thenReturn(target);
        when(event.isCritical()).thenReturn(critical);
        when(event.getCause()).thenReturn(org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK);
        return event;
    }

    protected org.bukkit.event.entity.EntityDamageByEntityEvent makeDamageEvent(
            org.bukkit.entity.Entity damager, org.bukkit.entity.Entity target) {
        return makeDamageEvent(damager, target, false);
    }

    protected org.bukkit.event.player.PlayerAnimationEvent makeSwingEvent(Player player) {
        org.bukkit.event.player.PlayerAnimationEvent event = mock(org.bukkit.event.player.PlayerAnimationEvent.class);
        when(event.getPlayer()).thenReturn(player);
        return event;
    }

    protected boolean flagged(String checkId) {
        return flagCalls.stream().anyMatch(f -> f.checkId.equals(checkId));
    }

    protected long flagCount(String checkId) {
        return flagCalls.stream().filter(f -> f.checkId.equals(checkId)).count();
    }
}
