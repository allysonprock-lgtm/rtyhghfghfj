package dev.basefinder;

import dev.basefinder.core.BaseReport;
import dev.basefinder.core.BaseScoringEngine;
import dev.basefinder.core.EmbedFactory;
import dev.basefinder.core.ImageRenderer;
import dev.basefinder.core.Multipart;
import dev.basefinder.core.Observation;
import dev.basefinder.core.Params;
import dev.basefinder.core.StoragePoint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stage 2 of detection. Candidates from the {@link Scanner} are queued; on the game thread the surrounding blocks are
 * copied into a snapshot; a background thread runs the (pure) analysis and renders the reconstructions; the result
 * comes back to the game thread to be stored, shown in game and routed to the right Discord channel.
 * The background thread never touches Minecraft objects.
 */
final class DetectionPipeline {
    record Job(Scanner.Candidate c, String id, boolean silent, String region) {}

    record Cached(Observation obs, BaseReport report) {}

    private record Result(Job job, Observation obs, BaseReport report, byte[] plan, byte[] voxel, byte[] iso) {}

    private final Deque<Job> jobs = new ArrayDeque<>();
    private final Set<String> inFlight = new HashSet<>();
    private final ConcurrentLinkedQueue<Result> results = new ConcurrentLinkedQueue<>();
    private final AtomicInteger running = new AtomicInteger();
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "basefinder-analysis");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final Map<String, Cached> cache = new LinkedHashMap<>(16, 0.75f, true);
    private long tick, lastStart;

    int queued() {
        return jobs.size();
    }

    int running() {
        return running.get();
    }

    Cached cached(String id) {
        return cache.get(id);
    }

    List<String> cachedIds() {
        return new ArrayList<>(cache.keySet());
    }

    void reset() {
        jobs.clear();
        inFlight.clear();
    }

    void shutdown() {
        exec.shutdownNow();
    }

    // ------------------------------------------------------------------ intake (game thread)

    void submit(Scanner.Candidate c) {
        Minecraft mc = Minecraft.getInstance();
        Config cfg = BaseFinderClient.config;
        String server = Scanner.serverKey(mc);
        int cx = c.x() >> 4, cz = c.z() >> 4;
        DetectionStore.Detection ex = BaseFinderClient.store.findNear(server, c.dim(), cx, cz, cfg.dedupeChunkRadius);

        boolean silent = false;
        String id;
        if (ex != null) {
            if (!ex.legacy()) {
                long cooldown = 1000L * ("REJECTED".equals(ex.classification) ? cfg.rejectedRecheckSeconds : cfg.reanalyzeSeconds);
                if (System.currentTimeMillis() - ex.lastAnalysis < cooldown) return; // unchanged recently: do not recompute
            } else {
                silent = true; // a v1.x detection that was already reported: analyse it quietly, no duplicate report
            }
            if (ex.id == null) ex.id = DetectionStore.newId(server, c.dim(), ex.chunkX, ex.chunkZ);
            id = ex.id;
        } else {
            id = DetectionStore.newId(server, c.dim(), cx, cz);
        }
        String region = server + "|" + c.dim() + "|" + (c.x() >> 5) + "|" + (c.z() >> 5);
        if (inFlight.contains(region) || jobs.size() >= 40) return;
        inFlight.add(region);
        jobs.add(new Job(c, id, silent, region));
    }

    // ------------------------------------------------------------------ per-tick work (game thread)

    void tick(Minecraft mc, ClientLevel level, Config cfg, Scanner scanner) {
        tick++;
        Result r;
        while ((r = results.poll()) != null) {
            try {
                finish(mc, cfg, r);
            } catch (Exception e) {
                BaseFinderClient.LOG.warn("[BaseFinder] Could not finish a detection", e);
                inFlight.remove(r.job().region());
            }
        }
        if (jobs.isEmpty() || running.get() >= 2 || tick - lastStart < cfg.analysisIntervalTicks) return;

        Job j = jobs.poll();
        lastStart = tick;
        if (!j.c().dim().equals(Scanner.dimKey(level))) {
            inFlight.remove(j.region());
            return;
        }
        Observation o;
        try {
            o = buildObservation(level, scanner, j.c(), cfg);
        } catch (Exception e) {
            BaseFinderClient.LOG.warn("[BaseFinder] Could not sample the area", e);
            inFlight.remove(j.region());
            return;
        }
        Params p = cfg.toParams();
        boolean images = cfg.attachReconstructions;
        running.incrementAndGet();
        exec.execute(() -> {
            try {
                BaseReport rep = BaseScoringEngine.analyze(o, p);
                rep.id = j.id();
                byte[] plan = null, voxel = null, iso = null;
                if (images && rep.classification != BaseReport.BaseClass.REJECTED) {
                    plan = ImageRenderer.floorPlanPng(o, rep);
                    voxel = ImageRenderer.voxelPng(o, rep);
                    iso = ImageRenderer.isometricPng(o, rep);
                    saveImages(j.id(), plan, voxel, iso);
                }
                results.add(new Result(j, o, rep, plan, voxel, iso));
            } catch (Throwable t) {
                BaseFinderClient.LOG.warn("[BaseFinder] Analysis failed: {}", t.toString());
                results.add(new Result(j, null, null, null, null, null));
            } finally {
                running.decrementAndGet();
            }
        });
    }

    private Observation buildObservation(ClientLevel level, Scanner sc, Scanner.Candidate c, Config cfg) {
        int r = cfg.analysisRadius;
        Observation o = new Observation();
        o.snap = WorldSampler.capture(level, c.dim(), c.x(), c.y(), c.z(), r, cfg.analysisBelow, cfg.analysisAbove);
        o.centerX = c.x();
        o.centerY = c.y();
        o.centerZ = c.z();

        List<Scanner.Sig> sigs = sc.gather(c.dim(), c.x(), c.z(), r);
        Set<Long> leftHalves = new HashSet<>();
        for (Scanner.Sig s : sigs) if (s.chest() == 1) leftHalves.add(s.pos().asLong());
        for (Scanner.Sig s : sigs) {
            if (s.entity()) continue;
            o.blockEntities.merge(s.id(), 1, Integer::sum);
            String id = s.id();
            int x = s.pos().getX(), y = s.pos().getY(), z = s.pos().getZ();
            if (id.equals("minecraft:chest") || id.equals("minecraft:trapped_chest")) {
                StoragePoint.Kind k = id.equals("minecraft:chest") ? StoragePoint.Kind.CHEST : StoragePoint.Kind.TRAPPED_CHEST;
                if (s.chest() == 2) { // right half: counted with its left half, unless that half is not loaded
                    boolean partner = false;
                    for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        if (leftHalves.contains(s.pos().offset(d[0], 0, d[1]).asLong())) partner = true;
                    }
                    if (!partner) o.storage.add(new StoragePoint(x, y, z, k, true));
                } else {
                    o.storage.add(new StoragePoint(x, y, z, k, s.chest() == 1)); // 0 = single, 1 = double (left half)
                }
            } else if (id.equals("minecraft:barrel")) {
                o.storage.add(new StoragePoint(x, y, z, StoragePoint.Kind.BARREL, false));
            } else if (id.endsWith("shulker_box")) {
                o.storage.add(new StoragePoint(x, y, z, StoragePoint.Kind.SHULKER, false));
            }
        }
        o.crystals.addAll(sc.crystalsNear(c.dim(), c.x(), c.y(), c.z(), cfg.exclusionRadius * 2));
        o.anchors.addAll(sc.anchorsNear(c.dim(), c.x(), c.y(), c.z(), cfg.exclusionRadius * 2));
        o.livestock = sc.livestockNear(c.dim(), c.x(), c.z(), r);
        o.bells = sc.bellsNear(c.dim(), c.x(), c.y(), c.z(), cfg.bellRadius);
        return o;
    }

    private static void saveImages(String id, byte[] plan, byte[] voxel, byte[] iso) {
        try {
            Path dir = Config.dir().resolve("reconstructions");
            Files.createDirectories(dir);
            if (plan != null) Files.write(dir.resolve(id + "-plan.png"), plan);
            if (voxel != null) Files.write(dir.resolve(id + "-voxel.png"), voxel);
            if (iso != null) Files.write(dir.resolve(id + "-iso.png"), iso);
            try (var s = Files.list(dir)) { // keep the folder small
                List<Path> files = new ArrayList<>(s.toList());
                if (files.size() > 300) {
                    files.sort((a, b) -> Long.compare(a.toFile().lastModified(), b.toFile().lastModified()));
                    for (int i = 0; i < files.size() - 300; i++) Files.deleteIfExists(files.get(i));
                }
            }
        } catch (Exception e) {
            BaseFinderClient.LOG.warn("[BaseFinder] Could not save reconstructions: {}", e.toString());
        }
    }

    // ------------------------------------------------------------------ result handling (game thread)

    private void finish(Minecraft mc, Config cfg, Result res) {
        inFlight.remove(res.job().region());
        if (res.report() == null) return;
        BaseReport rep = res.report();
        String server = Scanner.serverKey(mc);
        String nowIso = Instant.now().toString();

        DetectionStore.Detection d = BaseFinderClient.store.byId(res.job().id());
        boolean isNew = d == null;
        if (isNew) {
            d = new DetectionStore.Detection();
            d.id = res.job().id();
            d.server = server;
            d.foundAt = nowIso;
        }
        String prev = d.classification;
        d.apply(rep);
        d.hasMaps = res.plan() != null;
        String cls = d.classification;
        if (prev == null || !prev.equals(cls)) d.classHistory.add(nowIso + "  " + (prev == null ? "new" : prev) + " -> " + cls);
        if (isNew) BaseFinderClient.store.add(d, cfg.saveLocally, cfg.maxHistory);
        else BaseFinderClient.store.touch(cfg.saveLocally);

        cache.put(d.id, new Cached(res.obs(), rep));
        while (cache.size() > cfg.snapshotCacheSize) {
            String eldest = cache.keySet().iterator().next();
            cache.remove(eldest);
        }

        // ---- decide whether anyone gets notified (never duplicate, never spam) ----
        boolean notify;
        String note = null;
        if (res.job().silent()) notify = false; // v1.x detection, already reported before
        else if (prev == null) notify = !cls.equals("REJECTED");
        else if (prev.equals("REJECTED")) {
            notify = !cls.equals("REJECTED");
            note = "Previously rejected, now qualifies";
        } else if (cls.equals("REJECTED")) notify = false;
        else if (!prev.equals(cls)) {
            notify = cfg.notifyReclassification;
            note = "Reclassified: " + prev + " \u2192 " + cls;
        } else notify = false;

        String text = EmbedFactory.coords(d.x, d.y, d.z);
        if (cls.equals("REJECTED")) {
            if (prev == null || !prev.equals("REJECTED")) {
                BaseFinderClient.history.add("BASE_REJECTED", d.dimension, d.x, d.y, d.z, d.id + " rejected",
                        String.join("; ", d.rejectionReasons), cfg.maxEventHistory);
            }
        } else if (prev != null && !prev.equals(cls) && !prev.equals("REJECTED")) {
            BaseFinderClient.history.add("RECLASSIFIED", d.dimension, d.x, d.y, d.z, d.id + ": " + prev + " -> " + cls, text, cfg.maxEventHistory);
        } else if (notify || prev == null) {
            BaseFinderClient.history.add("BASE_" + cls, d.dimension, d.x, d.y, d.z, d.id + " " + d.condition, text, cfg.maxEventHistory);
        }
        if (!notify) return;

        Alerts.baseFound(mc, d, note);

        Channel ch = switch (cls) {
            case "INTACT" -> Channel.INTACT;
            case "GRIEFED" -> Channel.GRIEFED;
            default -> Channel.SUSPICIOUS;
        };
        String json = EmbedFactory.baseReport(rep, cfg.includeServerAddress ? server : null, cfg.discordUsername, nowIso,
                res.plan() != null, res.voxel() != null, res.iso() != null, note);
        List<Multipart.Attachment> files = new ArrayList<>();
        if (res.plan() != null) files.add(new Multipart.Attachment("plan.png", res.plan()));
        if (res.voxel() != null) files.add(new Multipart.Attachment("voxel.png", res.voxel()));
        if (res.iso() != null) files.add(new Multipart.Attachment("iso.png", res.iso()));
        if (!BaseFinderClient.discord.send(ch, json, files)) Alerts.channelNotConfigured(mc, ch);
        Notifier.sendApi(cfg, d, note == null ? "base_detected" : "base_reclassified");
    }
}
