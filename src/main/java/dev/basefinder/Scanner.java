package dev.basefinder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stage 1 of detection (unchanged idea from v1.x): collects weighted "base signals" from chunks as they load and finds
 * candidate clusters. Candidates are handed to {@link DetectionPipeline}, which does the real analysis
 * (storage clusters, structure, damage, scores, classification).
 */
final class Scanner {
    /** One piece of evidence for a base. chest: -1 not a chest, 0 single, 1 left half, 2 right half. */
    record Sig(BlockPos pos, double w, boolean entity, String id, byte chest) {
        boolean strong() {
            return w >= 1.0;
        }
    }

    record Candidate(String dim, int x, int y, int z, double score, int strong, String kind) {}

    private record Pending(String dim, int cx, int cz, long due) {}

    private static final Set<String> LIVESTOCK = Set.of("minecraft:cow", "minecraft:pig", "minecraft:sheep", "minecraft:chicken",
            "minecraft:horse", "minecraft:donkey", "minecraft:mule", "minecraft:llama", "minecraft:rabbit", "minecraft:goat",
            "minecraft:camel", "minecraft:mooshroom", "minecraft:turtle");

    private final Deque<Pending> pending = new ArrayDeque<>();
    private final Deque<Long> reeval = new ArrayDeque<>();
    private String reevalDim = "";

    private final Map<String, Map<Long, List<Sig>>> blockSigs = new HashMap<>();
    private final Map<String, Map<Long, List<Sig>>> entitySigs = new HashMap<>();
    private final Map<String, Map<Long, List<BlockPos>>> anchors = new HashMap<>();
    private final Map<String, Map<Long, List<BlockPos>>> bells = new HashMap<>();
    private final Map<String, Set<Long>> crystals = new HashMap<>();
    private final Map<String, List<BlockPos>> livestock = new HashMap<>();

    private long tick;
    private long lastEntityScan = -1000;

