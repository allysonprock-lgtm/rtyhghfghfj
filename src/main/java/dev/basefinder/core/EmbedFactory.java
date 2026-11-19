package dev.basefinder.core;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Builds Discord webhook payloads (JSON strings). One factory per message type keeps routing and looks consistent. */
public final class EmbedFactory {
    private EmbedFactory() {}

    public static final int COLOR_INTACT = 0x6EE7B7;     // mint / ice green
    public static final int COLOR_SUSPICIOUS = 0xF59E0B; // amber
    public static final int COLOR_GRIEFED = 0xB91C1C;    // dark red
    public static final int COLOR_PLAYER = 0xFF5A1F;     // red-orange
    public static final int COLOR_PEARL = 0x9B59FF;      // purple
    public static final int COLOR_DEATH = 0x64748B;      // slate
    public static final int COLOR_TEST = 0x7DD3FC;       // ice blue

    private static final String LINK = "https://www.minecraft.net/";

    static String cut(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "\u2026";
    }

    private static Map<String, Object> field(String name, String value, boolean inline) {
        Map<String, Object> f = Json.obj();
        f.put("name", cut(name, 256));
        f.put("value", cut(value.isBlank() ? "-" : value, 1024));
        f.put("inline", inline);
        return f;
    }

    public static String coords(int x, int y, int z) {
        return "X " + x + " \u00B7 Y " + y + " \u00B7 Z " + z;
    }

    private static String otherDim(String dim, int x, int z) {
        if ("minecraft:overworld".equals(dim)) return "Nether: " + Math.floorDiv(x, 8) + ", " + Math.floorDiv(z, 8);
        if ("minecraft:the_nether".equals(dim)) return "Overworld: " + (x * 8) + ", " + (z * 8);
        return null;
    }

