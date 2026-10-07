package com.legendary.plugin.gui;

import com.legendary.plugin.LegendaryPlugin;
import com.legendary.plugin.core.Module;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Builds the per-module toggle icon for the admin GUI's Modules screen.
 * Now built on top of {@link GuiActionItem} (action "toggle_module:<id>")
 * so every clickable GUI item in the plugin shares one PDC identification
 * scheme instead of each screen inventing its own key.
 */
public final class ModuleToggleItem {

    private ModuleToggleItem() {}

    public static ItemStack build(LegendaryPlugin plugin, Module module) {
        boolean enabled = module.isEnabled();
        Material material = enabled ? Material.LIME_DYE : Material.GRAY_DYE;
        return GuiActionItem.build(material, "<white>" + module.displayName(),
            List.of(
                enabled ? "<green>Enabled - click to disable" : "<red>Disabled - click to enable",
                "<dark_gray>id: " + module.id()),
            "toggle_module:" + module.id());
    }

    /** Returns the module id if this item is a module-toggle button, else null. */
    public static String readModuleId(ItemStack item) {
        String action = GuiActionItem.readAction(item);
        if (action == null || !action.startsWith("toggle_module:")) return null;
        return action.substring("toggle_module:".length());
    }
}
