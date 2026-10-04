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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

    public LegendaryGuiListener(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    // ---- entry point ----

    public void open(Player player) {
        openMain(player);
    }

    private Inventory blank(int size, String title) {
        LegendaryGuiHolder holder = new LegendaryGuiHolder();
        Inventory inventory = Bukkit.createInventory(holder, size, Text.of(title));
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

    private void openModules(Player player) {
        Inventory inv = blank(27, "<gradient:#38BDF8:#A855F7>LegendaryPlugin</gradient> <gray>- Modules");
        inv.setItem(0, backButton("open:main"));
        int slot = 10;
        for (Module module : plugin.getModuleManager().all().values()) {
            if (slot >= 17) break;
            inv.setItem(slot++, ModuleToggleItem.build(plugin, module));
        }
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
        Inventory inv = blank(54, "<gradient:#38BDF8:#A855F7>LegendaryPlugin</gradient> <gray>- " + name);
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

    private void openChecks(Player player) {
        var anticheatOpt = anticheat();
        Inventory inv = blank(54, "<gradient:#38BDF8:#A855F7>LegendaryPlugin</gradient> <gray>- Checks");
        inv.setItem(0, backButton("open:main"));
        if (anticheatOpt.isEmpty()) {
            player.openInventory(inv);
            return;
        }
        int slot = 9;
        for (Check check : anticheatOpt.get().getCheckRegistry().all().values()) {
            if (slot >= 54) break;
            boolean enabled = check.isEnabled();
            inv.setItem(slot++, GuiActionItem.build(enabled ? Material.LIME_DYE : Material.GRAY_DYE,
                "<white>" + check.getDisplayName(),
                List.of(enabled ? "<green>Enabled - click to disable" : "<red>Disabled - click to enable"),
                "toggle_check:" + check.getId()));
        }
        player.openInventory(inv);
    }

    private ItemStack backButton(String action) {
        return GuiActionItem.build(Material.ARROW, "<gray>« Back", null, action);
    }

    private ItemStack playerHead(Player target, String action) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        if (item.getItemMeta() instanceof SkullMeta skullMeta) {
            skullMeta.setOwningPlayer(target);
            skullMeta.displayName(Text.of("<white>" + target.getName()));
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

        String action = GuiActionItem.readAction(event.getCurrentItem());
        if (action == null || action.equals("noop")) return;

        int colon = action.indexOf(':');
        String verb = colon < 0 ? action : action.substring(0, colon);
        String arg = colon < 0 ? "" : action.substring(colon + 1);

        switch (verb) {
            case "open" -> {
                switch (arg) {
                    case "main" -> openMain(player);
                    case "modules" -> openModules(player);
                    case "players" -> openPlayers(player);
                    case "checks" -> openChecks(player);
                }
            }
            case "toggle_module" -> {
                var module = plugin.getModuleManager().get(arg);
                if (module.isPresent()) {
                    if (module.get().isEnabled()) plugin.getModuleManager().disable(arg);
                    else plugin.getModuleManager().enable(arg);
                }
                openModules(player);
            }
            case "toggle_check" -> {
                anticheat().ifPresent(a -> {
                    var check = a.getCheckRegistry().get(arg);
                    a.getCheckRegistry().setEnabled(arg, check.isEmpty() || !check.get().isEnabled());
                });
                openChecks(player);
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
                    anticheat().ifPresent(a -> a.getBanExecutor().banDirect(targetPlayer, "Manually banned via GUI", "30d"));
                    player.closeInventory();
                }
            }
            default -> { /* unknown action, ignore */ }
        }
    }
}
