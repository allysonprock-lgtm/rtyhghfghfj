package dev.basefinder;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/** Notifies when the local player dies. Observes only; never touches the death screen or respawning. */
final class DeathDetection {
    private boolean wasDead;
    private BlockPos lastAlivePos;
    private String lastAliveDim = "minecraft:overworld";
    private long lastDeathMs;

    void reset() {
        wasDead = false;
        lastAlivePos = null;
    }

    void tick(Minecraft mc, Config cfg, LocalPlayerExclusion me) {
        if (mc.player == null || mc.level == null) return;
        boolean dead = mc.player.isDeadOrDying();
        if (!dead) {
            lastAlivePos = mc.player.blockPosition();
            lastAliveDim = Scanner.dimKey(mc.level);
        } else if (!wasDead) { // rising edge = one death
            long now = System.currentTimeMillis();
            if (cfg.deathNotifications && now - lastDeathMs >= cfg.deathAlertCooldownSeconds * 1000L) {
                lastDeathMs = now;
                Alerts.death(mc, me.name(), lastAlivePos != null ? lastAlivePos : mc.player.blockPosition(), lastAliveDim);
            }
        }
        wasDead = dead;
    }
}
