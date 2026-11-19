package dev.basefinder;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import java.util.UUID;

/**
 * Knows who "me" is, so the local player can never be reported as another player. Identity is cached by UUID and name,
 * so it survives death/respawn (the player object is replaced), dimension changes and reconnects. Until it has seen the
 * local player at least once it reports {@link #known()} == false and detection stays off (fail safe).
 */
final class LocalPlayerExclusion {
    private UUID uuid;
    private String name;

    void update(Minecraft mc) {
        if (mc.player != null) {
            uuid = mc.player.getUUID();
            name = mc.player.getName().getString();
        }
    }

    boolean known() {
        return uuid != null;
    }

    String name() {
        return name == null ? "Unknown" : name;
    }

    /** True for the local player entity, a respawned copy of it, or any entity with my UUID/name. */
    boolean isLocal(Minecraft mc, Entity e) {
        if (e == null) return false;
        if (mc.player != null && e == mc.player) return true;
        if (uuid != null && uuid.equals(e.getUUID())) return true;
        return name != null && name.equalsIgnoreCase(e.getName().getString());
    }
}
