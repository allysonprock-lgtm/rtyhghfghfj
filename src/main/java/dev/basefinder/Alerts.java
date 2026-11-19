package dev.basefinder;

import dev.basefinder.core.EmbedFactory;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/** In-game messages and sounds, plus the glue that sends the TOP PRIORITY alerts to their own Discord channels. */
final class Alerts {
    private static long lastLocalAlert;
    private static final Set<Channel> hinted = new HashSet<>();

    private Alerts() {}

    static String prettyDim(String dim) {
        return EmbedFactory.prettyDim(dim);
    }

    private static void say(Minecraft mc, Component c, boolean actionBar) {
        if (mc.player == null) return;
        if (actionBar) mc.player.sendOverlayMessage(c); // action bar
        else mc.player.sendSystemMessage(c);           // chat
    }

    private static MutableComponent bar(ChatFormatting color) {
        return Component.literal("\u258C ").withStyle(color);
    }

    // ------------------------------------------------------------------ base reports

    static void baseFound(Minecraft mc, DetectionStore.Detection d, String note) {
        Config cfg = BaseFinderClient.config;
        long now = System.currentTimeMillis();
        if (now - lastLocalAlert < cfg.alertCooldownSeconds * 1000L) return;
        lastLocalAlert = now;
        ChatFormatting color = switch (d.classOrLegacy()) {
            case "INTACT" -> ChatFormatting.GREEN;
            case "GRIEFED" -> ChatFormatting.RED;
            default -> ChatFormatting.GOLD;
        };
        String label = switch (d.classOrLegacy()) {
            case "INTACT" -> "INTACT BASE";
            case "GRIEFED" -> "LIKELY GRIEFED BASE";
            default -> "SUSPICIOUS BASE";
        };
        if (cfg.alertChat && mc.player != null) {
            say(mc, bar(color).append(Component.literal(label).withStyle(color, ChatFormatting.BOLD))
                    .append(Component.literal("  " + prettyDim(d.dimension) + "  " + d.id).withStyle(ChatFormatting.GRAY)), false);
            say(mc, bar(color).append(Component.literal("XYZ ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(d.x + ", " + d.y + ", " + d.z).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD)), false);
            say(mc, bar(color).append(Component.literal("likelihood " + d.likelihood + "% \u00B7 " + d.condition
                    + " \u00B7 " + d.totalStorage + " storage").withStyle(ChatFormatting.DARK_GRAY)), false);
            if (note != null) say(mc, bar(color).append(Component.literal(note).withStyle(ChatFormatting.DARK_GRAY)), false);
            say(mc, Component.literal(label + ": " + d.x + ", " + d.y + ", " + d.z).withStyle(color), true);
        }
        if (cfg.alertSound) SoundPlayer.play(mc, cfg);
    }

    /** Test button in the menu: in-game message + sound, no Discord (Discord has its own per-channel test). */
    static void testAlert(Minecraft mc) {
        Config cfg = BaseFinderClient.config;
        BlockPos p = mc.player != null ? mc.player.blockPosition() : BlockPos.ZERO;
        if (cfg.alertChat && mc.player != null) {
            say(mc, bar(ChatFormatting.AQUA).append(Component.literal("TEST ALERT").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD))
                    .append(Component.literal("  " + p.getX() + ", " + p.getY() + ", " + p.getZ()).withStyle(ChatFormatting.GRAY)), false);
        }
        if (cfg.alertSound) SoundPlayer.play(mc, cfg);
    }

    static void channelNotConfigured(Minecraft mc, Channel ch) {
        if (!hinted.add(ch)) return; // once per game session per channel
        say(mc, Component.literal("[BaseFinder] Discord channel '" + ch.label + "' is not configured or disabled. The report was kept locally. "
                + "Open the Base Finder menu > Discord.").withStyle(ChatFormatting.YELLOW), false);
    }

    // ------------------------------------------------------------------ TOP PRIORITY alerts (own channels)

    private static String serverOrNull(Minecraft mc, Config cfg) {
        return cfg.includeServerAddress ? Scanner.serverKey(mc) : null;
    }

    static void player(Minecraft mc, String name, BlockPos pos, String dim) {
        Config cfg = BaseFinderClient.config;
        BaseFinderClient.history.add("PLAYER", dim, pos.getX(), pos.getY(), pos.getZ(), name, null, cfg.maxEventHistory);
        priorityChat(mc, "PLAYER DETECTED", name + " at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + " (" + prettyDim(dim) + ")",
                ChatFormatting.RED);
        BaseFinderClient.discord.send(Channel.PLAYER, EmbedFactory.player(name, pos.getX(), pos.getY(), pos.getZ(), dim,
                Instant.now().toString(), serverOrNull(mc, cfg), cfg.discordUsername), null);
    }

    static void pearl(Minecraft mc, BlockPos pos, String dim, String owner) {
        Config cfg = BaseFinderClient.config;
        BaseFinderClient.history.add("PEARL", dim, pos.getX(), pos.getY(), pos.getZ(), "Live ender pearl",
                owner == null ? null : "thrown by " + owner, cfg.maxEventHistory);
        priorityChat(mc, "LIVE ENDER PEARL", pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + " (" + prettyDim(dim) + ")",
                ChatFormatting.LIGHT_PURPLE);
        BaseFinderClient.discord.send(Channel.PEARL, EmbedFactory.pearl(pos.getX(), pos.getY(), pos.getZ(), dim, owner,
                Instant.now().toString(), serverOrNull(mc, cfg), cfg.discordUsername), null);
    }

    static void death(Minecraft mc, String name, BlockPos pos, String dim) {
        Config cfg = BaseFinderClient.config;
        Integer x = pos == null ? null : pos.getX(), y = pos == null ? null : pos.getY(), z = pos == null ? null : pos.getZ();
        BaseFinderClient.history.add("DEATH", dim, x, y, z, name + " died", null, cfg.maxEventHistory);
        BaseFinderClient.history.flushIfDue(true);
        BaseFinderClient.discord.send(Channel.DEATH, EmbedFactory.death(name, x, y, z, dim, null, Instant.now().toString(),
                serverOrNull(mc, cfg), cfg.discordUsername), null);
    }

    private static void priorityChat(Minecraft mc, String title, String detail, ChatFormatting color) {
        Config cfg = BaseFinderClient.config;
        if (cfg.alertChat) {
            say(mc, bar(color).append(Component.literal("TOP PRIORITY \u00B7 " + title).withStyle(color, ChatFormatting.BOLD)), false);
            say(mc, bar(color).append(Component.literal(detail).withStyle(ChatFormatting.WHITE)), false);
            say(mc, Component.literal(title + ": " + detail).withStyle(color), true);
        }
        if (cfg.alertSound) SoundPlayer.play(mc, cfg);
    }
}
