package dev.basefinder;

import dev.basefinder.core.Params;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Settings, stored in config/basefinder/config.json. Changes made in the menu are saved automatically, and edits to
 * the file are picked up while the game runs. Older config files are migrated (and backed up) on first load.
 * This class is a plain data holder; all JSON work lives in {@link Io} so the class can be used without Gson.
 */
public final class Config {
    /** Test hook: when set, files live here instead of the Fabric config directory. */
    public static Path dirOverride;

    public static Path dir() {
        return dirOverride != null ? dirOverride : FabricLoader.getInstance().getConfigDir().resolve("basefinder");
    }

    public static Path file() {
        return dir().resolve("config.json");
    }

    public static final int CURRENT_VERSION = 3;

    /** One Discord destination. */
    public static final class Webhook {
        public boolean enabled = true;
        public String url = "";

        public Webhook() {}

        public Webhook(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /** Internal: used to migrate older config files. */
    public int version = 0;
    /** Shown in the menu after migrating an older config that had a single webhook. Cleared once channels are set. */
    public String migrationNotice = "";

    // ---------------------------------------------------------------- general / detection (existing settings kept)
    public boolean enabled = true;
    /** 0 = Normal, 1 = High, 2 = Extreme. Rewrites the detection numbers below when changed in the menu. */
    public int sensitivity = 1;
    public int minStrongStorage = 4;
    public double minScore = 5.0;
    public int clusterRadius = 16;
    public boolean wideScan = true;
    public int wideMinStrong = 8;
    public double wideMinScore = 12.0;
    public int dedupeChunkRadius = 4;

    /** Legacy (v1.2). Kept for compatibility; used as "require intact condition for the Intact channel". */
    public boolean requireIntactCheck = true;
    public double minSupportedRatio = 0.5;
    public double minAvgEnclosure = 1.5;

    /** Hard rule: skip bases with a live end crystal within exclusionRadius blocks. */
    public boolean excludeEndCrystals = true;
    /** Legacy (v1.2) name for rejectRespawnAnchors. Copied once during migration; rejectRespawnAnchors is used now. */
    public boolean excludeRespawnAnchors = true;
    public int exclusionRadius = 48;

    public boolean suppressNearBell = true;
    public int bellRadius = 48;
    public double bellBypassScore = 25.0;

    public Map<String, Double> blockWeights = defaultBlockWeights();
    public Map<String, Double> entityWeights = defaultEntityWeights();
    public List<String> dimensions = new ArrayList<>(List.of(
            "minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"));
    public boolean saveLocally = true;

    // ---------------------------------------------------------------- storage
    public boolean detectChests = true;
    public boolean detectBarrels = true;
    public boolean detectShulkers = true;
    /** Storage blocks closer than this (blocks) belong to the same storage cluster. */
    public int storageClusterDistance = 6;

    // ---------------------------------------------------------------- filtering (spec names)
    public boolean rejectRespawnAnchors = true;
    public boolean rejectIsolatedObsidian = true;
    public boolean penalizeEndCrystals = true;
    public boolean detectExplosionDamage = true;
    public double endCrystalPenalty = 35;
    public double obsidianPenalty = 40;
    public double anchorPenalty = 60;

    // ---------------------------------------------------------------- scoring / classification
    public double minimumBaseLikelihood = 30;
    public double intactMinLikelihood = 55;
    public double minimumBaseQuality = 55;
    public double damagePenalty = 1.0;
    public double griefedDamageThreshold = 60;
    public int griefedMinIndicators = 3;
    public double minCoverage = 0.5;
    public double minCoverageForGriefed = 0.6;
    public int craterDepth = 3;
    /** STRICT, BALANCED or LENIENT. */
    public String suspiciousDetectionMode = "BALANCED";
    public boolean notifyReclassification = false;
    public double weightStorage = 30, weightConcentration = 15, weightInfra = 15, weightFarm = 5,
            weightEnclosure = 15, weightUnderground = 5, weightBuilt = 10, weightConnected = 5;

    // ---------------------------------------------------------------- area analysis
    public int analysisRadius = 24;
    public int analysisBelow = 16;
    public int analysisAbove = 24;
    public int reanalyzeSeconds = 300;
    public int rejectedRecheckSeconds = 120;

    // ---------------------------------------------------------------- entities / top priority
    public boolean playerAlerts = true;
    public int playerAlertCooldownSeconds = 120;
    /** 0 = any distance the client can see. */
    public int playerAlertRadius = 0;
    public boolean pearlAlerts = true;
    public int pearlAlertCooldownSeconds = 2;
    public boolean ignoreOwnPearls = true;
    public boolean deathNotifications = true;
    public int deathAlertCooldownSeconds = 10;

    // ---------------------------------------------------------------- discord
    /** Legacy single webhook from v1.1/v1.2. Never deleted and never used for routing. */
    public String discordWebhookUrl = "";
    public String discordUsername = "Base Finder";
    public Webhook intactBasesWebhook = new Webhook();
    public Webhook suspiciousBasesWebhook = new Webhook();
    public Webhook griefedBasesWebhook = new Webhook();
    public Webhook playerAlertsWebhook = new Webhook();
    public Webhook enderPearlAlertsWebhook = new Webhook();
    public Webhook deathAlertsWebhook = new Webhook();
    /** Used for player/pearl/death alerts only when their own webhook is not configured. Disabled until a URL is set. */
    public Webhook priorityFallbackWebhook = new Webhook(false);
    public boolean attachReconstructions = true;
    public String apiEndpointUrl = "";
    public String apiAuthHeader = "";
    public boolean includeServerAddress = true;

    // ---------------------------------------------------------------- in-game alerts
    public int alertCooldownSeconds = 3;
    public boolean alertChat = true;
    public boolean alertSound = true;
    public String customSoundFile = "alert.wav";
    public float soundVolume = 1.0f;
    public boolean sendTestOnJoin = false;

    // ---------------------------------------------------------------- visuals / performance
    public boolean snowAnimation = true;
    public boolean guiAnimations = true;
    public int snowDensity = 90;
    public boolean starTwinkle = true;
    public int scanBudget = 3;
    public int analysisIntervalTicks = 10;
    public int reevalIntervalTicks = 200;
    public int snapshotCacheSize = 12;
    public int maxHistory = 2000;
    public int maxEventHistory = 1000;

    private transient Map<String, Double> exactBlock = new HashMap<>();
    private transient List<Map.Entry<String, Double>> suffixBlock = new ArrayList<>();
    private transient Map<String, Double> blockCache = new HashMap<>();
    private transient Set<String> dimSet = new HashSet<>();
    private transient long loadedStamp;

    // ================================================================ defaults

    private static Map<String, Double> defaultBlockWeights() {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("minecraft:chest", 1.0);
        m.put("minecraft:trapped_chest", 1.0);
        m.put("minecraft:barrel", 1.0);
        m.put("minecraft:shulker_box", 1.0);
        m.put("*_shulker_box", 1.0);
        m.put("minecraft:ender_chest", 0.5);
        m.put("minecraft:hopper", 0.5);
        m.put("minecraft:dropper", 0.4);
        m.put("minecraft:dispenser", 0.4);
        m.put("minecraft:crafter", 0.4);
        m.put("minecraft:furnace", 0.4);
        m.put("minecraft:blast_furnace", 0.4);
        m.put("minecraft:smoker", 0.4);
        m.put("minecraft:brewing_stand", 0.5);
        m.put("minecraft:enchanting_table", 0.6);
        m.put("minecraft:beacon", 3.0);
        m.put("minecraft:conduit", 1.5);
        m.put("minecraft:lectern", 0.3);
        m.put("minecraft:jukebox", 0.3);
        m.put("minecraft:chiseled_bookshelf", 0.3);
        m.put("*_bed", 0.6);
        m.put("*_banner", 0.15);
        return m;
    }

    private static Map<String, Double> defaultEntityWeights() {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("minecraft:armor_stand", 0.4);
        m.put("minecraft:item_frame", 0.25);
        m.put("minecraft:glow_item_frame", 0.25);
        return m;
    }

    // ================================================================ lookups

    /** Weight of a block id for the candidate stage (0 if it is not a signal). Honours the storage toggles. */
    public double blockWeight(String id) {
        Double cached = blockCache.get(id);
        if (cached != null) return cached;
        double w = 0.0;
        Double exact = exactBlock.get(id);
        if (exact != null) {
            w = exact;
        } else {
            for (Map.Entry<String, Double> e : suffixBlock) {
                if (id.endsWith(e.getKey())) {
                    w = e.getValue();
                    break;
                }
            }
        }
        if (!detectChests && (id.equals("minecraft:chest") || id.equals("minecraft:trapped_chest"))) w = 0;
        if (!detectBarrels && id.equals("minecraft:barrel")) w = 0;
        if (!detectShulkers && id.endsWith("shulker_box")) w = 0;
        blockCache.put(id, w);
        return w;
    }

    public double entityWeight(String id) {
        Double w = entityWeights.get(id);
        return w == null ? 0.0 : w;
    }

    public boolean watchesDimension(String dim) {
        return dimSet.contains(dim);
    }

    public Webhook webhook(Channel c) {
        return switch (c) {
            case INTACT -> intactBasesWebhook;
            case SUSPICIOUS -> suspiciousBasesWebhook;
            case GRIEFED -> griefedBasesWebhook;
            case PLAYER -> playerAlertsWebhook;
            case PEARL -> enderPearlAlertsWebhook;
            case DEATH -> deathAlertsWebhook;
            case FALLBACK -> priorityFallbackWebhook;
        };
    }

    public boolean anyNewWebhookConfigured() {
        for (Channel c : Channel.values()) {
            Webhook w = webhook(c);
            if (w != null && w.url != null && !w.url.isBlank()) return true;
        }
        return false;
    }

    /** Copies the numbers the analysis core needs. Call on the game thread; the copy can be used anywhere. */
    public Params toParams() {
        Params p = new Params();
        p.clusterDistance = storageClusterDistance;
        p.wStorage = weightStorage; p.wConcentration = weightConcentration; p.wInfra = weightInfra; p.wFarm = weightFarm;
        p.wEnclosure = weightEnclosure; p.wUnderground = weightUnderground; p.wBuilt = weightBuilt; p.wConnected = weightConnected;
        p.rejectEndCrystals = excludeEndCrystals;
        p.penalizeEndCrystals = penalizeEndCrystals;
        p.endCrystalPenalty = endCrystalPenalty;
        p.crystalRadius = exclusionRadius;
        p.rejectRespawnAnchors = rejectRespawnAnchors;
        p.anchorPenalty = anchorPenalty;
        p.rejectIsolatedObsidian = rejectIsolatedObsidian;
        p.obsidianPenalty = obsidianPenalty;
        p.detectExplosionDamage = detectExplosionDamage;
        p.craterDepth = craterDepth;
        p.damagePenalty = damagePenalty;
        p.griefedDamageThreshold = griefedDamageThreshold;
        p.griefedMinIndicators = griefedMinIndicators;
        p.minCoverageForGriefed = minCoverageForGriefed;
        p.minimumBaseLikelihood = minimumBaseLikelihood;
        p.intactMinLikelihood = intactMinLikelihood;
        p.minimumBaseQuality = minimumBaseQuality;
        p.minCoverage = minCoverage;
        p.requireIntactCondition = requireIntactCheck;
        p.mode = suspiciousDetectionMode;
        return p;
    }

    /** Applies one of the sensitivity presets (0 normal, 1 high, 2 extreme) to the candidate-stage numbers. */
    public void applyPreset(int level) {
        sensitivity = Math.max(0, Math.min(2, level));
        switch (sensitivity) {
            case 0 -> {
                minStrongStorage = 6; minScore = 8.0; clusterRadius = 12;
                wideMinStrong = 12; wideMinScore = 18.0; minSupportedRatio = 0.6; minAvgEnclosure = 2.0;
            }
            case 1 -> {
                minStrongStorage = 4; minScore = 5.0; clusterRadius = 16;
                wideMinStrong = 8; wideMinScore = 12.0; minSupportedRatio = 0.5; minAvgEnclosure = 1.5;
            }
            default -> {
                minStrongStorage = 3; minScore = 3.5; clusterRadius = 20;
                wideMinStrong = 6; wideMinScore = 8.0; minSupportedRatio = 0.4; minAvgEnclosure = 1.0;
            }
        }
    }

    // ================================================================ validation + migration

    /** Fills missing objects, clamps invalid numbers and rebuilds the lookup caches. */
    public void rebuild() {
        if (blockWeights == null) blockWeights = defaultBlockWeights();
        if (entityWeights == null) entityWeights = defaultEntityWeights();
        if (dimensions == null) dimensions = new ArrayList<>(List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"));
        if (intactBasesWebhook == null) intactBasesWebhook = new Webhook();
        if (suspiciousBasesWebhook == null) suspiciousBasesWebhook = new Webhook();
        if (griefedBasesWebhook == null) griefedBasesWebhook = new Webhook();
        if (playerAlertsWebhook == null) playerAlertsWebhook = new Webhook();
        if (enderPearlAlertsWebhook == null) enderPearlAlertsWebhook = new Webhook();
        if (deathAlertsWebhook == null) deathAlertsWebhook = new Webhook();
        if (priorityFallbackWebhook == null) priorityFallbackWebhook = new Webhook(false);
        for (Channel c : Channel.values()) {
            Webhook w = webhook(c);
            if (w.url == null) w.url = "";
            w.url = w.url.trim();
        }
        if (discordWebhookUrl == null) discordWebhookUrl = "";
        if (migrationNotice == null) migrationNotice = "";
        if (discordUsername == null || discordUsername.isBlank()) discordUsername = "Base Finder";
        if (suspiciousDetectionMode == null) suspiciousDetectionMode = "BALANCED";
        suspiciousDetectionMode = suspiciousDetectionMode.trim().toUpperCase(java.util.Locale.ROOT);
        if (!suspiciousDetectionMode.equals("STRICT") && !suspiciousDetectionMode.equals("LENIENT")) suspiciousDetectionMode = "BALANCED";

        exactBlock = new HashMap<>();
        suffixBlock = new ArrayList<>();
        blockCache = new HashMap<>();
        for (Map.Entry<String, Double> e : blockWeights.entrySet()) {
            if (e.getKey().startsWith("*")) suffixBlock.add(Map.entry(e.getKey().substring(1), e.getValue()));
            else exactBlock.put(e.getKey(), e.getValue());
        }
        dimSet = new HashSet<>(dimensions);

        minStrongStorage = clampI(minStrongStorage, 1, 500);
        clusterRadius = clampI(clusterRadius, 1, 64);
        dedupeChunkRadius = clampI(dedupeChunkRadius, 0, 64);
        exclusionRadius = clampI(exclusionRadius, 1, 256);
        storageClusterDistance = clampI(storageClusterDistance, 1, 32);
        analysisRadius = clampI(analysisRadius, 8, 48);
        analysisBelow = clampI(analysisBelow, 4, 48);
        analysisAbove = clampI(analysisAbove, 4, 64);
        craterDepth = clampI(craterDepth, 2, 12);
        griefedMinIndicators = clampI(griefedMinIndicators, 1, 8);
        reanalyzeSeconds = clampI(reanalyzeSeconds, 10, 86400);
        rejectedRecheckSeconds = clampI(rejectedRecheckSeconds, 10, 86400);
        playerAlertCooldownSeconds = clampI(playerAlertCooldownSeconds, 0, 86400);
        playerAlertRadius = clampI(playerAlertRadius, 0, 512);
        pearlAlertCooldownSeconds = clampI(pearlAlertCooldownSeconds, 0, 3600);
        deathAlertCooldownSeconds = clampI(deathAlertCooldownSeconds, 0, 3600);
        alertCooldownSeconds = clampI(alertCooldownSeconds, 0, 3600);
        snowDensity = clampI(snowDensity, 0, 250);
        scanBudget = clampI(scanBudget, 1, 32);
        analysisIntervalTicks = clampI(analysisIntervalTicks, 1, 200);
        reevalIntervalTicks = clampI(reevalIntervalTicks, 20, 6000);
        snapshotCacheSize = clampI(snapshotCacheSize, 1, 64);
        maxHistory = clampI(maxHistory, 50, 20000);
        maxEventHistory = clampI(maxEventHistory, 50, 20000);
        minimumBaseLikelihood = clampD(minimumBaseLikelihood, 0, 100);
        intactMinLikelihood = clampD(intactMinLikelihood, 0, 100);
        minimumBaseQuality = clampD(minimumBaseQuality, 0, 100);
        damagePenalty = clampD(damagePenalty, 0, 5);
        griefedDamageThreshold = clampD(griefedDamageThreshold, 1, 100);
        minCoverage = clampD(minCoverage, 0, 1);
        minCoverageForGriefed = clampD(minCoverageForGriefed, 0, 1);
        soundVolume = Math.max(0f, Math.min(1f, soundVolume));
    }

    private static int clampI(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double clampD(double v, double lo, double hi) {
        if (Double.isNaN(v)) return lo;
        return Math.max(lo, Math.min(hi, v));
    }

    /**
     * Brings an older config up to the current layout WITHOUT deleting anything: old fields stay in the file, the old
     * single webhook is preserved but never used for routing, and a notice explains what to configure.
     * @return true if anything changed
     */
    public boolean migrate() {
        if (version >= CURRENT_VERSION) return false;
        if (version < 2) applyPreset(1);                 // v1.1 -> v1.2 numbers
        rejectRespawnAnchors = excludeRespawnAnchors;    // v1.2 name -> spec name
        rebuild();
        if (!discordWebhookUrl.isBlank() && !anyNewWebhookConfigured()) {
            migrationNotice = "Your old single Discord webhook was kept but is NOT used any more. Open the menu > Discord and "
                    + "set the Intact, Suspicious and Griefed channels (you can import the old webhook into any one of them).";
        }
        version = CURRENT_VERSION;
        return true;
    }

    // ================================================================ I/O

    public static Config load() {
        return Io.load();
    }

    /**
     * Live reload while the game runs. If the file cannot be parsed (for example it is half-edited), the CURRENT
     * settings are kept and the file is left exactly as the user wrote it. Returns the config to use.
     */
    public static Config reload(Config current) {
        Config fresh = Io.tryRead();
        if (fresh == null) {
            current.markSynced(); // do not retry every few seconds
            BaseFinderClient.LOG.warn("[BaseFinder] config.json could not be read (invalid JSON?). Keeping the current settings; the file was not touched.");
            return current;
        }
        fresh.migrate();
        fresh.rebuild();
        fresh.markSynced();
        return fresh;
    }

    public void applyAndSave() {
        rebuild();
        save();
    }

    public void save() {
        Io.save(this);
    }

    /** True if the file on disk changed since we last loaded/saved it. */
    public boolean changedOnDisk() {
        try {
            return Files.exists(file()) && Files.getLastModifiedTime(file()).toMillis() != loadedStamp;
        } catch (IOException e) {
            return false;
        }
    }

    void markSynced() {
        try {
            loadedStamp = Files.getLastModifiedTime(file()).toMillis();
        } catch (IOException ignored) {
            loadedStamp = 0;
        }
    }

    /** All Gson work. Loaded lazily so the rest of the class has no JSON dependency. */
    private static final class Io {
        private static final com.google.gson.Gson GSON = new com.google.gson.GsonBuilder().setPrettyPrinting().create();

        /** Parses the file without side effects. Null if missing or invalid. */
        static Config tryRead() {
            try {
                if (!Files.exists(file())) return null;
                return GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8), Config.class);
            } catch (Exception e) {
                return null;
            }
        }

        static Config load() {
            Config cfg = null;
            String raw = null;
            boolean existed = false;
            try {
                Files.createDirectories(dir());
                if (Files.exists(file())) {
                    existed = true;
                    raw = Files.readString(file(), StandardCharsets.UTF_8);
                    cfg = GSON.fromJson(raw, Config.class);
                }
            } catch (Exception e) {
                BaseFinderClient.LOG.warn("[BaseFinder] Could not read config ({}); a backup is kept and defaults are used", e.toString());
                backup(raw, "unreadable");
            }
            if (cfg == null) cfg = new Config();
            int oldVersion = cfg.version;
            if (existed && oldVersion < CURRENT_VERSION && raw != null) backup(raw, "v" + oldVersion);
            cfg.migrate();
            cfg.rebuild();
            cfg.save();
            return cfg;
        }

        static void save(Config c) {
            try {
                Files.createDirectories(dir());
                Path tmp = dir().resolve("config.json.tmp");
                Files.writeString(tmp, GSON.toJson(c), StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, file(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicUnsupported) {
                    Files.move(tmp, file(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                c.markSynced();
            } catch (IOException e) {
                BaseFinderClient.LOG.warn("[BaseFinder] Could not save config: {}", e.toString());
            }
        }

        /** Copies the original file before it is rewritten. Never overwrites an existing backup. */
        private static void backup(String raw, String tag) {
            if (raw == null) return;
            try {
                Path b = dir().resolve("config.backup-" + tag + "-" + System.currentTimeMillis() + ".json");
                Files.writeString(b, raw, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW);
                BaseFinderClient.LOG.info("[BaseFinder] Old config backed up to {}", b.getFileName());
            } catch (IOException e) {
                BaseFinderClient.LOG.warn("[BaseFinder] Could not back up old config: {}", e.toString());
            }
        }
    }
}
