package dev.basefinder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Alerts for LIVE ender pearl projectile entities only. Dropped pearls are item entities (a different entity type),
 * and pearls in inventories or chests are not entities at all, so none of those can trigger it.
 */
final class EnderPearlDetection {
    private static final String PEARL = "minecraft:ender_pearl";

    /** projectile UUID -> tick it was last seen (used for cleanup and to prevent duplicate alerts) */
    private final Map<UUID, Long> tracked = new HashMap<>();
    private long tick;
    private long lastAlertMs;

    int tracked() {
        return tracked.size();
    }

    void reset() {
        tracked.clear();
    }

    void tick(Minecraft mc, ClientLevel level, Config cfg, LocalPlayerExclusion me) {
        tick++;
        if (tick % 2 != 0) return;
        for (Entity e : EntityView.all(level)) {
            if (!PEARL.equals(EntityView.typeId(e))) continue;
            UUID id = e.getUUID();
            boolean first = !tracked.containsKey(id);
            tracked.put(id, tick);
            if (!first || !cfg.pearlAlerts) continue;

            String owner = null;
            boolean mine = false;
            if (e instanceof Projectile proj) {
                Entity o = proj.getOwner();
                if (o != null) {
                    owner = o.getName().getString();
                    mine = me.isLocal(mc, o);
                }
            }
            if (mine && cfg.ignoreOwnPearls) continue;
            long now = System.currentTimeMillis();
            if (now - lastAlertMs < cfg.pearlAlertCooldownSeconds * 1000L) continue;
            lastAlertMs = now;
            Alerts.pearl(mc, e.blockPosition(), Scanner.dimKey(level), owner);
        }
        // clean up pearls that disappeared or unloaded (kept ~10 s so a flicker cannot cause a duplicate alert)
        final long cutoff = tick - 200;
        tracked.values().removeIf(seen -> seen < cutoff);
    }
}
