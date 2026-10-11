package com.legendary.plugin.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;

/**
 * Central place for all player-facing text. Replaces every legacy
 * ChatColor / '&' / '§' usage across the three merged plugins with
 * Adventure {@link Component}s rendered through MiniMessage, per the
 * Paper 1.21.4 modernization requirement.
 */
public final class Text {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private Text() {}

    /** Parses a MiniMessage string, e.g. "<red>Hello <player>". */
    public static Component of(String miniMessage) {
        return MM.deserialize(miniMessage == null ? "" : miniMessage);
    }

    public static Component of(String miniMessage, TagResolver... resolvers) {
        return MM.deserialize(miniMessage == null ? "" : miniMessage, resolvers);
    }

    public static TagResolver placeholder(String key, Object value) {
        return Placeholder.unparsed(key, String.valueOf(value));
    }

    public static void send(CommandSender sender, String miniMessage, TagResolver... resolvers) {
        sender.sendMessage(of(miniMessage, resolvers));
    }

    public static String plain(Component component) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** Converts a legacy '&' coded string (still used in old admin configs) to a Component. */
    public static Component legacy(String legacyAmp) {
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand()
            .deserialize(legacyAmp == null ? "" : legacyAmp);
    }
}
