package com.legendary.plugin.modules.anticheat;

import com.legendary.plugin.LegendaryPlugin;
import com.legendary.plugin.util.Text;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NEW FEATURE (2026-09-19 merge, scoped-down from the DeepSeek dump's
 * watch.WatchManager + commands.WatchCommand). Lets staff mark a specific
 * player as "watched": every subsequent flag on that player sends the
 * full raw detail string directly to the watcher(s), in addition to the
 * normal broadcast alert everyone with legendary.anticheat.alerts already
 * gets. Useful when investigating one suspected player closely without
 * turning on debug logging (which would spam detail for every player).
 */
public final class WatchManager implements Listener {

    private final LegendaryPlugin plugin;
    /** watched player uuid -> set of staff uuids watching them */
    private final Map<UUID, Set<UUID>> watchers = new ConcurrentHashMap<>();

    public WatchManager(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean toggleWatch(Player staff, Player target) {
        Set<UUID> set = watchers.computeIfAbsent(target.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
        boolean nowWatching;
        if (set.contains(staff.getUniqueId())) {
            set.remove(staff.getUniqueId());
            nowWatching = false;
        } else {
            set.add(staff.getUniqueId());
            nowWatching = true;
        }
        if (set.isEmpty()) watchers.remove(target.getUniqueId());
        return nowWatching;
    }

    public boolean isWatched(UUID playerUuid) {
        Set<UUID> set = watchers.get(playerUuid);
        return set != null && !set.isEmpty();
    }

    public boolean isWatching(Player staff, UUID target) {
        Set<UUID> set = watchers.get(target);
        return set != null && set.contains(staff.getUniqueId());
    }

    /** Called by AnticheatModule right after the normal alert broadcast. */
    public void notifyWatchers(Player flaggedPlayer, String checkDisplay, double vl, String detail) {
        Set<UUID> set = watchers.get(flaggedPlayer.getUniqueId());
        if (set == null || set.isEmpty()) return;
        var message = Text.of("<dark_gray>[<gold>Watch<dark_gray>] <yellow><player> <gray>» <white><check> "
            + "<gray>vl=<red><vl> <gray>detail=<white><detail>",
            Placeholder.unparsed("player", flaggedPlayer.getName()),
            Placeholder.unparsed("check", checkDisplay),
            Placeholder.unparsed("vl", String.format("%.1f", vl)),
            Placeholder.unparsed("detail", detail == null ? "" : detail));
        for (UUID watcherUuid : set) {
            Player watcher = Bukkit.getPlayer(watcherUuid);
            if (watcher != null && watcher.isOnline()) watcher.sendMessage(message);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        watchers.remove(uuid);
        for (Set<UUID> set : watchers.values()) set.remove(uuid);
    }

    public void clear() {
        watchers.clear();
    }

    public int getWatchCount() {
        return watchers.size();
    }
}
