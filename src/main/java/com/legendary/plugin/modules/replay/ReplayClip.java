package com.legendary.plugin.modules.replay;

import java.util.List;
import java.util.UUID;

/** An immutable recorded clip: the last N seconds of a player's movement leading up to a check flag. */
public final class ReplayClip {

    public final UUID player;
    public final String playerName;
    public final String triggeringCheck;
    public final long recordedAt;
    public final List<ReplaySnapshot> snapshots;

    public ReplayClip(UUID player, String playerName, String triggeringCheck, long recordedAt, List<ReplaySnapshot> snapshots) {
        this.player = player;
        this.playerName = playerName;
        this.triggeringCheck = triggeringCheck;
        this.recordedAt = recordedAt;
        this.snapshots = List.copyOf(snapshots);
    }
}
