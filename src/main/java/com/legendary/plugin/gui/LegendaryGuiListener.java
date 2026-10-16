package com.legendary.plugin.gui;

import com.legendary.plugin.LegendaryPlugin;
import com.legendary.plugin.core.Module;
import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import com.legendary.plugin.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Admin GUI: a small hub (Modules / Players / Checks) instead of the
 * original single module-toggle screen. Scoped-down from the DeepSeek
 * dump's 9-menu suite (MainMenu/PlayerListMenu/PlayerDetailMenu/
 * CheckListMenu/SettingsMenu/LogsMenu/OptimizationMenu/PerformanceMenu/
 * IntegrationsMenu) to the three screens that add something the text
 * commands don't already cover just as well: browsing online players'
 * live violation levels, and toggling individual checks (not just whole
 * modules) without typing a check id from memory.
 */
public final class LegendaryGuiListener implements Listener {

    private final LegendaryPlugin plugin;
    private final Map<UUID, Long> actionCooldowns = new ConcurrentHashMap<>();
    private static final long ACTION_COOLDOWN_MS = 150;

    public LegendaryGuiListener(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    // ---- entry point ----

    public void open(Player player) {
        openMain(player);
    }

    private Inventory blank(int size, String title) {
        return blank(size, Text.of(title));
    }

    private Inventory blank(int size, Component title) {
        LegendaryGuiHolder holder = new LegendaryGuiHolder();
        Inventory inventory = Bukkit.createInventory(holder, size, title);
        holder.setInventory(inventory);
        return inventory;
    }

    private Optional<AnticheatModule> anticheat() {
        return plugin.getModule("anticheat").filter(m -> m instanceof AnticheatModule).map(m -> (AnticheatModule) m);
    }

    // ---- screens ----

    private void openMain(Player player) {
        Inventory inv = blank(27, "<gradient:#38BDF8:#A855F7>LegendaryPlugin</gradient>");
        inv.setItem(11, GuiActionItem.build(Material.COMMAND_BLOCK, "<white>Modules",
            List.of("<gray>Enable/disable each feature module"), "open:modules"));
        inv.setItem(13, GuiActionItem.build(Material.PLAYER_HEAD, "<white>Players",
            List.of("<gray>Browse online players' violation levels"), "open:players"));
        inv.setItem(15, GuiActionItem.build(Material.NETHER_STAR, "<white>Checks",
            List.of("<gray>Enable/disable individual anti-cheat checks"), "open:checks"));
        player.openInventory(inv);
    }

    private void openModules(Player player, int page) {
        Inventory inv = blank(27, "<gradient:#38BDF8:#A855F7>LegendaryPlugin</gradient> <gray>- Modules");
        inv.setItem(0, backButton("open:main"));
        List<Module> modules = new ArrayList<>(plugin.getModuleManager().all().values());
        int maxPage = Math.max(0, (modules.size() - 1) / 7);
        page = Math.max(0, Math.min(page, maxPage));
        int slot = 10;
        for (int i = page * 7; i < Math.min(modules.size(), (page + 1) * 7); i++) {
            inv.setItem(slot++, ModuleToggleItem.build(plugin, modules.get(i)));
        }
        if (page > 0) inv.setItem(18, GuiActionItem.build(Material.ARROW, "<white>Previous page", null, "modules_page:" + (page - 1)));
        if (page < maxPage) inv.setItem(26, GuiActionItem.build(Material.ARROW, "<white>Next page", null, "modules_page:" + (page + 1)));
        player.openInventory(inv);
    }

    private void openPlayers(Player player) {
        Inventory inv = blank(54, "<gradient:#38BDF8:#A855F7>LegendaryPlugin</gradient> <gray>- Players");
        inv.setItem(0, backButton("open:main"));
        int slot = 9;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (slot >= 54) break;
            inv.setItem(slot++, playerHead(online, "player:" + online.getUniqueId()));
        }
        player.openInventory(inv);
    }

