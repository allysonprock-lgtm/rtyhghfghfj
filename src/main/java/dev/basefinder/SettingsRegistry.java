package dev.basefinder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Every setting shown in the menu, grouped into sections. Each row reads and writes the real {@link Config} field. */
final class SettingsRegistry {
    private SettingsRegistry() {}

    static final class Row {
        static final int BOOL = 0, INT = 1, DOUBLE = 2, CYCLE = 3, INFO = 4;
        final int kind;
        final String label, tip;
        BooleanSupplier getB;
        Consumer<Boolean> setB;
        IntSupplier getI;
        IntConsumer setI;
        int min, max, step;
        DoubleSupplier getD;
        DoubleConsumer setD;
        double dmin, dmax, dstep;
        Supplier<String> cycleLabel;
        Runnable cycleNext;

        Row(int kind, String label, String tip) {
            this.kind = kind;
            this.label = label;
            this.tip = tip;
        }
    }

    private static Config c() {
        return BaseFinderClient.config;
    }

    private static Row bool(String label, String tip, BooleanSupplier g, Consumer<Boolean> s) {
        Row r = new Row(Row.BOOL, label, tip);
        r.getB = g;
        r.setB = s;
        return r;
    }

    private static Row num(String label, String tip, IntSupplier g, IntConsumer s, int min, int max, int step) {
        Row r = new Row(Row.INT, label, tip);
        r.getI = g;
        r.setI = s;
        r.min = min;
        r.max = max;
        r.step = step;
        return r;
    }

    private static Row dbl(String label, String tip, DoubleSupplier g, DoubleConsumer s, double min, double max, double step) {
        Row r = new Row(Row.DOUBLE, label, tip);
        r.getD = g;
        r.setD = s;
        r.dmin = min;
        r.dmax = max;
        r.dstep = step;
        return r;
    }

    private static Row cycle(String label, String tip, Supplier<String> value, Runnable next) {
        Row r = new Row(Row.CYCLE, label, tip);
        r.cycleLabel = value;
        r.cycleNext = next;
        return r;
    }

    private static Row info(String label, String tip) {
        return new Row(Row.INFO, label, tip);
    }

