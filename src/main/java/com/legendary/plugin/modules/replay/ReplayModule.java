package com.legendary.plugin.modules.replay;

import com.legendary.plugin.LegendaryPlugin;
import com.legendary.plugin.core.Module;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/** Aggregates replay recording + playback into one {@link Module}. */
public final class ReplayModule implements Module {

    private final LegendaryPlugin plugin;
    private final ReplayRecorder recorder;
    private final ReplayPlaybackManager playbackManager;
    private boolean enabled = false;

    public ReplayModule(LegendaryPlugin plugin) {
        this.plugin = plugin;
        this.recorder = new ReplayRecorder(plugin);
        this.playbackManager = new ReplayPlaybackManager(plugin);
    }

    @Override public String id() { return "replay"; }
    @Override public String displayName() { return "Replay Recording"; }
    @Override public int priority() { return 30; }
    @Override public boolean isEnabled() { return enabled; }

    @Override
    public void onEnable() {
        recorder.loadConfigValues();
        recorder.start();
        enabled = true;
    }

    @Override
    public void onDisable() {
        recorder.stop();
        playbackManager.stopAll();
        enabled = false;
    }

    public void saveClipOnFlag(Player player, String checkId) {
        recorder.saveClip(player, checkId);
    }

    public List<ReplayClip> getClips(UUID player) { return recorder.getClips(player); }
    public ReplayPlaybackManager getPlaybackManager() { return playbackManager; }
}