    public static String prettyDim(String dim) {
        String s = dim.replace("minecraft:", "").replace('_', ' ');
        if (s.startsWith("the ")) s = s.substring(4);
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String bullets(List<String> lines, int max) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            String t = l.startsWith("- ") ? l : "\u2022 " + l;
            if (sb.length() + t.length() + 1 > max) {
                sb.append("\u2026");
                break;
            }
            if (sb.length() > 0) sb.append('\n');
            sb.append(t);
        }
        return sb.toString();
    }

    private static String payload(String username, List<Object> embeds) {
        Map<String, Object> root = Json.obj();
        if (username != null && !username.isBlank()) root.put("username", username);
        root.put("embeds", embeds);
        Map<String, Object> allowed = Json.obj();
        allowed.put("parse", Json.arr()); // never ping anyone
        root.put("allowed_mentions", allowed);
        return Json.write(root);
    }

    // ------------------------------------------------------------------ base reports

    public static String baseReport(BaseReport r, String server, String username, String isoTime,
                                    boolean hasPlan, boolean hasVoxel, boolean hasIso, String changeNote) {
        int color;
        String title;
        switch (r.classification) {
            case INTACT -> {
                color = COLOR_INTACT;
                title = "\uD83E\uDDCA Intact base";
            }
            case GRIEFED -> {
                color = COLOR_GRIEFED;
                title = "\uD83D\uDD25 Likely griefed base";
            }
            default -> {
                color = COLOR_SUSPICIOUS;
                title = "\u26A0 Suspicious base";
            }
        }
        if (changeNote != null && !changeNote.isBlank()) title += " \u00B7 reclassified";
        title += " \u00B7 " + r.id;

        StorageReport s = r.storage;
        StringBuilder desc = new StringBuilder();
        desc.append("**").append(coords(r.x, r.y, r.z)).append("**\n").append(prettyDim(r.dimension));
        String other = otherDim(r.dimension, r.x, r.z);
        if (other != null) desc.append(" \u00B7 ").append(other);
        if (changeNote != null && !changeNote.isBlank()) desc.append("\n*").append(cut(changeNote, 200)).append('*');

        List<Object> fields = Json.arr();
        fields.add(field("Base likelihood", Math.round(r.likelihood) + "%", true));
        fields.add(field("Structure condition", r.condition.label + " \u00B7 " + Math.round(r.conditionScore) + "/100", true));
        fields.add(field("Functional infrastructure", Math.round(r.infraScore) + "/100", true));
        if (r.classification != BaseReport.BaseClass.INTACT || r.damageScore > 0) {
            fields.add(field("Damage score", Math.round(r.damageScore) + "/100 (" + r.indicators.size() + " indicators)", true));
        }
        fields.add(field("Storage",
                String.format(Locale.ROOT, "**%d units**\n%d chests (%d double) \u00B7 %d barrels \u00B7 %d shulkers",
                        s.total, s.chests, s.doubleChests, s.barrels, s.shulkers), true));
        if (s.largest != null) {
            fields.add(field("Storage clusters",
                    String.format(Locale.ROOT, "%d clusters \u00B7 %d isolated\nLargest: %d units at %d, %d, %d\nDensity %.1f per 100 blocks\u00B3",
                            s.clusters.size(), s.isolated, s.largest.count, s.largest.cx, s.largest.cy, s.largest.cz, s.densityOfLargest), true));
        }
        fields.add(field("Contents", "Inventory contents unavailable", true));

        if (r.classification == BaseReport.BaseClass.GRIEFED && !r.indicators.isEmpty()) {
            java.util.List<String> t = new java.util.ArrayList<>();
            for (BaseReport.Indicator i : r.indicators) t.add(i.text);
            fields.add(field("Damage indicators", bullets(t, 1000), false));
        }
        if (!r.suspicious.isEmpty()) {
            fields.add(field("Suspicious blocks", bullets(r.suspicious, 1000), false));
        }
        if (r.classification != BaseReport.BaseClass.INTACT) {
            fields.add(field("Why this channel", bullets(r.classReasons, 1000), false));
        }
        fields.add(field("Data quality",
                String.format(Locale.ROOT, "%d%% observed \u00B7 %d%% unknown/unloaded. Maps below are reconstructions, not screenshots.",
                        Math.round(r.coverage * 100), Math.round((1 - r.coverage) * 100)), false));

        Map<String, Object> embed = Json.obj();
        embed.put("title", cut(title, 256));
        embed.put("url", LINK);
        embed.put("description", cut(desc.toString(), 2000));
        embed.put("color", color);
        embed.put("fields", fields);
        if (hasPlan) embed.put("image", imageRef("plan.png"));
        Map<String, Object> footer = Json.obj();
        footer.put("text", cut("Base Finder \u00B7 " + r.id + (server != null && !server.isBlank() ? " \u00B7 " + server : ""), 2000));
        embed.put("footer", footer);
        embed.put("timestamp", isoTime);

        List<Object> embeds = Json.arr();
        embeds.add(embed);
        // Embeds that share the same url are shown as one gallery by Discord.
        if (hasVoxel) embeds.add(galleryEmbed(color, "voxel.png"));
        if (hasIso) embeds.add(galleryEmbed(color, "iso.png"));
        return payload(username, embeds);
    }

    private static Map<String, Object> imageRef(String name) {
        Map<String, Object> m = Json.obj();
        m.put("url", "attachment://" + name);
        return m;
    }

    private static Map<String, Object> galleryEmbed(int color, String file) {
        Map<String, Object> e = Json.obj();
        e.put("url", LINK);
        e.put("color", color);
        e.put("image", imageRef(file));
        return e;
    }

    // ------------------------------------------------------------------ top priority alerts

    public static String player(String name, int x, int y, int z, String dim, String isoTime, String server, String username) {
        List<Object> fields = Json.arr();
        fields.add(field("Player", name, true));
        fields.add(field("Coordinates", coords(x, y, z), true));
        fields.add(field("Dimension", prettyDim(dim), true));
        Map<String, Object> embed = priorityEmbed("\uD83D\uDEA8 TOP PRIORITY \u00B7 Player detected", COLOR_PLAYER,
                "Another player is within your loaded area.", fields, isoTime, server);
        return payload(username, one(embed));
    }

    public static String pearl(int x, int y, int z, String dim, String owner, String isoTime, String server, String username) {
        List<Object> fields = Json.arr();
        fields.add(field("Projectile position", coords(x, y, z), true));
        fields.add(field("Dimension", prettyDim(dim), true));
        fields.add(field("Thrown by", owner == null ? "Unknown (not available to the client)" : owner, true));
        Map<String, Object> embed = priorityEmbed("\uD83D\uDFE3 TOP PRIORITY \u00B7 Live ender pearl", COLOR_PEARL,
                "A live ender pearl projectile entity was observed (not an item).", fields, isoTime, server);
        return payload(username, one(embed));
    }

    public static String death(String name, Integer x, Integer y, Integer z, String dim, String message, String isoTime,
                               String server, String username) {
        List<Object> fields = Json.arr();
        fields.add(field("Player", name, true));
        fields.add(field("Death location", x == null ? "Unavailable" : coords(x, y, z), true));
        fields.add(field("Dimension", prettyDim(dim), true));
        fields.add(field("Death message", message == null || message.isBlank() ? "Not available to the client" : message, false));
        Map<String, Object> embed = priorityEmbed("\uD83D\uDC80 You died", COLOR_DEATH, "Death notification for your own character.",
                fields, isoTime, server);
        return payload(username, one(embed));
    }

    public static String test(String channelLabel, String isoTime, String username) {
        List<Object> fields = Json.arr();
        fields.add(field("Destination", channelLabel, true));
        fields.add(field("Status", "Delivery works", true));
        Map<String, Object> embed = priorityEmbed("\u2744 Base Finder test notification", COLOR_TEST,
                "If you can read this, this webhook is configured correctly.", fields, isoTime, null);
        return payload(username, one(embed));
    }

    private static List<Object> one(Map<String, Object> e) {
        List<Object> l = Json.arr();
        l.add(e);
        return l;
    }

    private static Map<String, Object> priorityEmbed(String title, int color, String desc, List<Object> fields,
                                                     String isoTime, String server) {
        Map<String, Object> embed = Json.obj();
        embed.put("title", title);
        embed.put("description", desc);
        embed.put("color", color);
        embed.put("fields", fields);
        Map<String, Object> footer = Json.obj();
        footer.put("text", "Base Finder" + (server != null && !server.isBlank() ? " \u00B7 " + server : ""));
        embed.put("footer", footer);
        embed.put("timestamp", isoTime);
        return embed;
    }
}