    /** Section name -> rows. Discord has its own page, so it is not listed here. */
    static Map<String, List<Row>> build() {
        Map<String, List<Row>> m = new LinkedHashMap<>();

        m.put("General", new ArrayList<>(List.of(
                bool("Detection enabled", "Master switch for base detection (same as Pause/Resume).", () -> c().enabled, v -> c().enabled = v),
                num("Minimum strong storage", "Chests, barrels and shulker boxes a cluster needs before it is analysed at all.",
                        () -> c().minStrongStorage, v -> c().minStrongStorage = v, 1, 50, 1),
                num("Re-check interval (ticks)", "How often every chunk with storage is re-checked. 20 ticks = 1 second.",
                        () -> c().reevalIntervalTicks, v -> c().reevalIntervalTicks = v, 20, 1200, 20),
                num("Detections kept", "Maximum detections kept in the local history file.", () -> c().maxHistory, v -> c().maxHistory = v, 50, 20000, 250),
                num("Events kept", "Maximum player/pearl/death/base events kept in the local event history.",
                        () -> c().maxEventHistory, v -> c().maxEventHistory = v, 50, 5000, 50),
                bool("Save detections locally", "Write detections.json (rejected detections included).", () -> c().saveLocally, v -> c().saveLocally = v),
                bool("Notify on reclassification", "Send a Discord message when an already-reported base changes class (e.g. Intact to Griefed).",
                        () -> c().notifyReclassification, v -> c().notifyReclassification = v))));

        m.put("Detection", new ArrayList<>(List.of(
                cycle("Sensitivity preset", "Normal / High / Extreme. Rewrites the candidate thresholds below.",
                        () -> new String[]{"Normal", "High", "Extreme"}[Math.max(0, Math.min(2, c().sensitivity))],
                        () -> c().applyPreset((c().sensitivity + 1) % 3)),
                dbl("Minimum cluster score", "Weighted evidence (storage, workstations, beds...) needed inside one cluster.",
                        () -> c().minScore, v -> c().minScore = v, 1, 50, 0.5),
                num("Cluster radius", "Evidence within this many blocks counts as one cluster.", () -> c().clusterRadius, v -> c().clusterRadius = v, 4, 40, 1),
                bool("Wide scan", "Also look at the whole 3x3 chunk area to find spread-out bases.", () -> c().wideScan, v -> c().wideScan = v),
                num("Wide scan minimum storage", "Strong blocks needed in the 3x3 chunk area.", () -> c().wideMinStrong, v -> c().wideMinStrong = v, 3, 40, 1),
                dbl("Wide scan minimum score", "Weighted evidence needed in the 3x3 chunk area.", () -> c().wideMinScore, v -> c().wideMinScore = v, 3, 60, 1),
                num("Duplicate radius (chunks)", "A new find within this many chunks of a saved one is the same base.",
                        () -> c().dedupeChunkRadius, v -> c().dedupeChunkRadius = v, 0, 16, 1),
                dbl("Minimum base likelihood %", "Below this likelihood a candidate is rejected (only when enough area is observed).",
                        () -> c().minimumBaseLikelihood, v -> c().minimumBaseLikelihood = v, 0, 100, 5),
                bool("Skip villages (bells)", "Ignore clusters next to a village bell unless their score is very high.",
                        () -> c().suppressNearBell, v -> c().suppressNearBell = v))));

        m.put("Storage", new ArrayList<>(List.of(
                bool("Detect chests", "Count chests and trapped chests (a double chest is one unit).", () -> c().detectChests, v -> { c().detectChests = v; c().applyAndSave(); }),
                bool("Detect barrels", "Count barrels.", () -> c().detectBarrels, v -> { c().detectBarrels = v; c().applyAndSave(); }),
                bool("Detect shulker boxes", "Count shulker boxes of every colour.", () -> c().detectShulkers, v -> { c().detectShulkers = v; c().applyAndSave(); }),
                num("Cluster distance", "Storage units closer than this many blocks belong to the same storage cluster.",
                        () -> c().storageClusterDistance, v -> c().storageClusterDistance = v, 2, 16, 1),
                info("Inventory contents: unavailable", "The client only receives container contents when a container is opened. Base Finder never opens or "
                        + "interacts with containers, so contents are reported as unavailable."))));

        m.put("Filtering", new ArrayList<>(List.of(
                bool("Reject respawn anchors", "Reject bases with a respawn anchor.", () -> c().rejectRespawnAnchors, v -> c().rejectRespawnAnchors = v),
                bool("Reject isolated obsidian", "Reject bases with a lone obsidian block (no other obsidian within 2 blocks, no portal).",
                        () -> c().rejectIsolatedObsidian, v -> c().rejectIsolatedObsidian = v),
                bool("Reject live end crystals", "Reject bases with a live end crystal entity within the radius below.",
                        () -> c().excludeEndCrystals, v -> c().excludeEndCrystals = v),
                bool("Penalize end crystals", "Lower the base likelihood when end crystals are found (and never classify as Intact).",
                        () -> c().penalizeEndCrystals, v -> c().penalizeEndCrystals = v),
                bool("Detect explosion damage", "Look for crater-shaped pits next to building remains.", () -> c().detectExplosionDamage, v -> c().detectExplosionDamage = v),
                num("Crystal / anchor radius", "Blocks around the base searched for crystals and anchors.", () -> c().exclusionRadius, v -> c().exclusionRadius = v, 8, 128, 4),
                dbl("End crystal penalty", "Likelihood points removed per detection.", () -> c().endCrystalPenalty, v -> c().endCrystalPenalty = v, 0, 100, 5),
                dbl("Obsidian penalty", "Likelihood points removed when isolated obsidian is found.", () -> c().obsidianPenalty, v -> c().obsidianPenalty = v, 0, 100, 5),
                dbl("Respawn anchor penalty", "Likelihood points removed when a respawn anchor is found.", () -> c().anchorPenalty, v -> c().anchorPenalty = v, 0, 100, 5))));

        m.put("Condition", new ArrayList<>(List.of(
                cycle("Suspicious detection mode", "STRICT demotes borderline bases, LENIENT promotes them. Facts never change, only thresholds.",
                        () -> c().suspiciousDetectionMode, () -> {
                            String cur = c().suspiciousDetectionMode;
                            c().suspiciousDetectionMode = cur.equals("STRICT") ? "BALANCED" : cur.equals("BALANCED") ? "LENIENT" : "STRICT";
                        }),
                dbl("Intact: minimum likelihood %", "Base likelihood needed for the Intact channel.", () -> c().intactMinLikelihood, v -> c().intactMinLikelihood = v, 0, 100, 5),
                dbl("Minimum base quality", "Structure condition score needed for the Intact channel.", () -> c().minimumBaseQuality, v -> c().minimumBaseQuality = v, 0, 100, 5),
                dbl("Damage penalty multiplier", "Scales every damage indicator. 1.0 = default.", () -> c().damagePenalty, v -> c().damagePenalty = v, 0, 3, 0.1),
                dbl("Griefed: damage threshold", "Damage score needed (with enough independent indicators) for Likely Griefed.",
                        () -> c().griefedDamageThreshold, v -> c().griefedDamageThreshold = v, 30, 100, 5),
                num("Griefed: minimum indicators", "Independent damage indicators required for Likely Griefed.",
                        () -> c().griefedMinIndicators, v -> c().griefedMinIndicators = v, 1, 6, 1),
                dbl("Minimum observed area", "Share of the area that must be loaded before condition is judged.", () -> c().minCoverage, v -> c().minCoverage = v, 0.2, 0.95, 0.05),
                num("Crater depth", "How many blocks lower than its surroundings a pit must be to look like a crater.", () -> c().craterDepth, v -> c().craterDepth = v, 2, 10, 1),
                bool("Intact needs intact condition", "The Intact channel requires condition Intact/Mostly intact.", () -> c().requireIntactCheck, v -> c().requireIntactCheck = v))));

        m.put("Players", new ArrayList<>(List.of(
                bool("Player alerts", "TOP PRIORITY alert when another player is visible. Never your own character.", () -> c().playerAlerts, v -> c().playerAlerts = v),
                num("Cooldown per player (s)", "Seconds before the same player can trigger another alert.", () -> c().playerAlertCooldownSeconds, v -> c().playerAlertCooldownSeconds = v, 0, 3600, 30),
                num("Maximum distance (0 = any)", "Only alert for players closer than this many blocks.", () -> c().playerAlertRadius, v -> c().playerAlertRadius = v, 0, 512, 16))));

        m.put("Pearls", new ArrayList<>(List.of(
                bool("Ender pearl alerts", "TOP PRIORITY alert for live ender pearl projectiles (not dropped items).", () -> c().pearlAlerts, v -> c().pearlAlerts = v),
                num("Cooldown (s)", "Minimum seconds between pearl alerts.", () -> c().pearlAlertCooldownSeconds, v -> c().pearlAlertCooldownSeconds = v, 0, 60, 1),
                bool("Ignore my own pearls", "Do not alert for pearls you threw yourself.", () -> c().ignoreOwnPearls, v -> c().ignoreOwnPearls = v))));

        m.put("Death", new ArrayList<>(List.of(
                bool("Death notifications", "Send a notification when your own character dies.", () -> c().deathNotifications, v -> c().deathNotifications = v),
                num("Cooldown (s)", "Minimum seconds between death notifications.", () -> c().deathAlertCooldownSeconds, v -> c().deathAlertCooldownSeconds = v, 0, 600, 5))));

        m.put("Screenshots", new ArrayList<>(List.of(
                info("Not available in this build", "Real game screenshots were not implemented because the screenshot API for 26.1.2 could not be verified. "
                        + "Detections use generated floor-plan / 3D / isometric reconstructions instead, clearly labelled as reconstructions."),
                bool("Attach reconstructions to Discord", "Attach the generated PNG reconstructions to base reports.",
                        () -> c().attachReconstructions, v -> c().attachReconstructions = v))));

        m.put("Performance", new ArrayList<>(List.of(
                num("Chunk scans per tick", "How many chunks are scanned per game tick. Lower = smoother.", () -> c().scanBudget, v -> c().scanBudget = v, 1, 16, 1),
                num("Analysis interval (ticks)", "Minimum ticks between starting two area analyses.", () -> c().analysisIntervalTicks, v -> c().analysisIntervalTicks = v, 1, 100, 1),
                num("Analysis radius", "Blocks around a base copied for analysis.", () -> c().analysisRadius, v -> c().analysisRadius = v, 8, 48, 4),
                num("Re-analysis (s)", "Seconds before a saved base can be analysed again.", () -> c().reanalyzeSeconds, v -> c().reanalyzeSeconds = v, 30, 3600, 30),
                num("Rejected re-check (s)", "Seconds before a rejected detection is looked at again.", () -> c().rejectedRecheckSeconds, v -> c().rejectedRecheckSeconds = v, 30, 3600, 30),
                num("Map cache size", "Reconstructions kept in memory for the Map page.", () -> c().snapshotCacheSize, v -> c().snapshotCacheSize = v, 1, 40, 1))));

        m.put("Appearance", new ArrayList<>(List.of(
                bool("Falling snow", "Animated snowfall in the menu.", () -> c().snowAnimation, v -> c().snowAnimation = v),
                num("Snow density", "Number of snowflakes.", () -> c().snowDensity, v -> c().snowDensity = v, 0, 250, 10),
                bool("Animations", "Hover and transition effects. Turn off for a completely static menu.", () -> c().guiAnimations, v -> c().guiAnimations = v),
                bool("Twinkling stars", "Subtle star twinkle in the night sky.", () -> c().starTwinkle, v -> c().starTwinkle = v),
                bool("Chat alerts", "Show alerts in chat and the action bar.", () -> c().alertChat, v -> c().alertChat = v),
                bool("Sound alerts", "Play the alert sound (custom WAV or built-in).", () -> c().alertSound, v -> c().alertSound = v),
                dbl("Alert volume", "0.0 - 1.0", () -> c().soundVolume, v -> c().soundVolume = (float) v, 0, 1, 0.1),
                num("Alert cooldown (s)", "Minimum seconds between in-game chat/sound alerts for bases.", () -> c().alertCooldownSeconds, v -> c().alertCooldownSeconds = v, 0, 60, 1))));

        return m;
    }

    static String fmt(double v) {
        return Math.abs(v - Math.rint(v)) < 0.0001 ? String.valueOf((long) Math.rint(v)) : String.format(Locale.ROOT, "%.2f", v);
    }
}
