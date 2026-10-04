package com.legendary.plugin.gui;

import com.legendary.plugin.util.Text;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/**
 * Generic clickable GUI item identified by a PDC action string (never by
 * display name/lore text), used by every LegendaryPlugin admin-GUI screen
 * beyond the original module-toggle row: navigation buttons, the player
 * list, the per-check toggle list, and player-detail action buttons.
 * Action string format is "<verb>:<argument>", e.g. "open:players",
 * "toggle_check:killaura", "player:<uuid>".
 */
public final class GuiActionItem {

    public static final NamespacedKey ACTION_KEY = new NamespacedKey("legendary", "gui_action");

    private GuiActionItem() {}

    public static ItemStack build(Material material, String displayMini, List<String> loreMini, String action) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Text.of(displayMini));
        if (loreMini != null && !loreMini.isEmpty()) {
            meta.lore(loreMini.stream().map(Text::of).toList());
        }
        meta.getPersistentDataContainer().set(ACTION_KEY, PersistentDataType.STRING, action);
        item.setItemMeta(meta);
        return item;
    }

    public static String readAction(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(ACTION_KEY, PersistentDataType.STRING);
    }
}
