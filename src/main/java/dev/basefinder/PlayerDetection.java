package dev.basefinder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Alerts when another player is visible to the client. Never the local player. Per-player cooldown, no duplicates. */
final class PlayerDetection {
    private final Map<UUID, Long> lastAlert = new HashMap<>();
    private final Set<UUID> present = new HashSet<>();
    private int nearby;
    private long tick;

    int nearbyCount() {
        return nearby;
    }

    void reset() {
        lastAlert.clear();
        present.clear();
        nearby = 0;
    }

    void tick(Minecraft mc, ClientLevel level, Config cfg, LocalPlayerExclusion me) {
        tick++;
        if (tick % 10 != 0) return;
        if (!me.known()) { // fail safe: do not alert until we know who we are
            nearby = 0;
            return;
        }
        long now = System.currentTimeMillis();
        Set<UUID> current = new HashSet<>();
        int count = 0;
        for (Player p : level.players()) {
            if (me.isLocal(mc, p)) continue; // never myself
            if (cfg.playerAlertRadius > 0 && mc.player != null && mc.player.distanceTo(p) > cfg.playerAlertRadius) continue;
            UUID id = p.getUUID();
            current.add(id);
            count++;
            if (!cfg.playerAlerts) continue;
            Long last = lastAlert.get(id);
            boolean newlySeen = !present.contains(id);
            boolean cooledDown = last == null || now - last >= cfg.playerAlertCooldownSeconds * 1000L;
            if ((newlySeen || last == null) && cooledDown) {
                lastAlert.put(id, now);
                Alerts.player(mc, p.getName().getString(), p.blockPosition(), Scanner.dimKey(level));
            }
        }
        nearby = count;
        present.clear();
        present.addAll(current);
        // forget old cooldown entries so the map cannot grow forever
        long keep = Math.max(cfg.playerAlertCooldownSeconds, 60) * 1000L;
        lastAlert.values().removeIf(t -> now - t > keep);
    }
}