    private void openPlayerDetail(Player staff, UUID targetUuid) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetUuid);
        String name = target.getName() == null ? targetUuid.toString() : target.getName();
        Inventory inv = blank(54, Text.of(
            "<gradient:#38BDF8:#A855F7>LegendaryPlugin</gradient> <gray>- <target>",
            Text.placeholder("target", name)));
        inv.setItem(0, backButton("open:players"));

        var anticheatOpt = anticheat();
        int slot = 9;
        if (anticheatOpt.isPresent() && target.getPlayer() != null) {
            AnticheatModule anticheat = anticheatOpt.get();
            Player online = target.getPlayer();
            for (Check check : anticheat.getCheckRegistry().all().values()) {
                double vl = anticheat.getViolationManager().getVl(online, check.getId());
                if (vl <= 0) continue;
                if (slot >= 45) break;
                inv.setItem(slot++, GuiActionItem.build(Material.PAPER,
                    "<white>" + check.getDisplayName(), List.of("<gray>VL: <red>" + String.format("%.1f", vl)), "noop"));
            }
        }

        inv.setItem(46, GuiActionItem.build(Material.BARRIER, "<red>Reset all violations",
            List.of("<gray>Clears this player's VL for every check"), "reset_vl:" + targetUuid));
        boolean watching = anticheatOpt.map(a -> a.getWatchManager().isWatching(staff, targetUuid)).orElse(false);
        inv.setItem(48, GuiActionItem.build(watching ? Material.ENDER_EYE : Material.ENDER_PEARL,
            watching ? "<green>Watching - click to stop" : "<white>Watch this player",
            List.of("<gray>Get detailed alerts for every flag on them"), "watch:" + targetUuid));
        inv.setItem(50, GuiActionItem.build(Material.NETHER_STAR, "<red>Ban player",
            List.of("<gray>Manual ban via configured ban command"), "ban:" + targetUuid));
        staff.openInventory(inv);
    }

    private void openChecks(Player player, int page) {
        var anticheatOpt = anticheat();
        Inventory inv = blank(54, "<gradient:#38BDF8:#A855F7>LegendaryPlugin</gradient> <gray>- Checks");
        inv.setItem(0, backButton("open:main"));
        if (anticheatOpt.isEmpty()) {
            player.openInventory(inv);
            return;
        }
        List<Check> checks = new ArrayList<>(anticheatOpt.get().getCheckRegistry().all().values());
        int maxPage = Math.max(0, (checks.size() - 1) / 36);
        page = Math.max(0, Math.min(page, maxPage));
        int slot = 9;
        for (int i = page * 36; i < Math.min(checks.size(), (page + 1) * 36); i++) {
            Check check = checks.get(i);
            boolean enabled = check.isEnabled();
            inv.setItem(slot++, GuiActionItem.build(enabled ? Material.LIME_DYE : Material.GRAY_DYE,
                "<white>" + check.getDisplayName(),
                List.of(enabled ? "<green>Enabled - click to disable" : "<red>Disabled - click to enable"),
                "toggle_check:" + check.getId()));
        }
        if (page > 0) inv.setItem(45, GuiActionItem.build(Material.ARROW, "<white>Previous page", null, "checks_page:" + (page - 1)));
        if (page < maxPage) inv.setItem(53, GuiActionItem.build(Material.ARROW, "<white>Next page", null, "checks_page:" + (page + 1)));
        player.openInventory(inv);
    }

    private void openBanConfirmation(Player staff, UUID target) {
        Inventory inv = blank(27, "<red>Confirm manual ban");
        inv.setItem(11, GuiActionItem.build(Material.LIME_WOOL, "<green>Confirm ban",
            List.of("<gray>Uses the configured default duration"), "confirm_ban:" + target));
        inv.setItem(15, GuiActionItem.build(Material.RED_WOOL, "<red>Cancel", null, "cancel_ban"));
        staff.openInventory(inv);
    }

    private void openModuleConfirmation(Player staff, String moduleId, boolean enable) {
        Inventory inv = blank(27, "<red>Confirm module change");
        inv.setItem(11, GuiActionItem.build(Material.LIME_WOOL, "<green>Confirm",
            List.of("<gray>" + (enable ? "Enable " : "Disable ") + moduleId), "confirm_module:" + moduleId + ":" + enable));
        inv.setItem(15, GuiActionItem.build(Material.RED_WOOL, "<red>Cancel", null, "cancel_module"));
        staff.openInventory(inv);
    }

    private ItemStack backButton(String action) {
        return GuiActionItem.build(Material.ARROW, "<gray>« Back", null, action);
    }

    private ItemStack playerHead(Player target, String action) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        if (item.getItemMeta() instanceof SkullMeta skullMeta) {
            skullMeta.setOwningPlayer(target);
            skullMeta.displayName(Text.of("<white><target>", Text.placeholder("target", target.getName())));
            item.setItemMeta(skullMeta);
        }
        var meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(GuiActionItem.ACTION_KEY,
            org.bukkit.persistence.PersistentDataType.STRING, action);
        item.setItemMeta(meta);
        return item;
    }

    // ---- click routing ----

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof LegendaryGuiHolder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!player.hasPermission("legendary.admin")) {
            player.closeInventory();
            Text.send(player, "<red>Your legendary.admin permission is required for this menu.");
            return;
        }

        String action = GuiActionItem.readAction(event.getCurrentItem());
        if (action == null || action.equals("noop")) return;

        // Cooldown destructive actions to prevent macro click-spam
        boolean isAction = !action.startsWith("open:");
        if (isAction) {
            long now = System.currentTimeMillis();
            Long last = actionCooldowns.get(player.getUniqueId());
            if (last != null && now - last < ACTION_COOLDOWN_MS) return;
            actionCooldowns.put(player.getUniqueId(), now);
        }

        int colon = action.indexOf(':');
        String verb = colon < 0 ? action : action.substring(0, colon);
        String arg = colon < 0 ? "" : action.substring(colon + 1);

        switch (verb) {
            case "open" -> {
                switch (arg) {
                    case "main" -> openMain(player);
                    case "modules" -> openModules(player, 0);
                    case "players" -> openPlayers(player);
                    case "checks" -> openChecks(player, 0);
                }
            }
            case "modules_page" -> openModules(player, parsePage(arg));
            case "checks_page" -> openChecks(player, parsePage(arg));
            case "toggle_module" -> {
                var module = plugin.getModuleManager().get(arg);
                if (module.isPresent()) {
                    boolean enable = !module.get().isEnabled();
                    if (arg.equalsIgnoreCase("anticheat") || arg.equalsIgnoreCase("anti-ddos")) {
                        openModuleConfirmation(player, arg, enable);
                        return;
                    }
                    applyModuleToggle(player, arg, enable);
                }
                openModules(player, 0);
            }
            case "confirm_module" -> {
                String[] values = arg.split(":", 2);
                if (values.length == 2) {
                    boolean enable = Boolean.parseBoolean(values[1]);
                    applyModuleToggle(player, values[0], enable);
                }
                openModules(player, 0);
            }
            case "cancel_module" -> openModules(player, 0);
            case "cancel_ban" -> openMain(player);
            case "confirm_ban" -> {
                try {
                    UUID targetId = UUID.fromString(arg);
                    Player target = Bukkit.getPlayer(targetId);
                    if (target == null) {
                        Text.send(player, "<red>That player is no longer online.");
                    } else {
                        var anticheat = anticheat();
                        if (anticheat.isPresent()) {
                            String duration = plugin.getConfig().getString("anticheat.ban.default-duration", "30d");
                            anticheat.get().getDatabaseManager().recordBanAuditAsync(
                                player.getUniqueId().toString(), targetId.toString(), target.getName(),
                                "manual-gui", 0.0, "Manual ban requested via GUI");
                            plugin.getLogger().warning("[Ban Audit] staff=" + player.getUniqueId()
                                + " target=" + targetId + " requested manual GUI ban.");
                            anticheat.get().getBanExecutor().banDirect(target,
                                "Manual ban requested via GUI", duration);
                        }
                    }
                } catch (IllegalArgumentException ignored) {
                    Text.send(player, "<red>Invalid target for ban confirmation.");
                }
                player.closeInventory();
            }
            case "toggle_check" -> {
                anticheat().ifPresent(a -> {
                    var check = a.getCheckRegistry().get(arg);
                    a.getCheckRegistry().setEnabled(arg, check.isEmpty() || !check.get().isEnabled());
                });
                openChecks(player, 0);
            }
            case "player" -> {
                try { openPlayerDetail(player, UUID.fromString(arg)); } catch (IllegalArgumentException ignored) {}
            }
            case "reset_vl" -> {
                UUID target;
                try { target = UUID.fromString(arg); } catch (IllegalArgumentException ignored) { return; }
                anticheat().ifPresent(a -> a.getViolationManager().resetAll(target));
                Text.send(player, "<green>Violations reset.");
                openPlayerDetail(player, target);
            }
            case "watch" -> {
                UUID target;
                try { target = UUID.fromString(arg); } catch (IllegalArgumentException ignored) { return; }
                Player targetPlayer = Bukkit.getPlayer(target);
                if (targetPlayer != null) {
                    anticheat().ifPresent(a -> a.getWatchManager().toggleWatch(player, targetPlayer));
                }
                openPlayerDetail(player, target);
            }
            case "ban" -> {
                UUID target;
                try { target = UUID.fromString(arg); } catch (IllegalArgumentException ignored) { return; }
                Player targetPlayer = Bukkit.getPlayer(target);
                if (targetPlayer != null) {
                    openBanConfirmation(player, target);
                }
            }
            default -> { /* unknown action, ignore */ }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof LegendaryGuiHolder) event.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof LegendaryGuiHolder
            && event.getPlayer() instanceof Player player
            && !player.hasPermission("legendary.admin")) {
            actionCooldowns.remove(player.getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        actionCooldowns.remove(event.getPlayer().getUniqueId());
    }

    private void applyModuleToggle(Player actor, String moduleId, boolean enable) {
        plugin.getModuleManager().get(moduleId).ifPresent(module -> {
            if (enable) plugin.getModuleManager().enable(moduleId);
            else plugin.getModuleManager().disable(moduleId);
            plugin.getLogger().warning("[Admin Audit] " + actor.getUniqueId() + " "
                + (enable ? "enabled " : "disabled ") + "module " + moduleId + " via GUI.");
        });
    }

    private static int parsePage(String value) {
        try { return Math.max(0, Integer.parseInt(value)); }
        catch (NumberFormatException ignored) { return 0; }
    }
}
