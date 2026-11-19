package dev.basefinder;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.basefinder.core.BaseReport;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Detection history, saved to config/basefinder/detections.json. Old (v1.x) entries load fine: their new fields are
 * simply empty. Stores accepted AND rejected detections (rejected ones keep their exact rejection reasons).
 */
final class DetectionStore {
    static final class Detection {
        // ---- v1.x fields (unchanged) ----
        String server;
        String dimension;
        int chunkX;
        int chunkZ;
        int x;
        int y;
        int z;
        int storageCount;
        double score;
        String kind;
        int confidence;
        double supportRatio;
        double avgEnclosure;
        String foundAt;

        // ---- v2 fields ----
        String id;
        /** INTACT, SUSPICIOUS, GRIEFED or REJECTED. Null for entries saved by v1.x. */
        String classification;
        String outcome;
        int likelihood;
        String condition;
        int conditionScore;
        int damageScore;
        int infraScore;
        int chests, doubleChests, barrels, shulkers, totalStorage, clusters, largestCluster;
        double density;
        double coverage;
        long lastAnalysis;
        boolean hasMaps;
        List<String> reasons = new ArrayList<>();
        List<String> likelihoodReasons = new ArrayList<>();
        List<String> conditionReasons = new ArrayList<>();
        List<String> infraReasons = new ArrayList<>();
        List<String> suspicious = new ArrayList<>();
        List<String> rejectionReasons = new ArrayList<>();
        List<String> dataQuality = new ArrayList<>();
        List<String> classHistory = new ArrayList<>();

        boolean legacy() {
            return classification == null;
        }

        String classOrLegacy() {
            return classification == null ? "LEGACY" : classification;
        }

        /** Copies the analysis into this record (keeps id, server, foundAt and history). */
        void apply(BaseReport r) {
            dimension = r.dimension;
            x = r.x;
            y = r.y;
            z = r.z;
            chunkX = r.x >> 4;
            chunkZ = r.z >> 4;
            classification = r.classification.name();
            outcome = r.outcome.name();
            likelihood = (int) Math.round(r.likelihood);
            confidence = likelihood;
            condition = r.condition.label;
            conditionScore = (int) Math.round(r.conditionScore);
            damageScore = (int) Math.round(r.damageScore);
            infraScore = (int) Math.round(r.infraScore);
            chests = r.storage.chests;
            doubleChests = r.storage.doubleChests;
            barrels = r.storage.barrels;
            shulkers = r.storage.shulkers;
            totalStorage = r.storage.total;
            storageCount = r.storage.total;
            clusters = r.storage.clusters.size();
            largestCluster = r.storage.largest == null ? 0 : r.storage.largest.count;
            density = Math.round(r.storage.densityOfLargest * 10.0) / 10.0;
            coverage = Math.round(r.coverage * 100.0) / 100.0;
            kind = "analysed";
            reasons = new ArrayList<>(r.classReasons);
            likelihoodReasons = new ArrayList<>(r.likelihoodReasons);
            conditionReasons = new ArrayList<>(r.conditionReasons);
            infraReasons = new ArrayList<>(r.infraReasons);
            suspicious = new ArrayList<>(r.suspicious);
            rejectionReasons = new ArrayList<>(r.rejectionReasons);
            dataQuality = new ArrayList<>(r.dataQuality);
            lastAnalysis = System.currentTimeMillis();
        }
    }

    private static Path file() {
        return Config.dir().resolve("detections.json");
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final List<Detection> all = new ArrayList<>();

    static DetectionStore load() {
        DetectionStore s = new DetectionStore();
        try {
            if (Files.exists(file())) {
                List<Detection> read = GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8),
                        new TypeToken<List<Detection>>() {}.getType());
                if (read != null) {
                    for (Detection d : read) {
                        if (d == null) continue;
                        if (d.server == null) d.server = "unknown";
                        if (d.dimension == null) d.dimension = "minecraft:overworld";
                        if (d.id == null) d.id = newId(d.server, d.dimension, d.chunkX, d.chunkZ); // v1.x entries get an id
                        s.all.add(d);
                    }
                }
            }
        } catch (Exception e) {
            BaseFinderClient.LOG.warn("[BaseFinder] Could not read detections file: {}", e.toString());
        }
        return s;
    }

    /** Legacy duplicate check, kept for compatibility. */
    synchronized boolean isDuplicate(String server, String dim, int cx, int cz, int radius) {
        return findNear(server, dim, cx, cz, radius) != null;
    }

    synchronized Detection findNear(String server, String dim, int cx, int cz, int radius) {
        Detection best = null;
        int bestD = Integer.MAX_VALUE;
        for (Detection d : all) {
            if (!server.equals(d.server) || !dim.equals(d.dimension)) continue;
            int dx = Math.abs(d.chunkX - cx), dz = Math.abs(d.chunkZ - cz);
            if (dx <= radius && dz <= radius && dx + dz < bestD) {
                best = d;
                bestD = dx + dz;
            }
        }
        return best;
    }

    synchronized void add(Detection d, boolean persist, int maxHistory) {
        all.add(d);
        while (all.size() > maxHistory) all.remove(0);
        if (persist) write();
    }

    /** Call after changing a stored detection. */
    synchronized void touch(boolean persist) {
        if (persist) write();
    }

    synchronized List<Detection> snapshot() {
        return new ArrayList<>(all);
    }

    /** Newest first, at most {@code n} entries. */
    synchronized List<Detection> recent(int n) {
        List<Detection> out = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0 && out.size() < n; i--) out.add(all.get(i));
        return out;
    }

    synchronized int size() {
        return all.size();
    }

    /** Count by classification (INTACT, SUSPICIOUS, GRIEFED, REJECTED, LEGACY). */
    synchronized int count(String classification) {
        int n = 0;
        for (Detection d : all) if (classification.equals(d.classOrLegacy())) n++;
        return n;
    }

    synchronized Detection byId(String id) {
        for (Detection d : all) if (id != null && id.equals(d.id)) return d;
        return null;
    }

    static String newId(String server, String dim, int cx, int cz) {
        int h = (server + "|" + dim + "|" + cx + "|" + cz).hashCode();
        return "BF-" + Integer.toString(h & 0x7fffffff, 36).toUpperCase(Locale.ROOT);
    }

    private void write() {
        try {
            Files.createDirectories(Config.dir());
            Path tmp = Config.dir().resolve("detections.json.tmp");
            Files.writeString(tmp, GSON.toJson(all), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.io.IOException atomicUnsupported) {
                Files.move(tmp, file(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            BaseFinderClient.LOG.warn("[BaseFinder] Could not save detections: {}", e.toString());
        }
    }
}