    // ------------------------------------------------------------------ helpers

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xffffffffL);
    }

    static String dimKey(ClientLevel level) {
        // ResourceKey#toString looks like "ResourceKey[minecraft:dimension / minecraft:overworld]"
        String s = level.dimension().toString();
        int i = s.lastIndexOf(" / ");
        int j = s.lastIndexOf(']');
        return (i >= 0 && j > i) ? s.substring(i + 3, j) : s;
    }

    static String serverKey(Minecraft mc) {
        return mc.getCurrentServer() != null ? mc.getCurrentServer().ip : "singleplayer";
    }

    private static <T> Map<Long, List<T>> sub(Map<String, Map<Long, List<T>>> m, String dim) {
        return m.computeIfAbsent(dim, k -> new HashMap<>());
    }

    // ------------------------------------------------------------------ lifecycle

    /** Forget everything (left the world / changed server). */
    void reset() {
        pending.clear();
        reeval.clear();
        blockSigs.clear();
        entitySigs.clear();
        anchors.clear();
        bells.clear();
        crystals.clear();
        livestock.clear();
    }

    void onChunkLoad(ClientLevel level, LevelChunk chunk) {
        Config cfg = BaseFinderClient.config;
        if (cfg == null || !cfg.enabled) return;
        String dim = dimKey(level);
        if (!cfg.watchesDimension(dim)) return;
        int cx = chunk.getPos().getMinBlockX() >> 4;
        int cz = chunk.getPos().getMinBlockZ() >> 4;
        // Scan twice: once shortly after load, and again once neighbouring chunks and entities have arrived.
        pending.add(new Pending(dim, cx, cz, tick + 40));
        pending.add(new Pending(dim, cx, cz, tick + 160));
    }

    void onChunkUnload(ClientLevel level, LevelChunk chunk) {
        String dim = dimKey(level);
        long k = key(chunk.getPos().getMinBlockX() >> 4, chunk.getPos().getMinBlockZ() >> 4);
        forget(blockSigs, dim, k);
        forget(anchors, dim, k);
        forget(bells, dim, k);
    }

    private static void forget(Map<String, ? extends Map<Long, ?>> m, String dim, long k) {
        Map<Long, ?> inner = m.get(dim);
        if (inner != null) inner.remove(k);
    }

    int trackedChunks() {
        int n = 0;
        for (Map<Long, List<Sig>> m : blockSigs.values()) n += m.size();
        return n;
    }

    void tick(Minecraft mc, ClientLevel level, Config cfg) {
        tick++;
        if (!cfg.enabled) {
            pending.clear();
            reeval.clear();
            return;
        }
        String dim = dimKey(level);
        if (!cfg.watchesDimension(dim)) return;

        // Entities (end crystals, armor stands, item frames, animals) come and go, so refresh them regularly.
        boolean dueScan = !pending.isEmpty() && pending.peek().due() <= tick && tick - lastEntityScan >= 10;
        if (dueScan || tick - lastEntityScan >= 40) {
            lastEntityScan = tick;
            try {
                scanEntities(mc, level, dim, cfg);
            } catch (Exception e) {
                BaseFinderClient.LOG.warn("[BaseFinder] Entity scan failed", e);
            }
        }

        // Re-check every chunk that has signals now and then (late entities, late neighbours).
        if (tick % cfg.reevalIntervalTicks == 0 && reeval.isEmpty()) {
            Map<Long, List<Sig>> bm = blockSigs.get(dim);
            if (bm != null) {
                reevalDim = dim;
                reeval.addAll(bm.keySet());
            }
        }

        int budget = cfg.scanBudget;
        while (budget > 0 && !pending.isEmpty() && pending.peek().due() <= tick) {
            Pending p = pending.poll();
            if (!p.dim().equals(dim)) continue;
            budget--;
            try {
                scan(mc, level, dim, p.cx(), p.cz(), cfg);
            } catch (Exception e) {
                BaseFinderClient.LOG.warn("[BaseFinder] Scan failed for chunk {}, {}", p.cx(), p.cz(), e);
            }
        }

        int evalBudget = cfg.scanBudget * 2;
        while (evalBudget > 0 && !reeval.isEmpty()) {
            Long k = reeval.poll();
            if (!reevalDim.equals(dim)) continue;
            evalBudget--;
            try {
                evaluate(dim, (int) (k >> 32), (int) (long) k, cfg);
            } catch (Exception e) {
                BaseFinderClient.LOG.warn("[BaseFinder] Re-check failed", e);
            }
        }
    }

    // ------------------------------------------------------------------ scanning

    private void scanEntities(Minecraft mc, ClientLevel level, String dim, Config cfg) {
        Map<Long, List<Sig>> em = new HashMap<>();
        Set<Long> crystalsNow = new HashSet<>();
        List<BlockPos> animals = new ArrayList<>();

        for (Entity e : EntityView.all(level)) {
            String id = EntityView.typeId(e);
            BlockPos bp = e.blockPosition();
            if ("minecraft:end_crystal".equals(id)) { // the entity, never an item
                crystalsNow.add(bp.asLong());
                continue;
            }
            if (LIVESTOCK.contains(id)) animals.add(bp);
            double w = cfg.entityWeight(id);
            if (w > 0) {
                em.computeIfAbsent(key(bp.getX() >> 4, bp.getZ() >> 4), k -> new ArrayList<>())
                        .add(new Sig(bp, w, true, id, (byte) -1));
            }
        }
        entitySigs.put(dim, em);
        livestock.put(dim, animals);

        // Remember crystals we have seen, but forget ones that are gone when we are close enough to know.
        Set<Long> known = crystals.computeIfAbsent(dim, k -> new HashSet<>());
        known.addAll(crystalsNow);
        if (mc.player != null) {
            BlockPos pp = mc.player.blockPosition();
            known.removeIf(l -> !crystalsNow.contains(l)
                    && Math.abs(BlockPos.getX(l) - pp.getX()) < 24
                    && Math.abs(BlockPos.getY(l) - pp.getY()) < 24
                    && Math.abs(BlockPos.getZ(l) - pp.getZ()) < 24);
        }
    }

    private void scan(Minecraft mc, ClientLevel level, String dim, int cx, int cz, Config cfg) {
        LevelChunk chunk = level.getChunk(cx, cz);

        List<Sig> sigs = new ArrayList<>();
        List<BlockPos> bellList = new ArrayList<>();
        for (BlockEntity be : new ArrayList<>(chunk.getBlockEntities().values())) {
            BlockState state = be.getBlockState();
            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().intern();
            if ("minecraft:bell".equals(id)) {
                bellList.add(be.getBlockPos());
                continue;
            }
            double w = cfg.blockWeight(id);
            if (w <= 0) continue;
            byte chest = -1;
            if (state.hasProperty(ChestBlock.TYPE)) chest = (byte) state.getValue(ChestBlock.TYPE).ordinal();
            sigs.add(new Sig(be.getBlockPos(), w, false, id, chest));
        }

        List<BlockPos> found = findRespawnAnchors(chunk, cx, cz);
        if (found.isEmpty()) sub(anchors, dim).remove(key(cx, cz));
        else sub(anchors, dim).put(key(cx, cz), found);

        Map<Long, List<Sig>> bm = sub(blockSigs, dim);
        if (bm.size() > 30000) bm.clear(); // keep memory bounded on very long sessions
        if (bellList.isEmpty()) sub(bells, dim).remove(key(cx, cz));
        else sub(bells, dim).put(key(cx, cz), bellList);

        if (sigs.isEmpty()) {
            bm.remove(key(cx, cz));
            return;
        }
        bm.put(key(cx, cz), sigs);
        evaluate(dim, cx, cz, cfg);
    }

    /**
     * Respawn anchors are not block entities, so the chunk's block palettes are searched. The section methods are
     * looked up by reflection (names have changed between versions); if they are missing this scan is skipped, and
     * anchors inside the analysed area are still found by the area snapshot.
     */
    private static final class AnchorScan {
        static final java.util.function.Predicate<BlockState> IS_ANCHOR = st -> st.is(Blocks.RESPAWN_ANCHOR);
        static java.lang.reflect.Method getSections, getMinY, hasOnlyAir, maybeHas, getBlockState;
        static boolean resolved, usable;

        static void resolve(LevelChunk chunk) {
            resolved = true;
            try {
                getSections = chunk.getClass().getMethod("getSections");
                try {
                    getMinY = chunk.getClass().getMethod("getMinY");
                } catch (NoSuchMethodException e) {
                    getMinY = chunk.getClass().getMethod("getMinBuildHeight");
                }
                Class<?> sec = getSections.getReturnType().getComponentType();
                hasOnlyAir = sec.getMethod("hasOnlyAir");
                maybeHas = sec.getMethod("maybeHas", java.util.function.Predicate.class);
                getBlockState = sec.getMethod("getBlockState", int.class, int.class, int.class);
                usable = true;
            } catch (Throwable t) {
                usable = false;
                BaseFinderClient.LOG.warn("[BaseFinder] Respawn anchor palette scan unavailable ({}); anchors are still found inside analysed areas", t.toString());
            }
        }
    }

    private static List<BlockPos> findRespawnAnchors(LevelChunk chunk, int cx, int cz) {
        List<BlockPos> out = new ArrayList<>();
        if (!AnchorScan.resolved) AnchorScan.resolve(chunk);
        if (!AnchorScan.usable) return out;
        try {
            Object[] sections = (Object[]) AnchorScan.getSections.invoke(chunk);
            int minSection = ((Integer) AnchorScan.getMinY.invoke(chunk)) >> 4;
            for (int i = 0; i < sections.length; i++) {
                Object sec = sections[i];
                if (sec == null || (Boolean) AnchorScan.hasOnlyAir.invoke(sec)) continue;
                if (!(Boolean) AnchorScan.maybeHas.invoke(sec, AnchorScan.IS_ANCHOR)) continue;
                for (int x = 0; x < 16; x++) {
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            BlockState st = (BlockState) AnchorScan.getBlockState.invoke(sec, x, y, z);
                            if (st.is(Blocks.RESPAWN_ANCHOR)) {
                                out.add(new BlockPos((cx << 4) + x, ((minSection + i) << 4) + y, (cz << 4) + z));
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            AnchorScan.usable = false;
        }
        return out;
    }

    // ------------------------------------------------------------------ candidate stage (weighted clusters)

    private static double sum(List<Sig> l) {
        double t = 0;
        for (Sig s : l) t += s.w();
        return t;
    }

    private static int strongCount(List<Sig> l) {
        int n = 0;
        for (Sig s : l) if (s.strong()) n++;
        return n;
    }

    private void evaluate(String dim, int cx, int cz, Config cfg) {
        Map<Long, List<Sig>> bm = blockSigs.get(dim);
        if (bm == null) return;
        List<Sig> center = bm.get(key(cx, cz));
        if (center == null || center.isEmpty()) return;
        Map<Long, List<Sig>> em = entitySigs.get(dim);

        // This chunk plus its 8 neighbours, so clusters crossing chunk borders are seen whole.
        List<Sig> pts = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                List<Sig> l = bm.get(key(cx + dx, cz + dz));
                if (l != null) pts.addAll(l);
                if (em != null) {
                    List<Sig> e = em.get(key(cx + dx, cz + dz));
                    if (e != null) pts.addAll(e);
                }
            }
        }
        if (strongCount(pts) < cfg.minStrongStorage) return;
        if (pts.size() > 1500) pts = pts.subList(0, 1500);

        // Pass 1: the densest tight cluster anchored on a signal in this chunk.
        int r = cfg.clusterRadius;
        List<Sig> best = List.of();
        double bestScore = 0;
        int anchorsChecked = 0;
        for (Sig a : center) {
            if (++anchorsChecked > 60) break;
            List<Sig> members = new ArrayList<>();
            for (Sig p : pts) {
                if (Math.abs(p.pos().getX() - a.pos().getX()) <= r && Math.abs(p.pos().getY() - a.pos().getY()) <= r
                        && Math.abs(p.pos().getZ() - a.pos().getZ()) <= r) {
                    members.add(p);
                }
            }
            double score = sum(members);
            if (score > bestScore) {
                bestScore = score;
                best = members;
            }
        }
        String kind = "cluster";
        boolean ok = strongCount(best) >= cfg.minStrongStorage && bestScore >= cfg.minScore;

        // Pass 2: spread-out bases. Add up everything in the 3x3 chunk area.
        if (!ok && cfg.wideScan) {
            double total = sum(pts);
            if (strongCount(pts) >= cfg.wideMinStrong && total >= cfg.wideMinScore) {
                best = pts;
                bestScore = total;
                kind = "spread";
                ok = true;
            }
        }
        if (!ok) return;

        long sx = 0, sy = 0, sz = 0;
        int n = 0;
        for (Sig s : best) {
            if (!s.strong()) continue;
            sx += s.pos().getX();
            sy += s.pos().getY();
            sz += s.pos().getZ();
            n++;
        }
        if (n == 0) return;
        int x = (int) Math.floorDiv(sx, (long) n);
        int y = (int) Math.floorDiv(sy, (long) n);
        int z = (int) Math.floorDiv(sz, (long) n);

        // Villages have beds, barrels and furnaces: skip clusters next to a bell unless the score is very high.
        if (cfg.suppressNearBell && bestScore < cfg.bellBypassScore && bellsNear(dim, x, y, z, cfg.bellRadius) > 0) return;

        BaseFinderClient.pipeline.submit(new Candidate(dim, x, y, z, bestScore, strongCount(best), kind));
    }

    // ------------------------------------------------------------------ queries used by the analysis pipeline

    /** Block-entity signals within a horizontal box around (x, z). */
    List<Sig> gather(String dim, int x, int z, int radius) {
        List<Sig> out = new ArrayList<>();
        Map<Long, List<Sig>> bm = blockSigs.get(dim);
        if (bm == null) return out;
        int span = (radius >> 4) + 1;
        int cx = x >> 4, cz = z >> 4;
        for (int dx = -span; dx <= span; dx++) {
            for (int dz = -span; dz <= span; dz++) {
                List<Sig> l = bm.get(key(cx + dx, cz + dz));
                if (l == null) continue;
                for (Sig s : l) {
                    if (Math.abs(s.pos().getX() - x) <= radius && Math.abs(s.pos().getZ() - z) <= radius) out.add(s);
                }
            }
        }
        return out;
    }

    List<int[]> crystalsNear(String dim, int x, int y, int z, int radius) {
        List<int[]> out = new ArrayList<>();
        Set<Long> cs = crystals.get(dim);
        if (cs == null) return out;
        long r2 = (long) radius * radius;
        for (long l : cs) {
            long dx = BlockPos.getX(l) - x, dy = BlockPos.getY(l) - y, dz = BlockPos.getZ(l) - z;
            if (dx * dx + dy * dy + dz * dz <= r2) out.add(new int[]{BlockPos.getX(l), BlockPos.getY(l), BlockPos.getZ(l)});
        }
        return out;
    }

    List<int[]> anchorsNear(String dim, int x, int y, int z, int radius) {
        List<int[]> out = new ArrayList<>();
        Map<Long, List<BlockPos>> map = anchors.get(dim);
        if (map == null) return out;
        long r2 = (long) radius * radius;
        int span = (radius >> 4) + 1;
        for (int dx = -span; dx <= span; dx++) {
            for (int dz = -span; dz <= span; dz++) {
                List<BlockPos> l = map.get(key((x >> 4) + dx, (z >> 4) + dz));
                if (l == null) continue;
                for (BlockPos p : l) {
                    long ddx = p.getX() - x, ddy = p.getY() - y, ddz = p.getZ() - z;
                    if (ddx * ddx + ddy * ddy + ddz * ddz <= r2) out.add(new int[]{p.getX(), p.getY(), p.getZ()});
                }
            }
        }
        return out;
    }

    int livestockNear(String dim, int x, int z, int radius) {
        List<BlockPos> l = livestock.get(dim);
        if (l == null) return 0;
        int n = 0;
        for (BlockPos p : l) if (Math.abs(p.getX() - x) <= radius && Math.abs(p.getZ() - z) <= radius) n++;
        return n;
    }

    int bellsNear(String dim, int x, int y, int z, int radius) {
        Map<Long, List<BlockPos>> map = bells.get(dim);
        if (map == null || map.isEmpty()) return 0;
        long r2 = (long) radius * radius;
        int span = (radius >> 4) + 1;
        int n = 0;
        for (int dx = -span; dx <= span; dx++) {
            for (int dz = -span; dz <= span; dz++) {
                List<BlockPos> l = map.get(key((x >> 4) + dx, (z >> 4) + dz));
                if (l == null) continue;
                for (BlockPos p : l) {
                    long ddx = p.getX() - x, ddy = p.getY() - y, ddz = p.getZ() - z;
                    if (ddx * ddx + ddy * ddy + ddz * ddz <= r2) n++;
                }
            }
        }
        return n;
    }
}
