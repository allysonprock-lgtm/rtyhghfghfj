package dev.basefinder.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Measures walls, roofs, floors, enclosed rooms, buildings, farms, craters and suspicious blocks in an
 * {@link Observation}. It only reports what the observed blocks show; judgement happens in the scoring engine.
 * Cells that were never loaded are UNKNOWN and are never treated as open or as damaged.
 */
public final class StructureAnalyzer {
    private StructureAnalyzer() {}

    private static final int[][] DIRS4 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] DIRS6 = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    private static final int MAX_ROOM = 3000;
    private static final int MAX_STORAGE_EVAL = 300;

    public static StructureReport analyze(Observation o, Params p) {
        StructureReport r = new StructureReport();
        Snapshot s = o.snap;
        Cat[] all = Cat.values();
        int total = s.size();
        r.totalCells = total;

        for (int i = 0; i < total; i++) {
            Cat c = s.at(i);
            r.counts[c.ordinal()]++;
        }
        r.knownCells = total - r.counts[Cat.UNKNOWN.ordinal()];
        r.coverage = total == 0 ? 0 : r.knownCells / (double) total;
        for (Cat c : all) {
            if (c.isStructural()) r.structural += r.counts[c.ordinal()];
        }
        r.naturalSolid = r.counts[Cat.NATURAL.ordinal()];
        r.doors = r.count(Cat.DOOR);
        r.farmland = r.count(Cat.FARMLAND);
        r.crops = r.count(Cat.CROP);
        r.infraCells = r.count(Cat.INFRA);
        r.fire = r.count(Cat.FIRE);
        r.tnt = r.count(Cat.TNT);
        r.livestock = o.livestock;

        // ---- column heights (highest solid cell) ----
        int cols = s.sx * s.sz;
        int[] top = new int[cols];
        boolean[] colKnown = new boolean[cols];
        boolean[] fluidAboveTop = new boolean[cols];
        for (int z = 0; z < s.sz; z++) {
            for (int x = 0; x < s.sx; x++) {
                int col = x + z * s.sx;
                top[col] = -1;
                boolean fluid = false;
                for (int y = s.sy - 1; y >= 0; y--) {
                    Cat c = s.get(x, y, z);
                    if (c != Cat.UNKNOWN) colKnown[col] = true;
                    if (c == Cat.WATER || c == Cat.LAVA) fluid = true;
                    if (c.solid) {
                        top[col] = y;
                        break;
                    }
                }
                fluidAboveTop[col] = fluid;
            }
        }

        // ---- storage placement ----
        List<int[]> st = new ArrayList<>();
        for (StoragePoint sp : o.storage) {
            if (st.size() >= MAX_STORAGE_EVAL) break;
            st.add(new int[]{s.relX(sp.x()), s.relY(sp.y()), s.relZ(sp.z())});
        }
        int undergroundCount = 0;
        for (int[] q : st) {
            int x = q[0], y = q[1], z = q[2];
            if (!s.in(x, y, z)) {
                r.unevaluated++;
                continue;
            }
            int walls = 0, wallUnknown = 0;
            for (int[] d : DIRS4) {
                int h = ray(s, x, y, z, d[0], 0, d[1], 8);
                if (h > 0) walls++;
                else if (h == -2) wallUnknown++;
            }
            int roof = ray(s, x, y, z, 0, 1, 0, 12);
            if (wallUnknown > 1 || roof == -2) {
                r.unevaluated++;
                continue;
            }
            r.evaluated++;
            if (roof > 0 && walls >= 2) r.housed++;
            if (roof > 0 && walls >= 3) r.sealed++;
            if (roof == -1 && walls >= 2) r.roofMissing++;
            if (roof > 0 && walls <= 1) {
                r.roofedOpen++;
                if (remnants(s, x, y, z) >= 12) r.breached++;
            }
            Cat below = s.get(x, y - 1, z);
            if (below != Cat.UNKNOWN) {
                r.floatEvaluated++;
                if (below.passable) r.floating++;
            }
            int solidAbove = 0;
            for (int yy = y + 1; yy < s.sy; yy++) if (s.get(x, yy, z).solid) solidAbove++;
            if (solidAbove >= 6) undergroundCount++;
        }
        r.undergroundFraction = r.evaluated == 0 ? 0 : undergroundCount / (double) r.evaluated;

        // ---- sealed rooms (flood fill from air cells touching storage) ----
        int[] roomOf = new int[total];
        List<Boolean> roomSealed = new ArrayList<>();
        List<Integer> roomVolume = new ArrayList<>();
        roomSealed.add(false);
        roomVolume.add(0);
        for (int[] q : st) {
            if (!s.in(q[0], q[1], q[2])) continue;
            boolean counted = false;
            for (int[] d : DIRS6) {
                int nx = q[0] + d[0], ny = q[1] + d[1], nz = q[2] + d[2];
                if (!s.in(nx, ny, nz)) continue;
                Cat c = s.get(nx, ny, nz);
                if (!c.passable || c == Cat.UNKNOWN || c == Cat.DOOR) continue;
                int idx = s.index(nx, ny, nz);
                int id = roomOf[idx];
                if (id == 0) {
                    id = roomSealed.size();
                    boolean sealedRoom = fill(s, roomOf, id, nx, ny, nz, top, colKnown, roomVolume);
                    roomSealed.add(sealedRoom);
                    if (sealedRoom) {
                        r.sealedRooms++;
                        r.largestRoomVolume = Math.max(r.largestRoomVolume, roomVolume.get(id));
                    }
                }
                if (roomSealed.get(id) && !counted) {
                    r.storageInSealedRooms++;
                    counted = true;
                }
            }
        }

        // ---- debris and connected buildings ----
        boolean[] seen = new boolean[total];
        int[] queue = new int[Math.min(total, 400_000)];
        for (int z = 0; z < s.sz; z++) {
            for (int y = 0; y < s.sy; y++) {
                for (int x = 0; x < s.sx; x++) {
                    Cat c = s.get(x, y, z);
                    if (!c.isStructural()) continue;
                    int open = 0;
                    for (int[] d : DIRS6) {
                        Cat n = s.get(x + d[0], y + d[1], z + d[2]);
                        if (n != Cat.UNKNOWN && !n.solid) open++;
                    }
                    if (open >= 5) r.debris++;

                    int idx = s.index(x, y, z);
                    if (seen[idx]) continue;
                    int size = flood26(s, seen, queue, x, y, z);
                    if (size >= 20) {
                        r.buildings++;
                        r.largestComponent = Math.max(r.largestComponent, size);
                    }
                }
            }
        }

        // ---- lava near storage ----
        for (int z = 0; z < s.sz; z++) {
            for (int y = 0; y < s.sy; y++) {
                for (int x = 0; x < s.sx; x++) {
                    if (s.get(x, y, z) != Cat.LAVA) continue;
                    for (int[] q : st) {
                        if (Math.abs(q[0] - x) <= 6 && Math.abs(q[1] - y) <= 6 && Math.abs(q[2] - z) <= 6) {
                            r.lavaNearStorage++;
                            break;
                        }
                    }
                }
            }
        }

        // ---- craters (possible explosion damage) ----
        if (p.detectExplosionDamage) findCraters(s, o, p, r, top, colKnown, fluidAboveTop);

        // ---- suspicious blocks ----
        r.anchors = r.count(Cat.ANCHOR);
        for (int[] a : o.anchors) {
            if (!s.in(s.relX(a[0]), s.relY(a[1]), s.relZ(a[2]))) r.anchors++;
        }
        for (int[] c : o.crystals) {
            long dx = c[0] - o.centerX, dy = c[1] - o.centerY, dz = c[2] - o.centerZ;
            if (dx * dx + dy * dy + dz * dz <= (long) p.crystalRadius * p.crystalRadius) r.crystalsNear++;
        }
        collectIsolatedObsidian(s, r);
        return r;
    }

    // ------------------------------------------------------------------ helpers

    /** Distance to the first blocking cell, -1 if open for {@code max} cells, -2 if the ray enters unknown space. */
    static int ray(Snapshot s, int x, int y, int z, int dx, int dy, int dz, int max) {
        for (int i = 1; i <= max; i++) {
            int nx = x + dx * i, ny = y + dy * i, nz = z + dz * i;
            if (!s.in(nx, ny, nz)) return -2;
            Cat c = s.get(nx, ny, nz);
            if (c == Cat.UNKNOWN) return -2;
            if (c.blocksRay()) return i;
        }
        return -1;
    }

    private static int remnants(Snapshot s, int x, int y, int z) {
        int n = 0;
        for (int yy = y - 3; yy <= y + 3; yy++) {
            for (int zz = z - 6; zz <= z + 6; zz++) {
                for (int xx = x - 6; xx <= x + 6; xx++) {
                    if (s.get(xx, yy, zz).isStructural()) n++;
                }
            }
        }
        return n;
    }

    /** Flood fill of passable cells. Returns true if the volume is sealed (no sky, no unknown, small, no edge). */
    private static boolean fill(Snapshot s, int[] roomOf, int id, int sx0, int sy0, int sz0, int[] top,
                                boolean[] colKnown, List<Integer> roomVolume) {
        ArrayDeque<int[]> q = new ArrayDeque<>();
        q.add(new int[]{sx0, sy0, sz0});
        roomOf[s.index(sx0, sy0, sz0)] = id;
        int count = 0;
        boolean leak = false;
        while (!q.isEmpty()) {
            int[] c = q.poll();
            count++;
            if (count > MAX_ROOM) leak = true;
            int col = c[0] + c[2] * s.sx;
            if (colKnown[col] && c[1] > top[col]) leak = true; // open to the sky
            for (int[] d : DIRS6) {
                int nx = c[0] + d[0], ny = c[1] + d[1], nz = c[2] + d[2];
                if (!s.in(nx, ny, nz)) {
                    leak = true;
                    continue;
                }
                Cat n = s.get(nx, ny, nz);
                if (n == Cat.UNKNOWN) {
                    leak = true;
                    continue;
                }
                if (!n.passable || n == Cat.DOOR) continue;
                int idx = s.index(nx, ny, nz);
                if (roomOf[idx] != 0) continue;
                if (count > MAX_ROOM) continue; // stop expanding but keep marking what we have
                roomOf[idx] = id;
                q.add(new int[]{nx, ny, nz});
            }
        }
        while (roomVolume.size() <= id) roomVolume.add(0);
        roomVolume.set(id, count);
        return !leak;
    }

    private static int flood26(Snapshot s, boolean[] seen, int[] queue, int x0, int y0, int z0) {
        int head = 0, tail = 0, size = 0;
        queue[tail++] = s.index(x0, y0, z0);
        seen[queue[0]] = true;
        while (head < tail) {
            int idx = queue[head++];
            size++;
            int x = idx % s.sx;
            int z = (idx / s.sx) % s.sz;
            int y = idx / (s.sx * s.sz);
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        int nx = x + dx, ny = y + dy, nz = z + dz;
                        if (!s.in(nx, ny, nz)) continue;
                        int ni = s.index(nx, ny, nz);
                        if (seen[ni] || !s.at(ni).isStructural()) continue;
                        seen[ni] = true;
                        if (tail < queue.length) queue[tail++] = ni;
                    }
                }
            }
        }
        return size;
    }

    private static void collectIsolatedObsidian(Snapshot s, StructureReport r) {
        int seen = 0;
        for (int z = 0; z < s.sz && seen < 400; z++) {
            for (int y = 0; y < s.sy && seen < 400; y++) {
                for (int x = 0; x < s.sx && seen < 400; x++) {
                    if (s.get(x, y, z) != Cat.OBSIDIAN) continue;
                    seen++;
                    boolean other = false, portal = false;
                    for (int dz = -3; dz <= 3 && !(other || portal); dz++) {
                        for (int dy = -3; dy <= 3 && !(other || portal); dy++) {
                            for (int dx = -3; dx <= 3; dx++) {
                                if (dx == 0 && dy == 0 && dz == 0) continue;
                                Cat c = s.get(x + dx, y + dy, z + dz);
                                if (c == Cat.PORTAL) {
                                    portal = true;
                                    break;
                                }
                                if (c == Cat.OBSIDIAN && Math.abs(dx) <= 2 && Math.abs(dy) <= 2 && Math.abs(dz) <= 2) {
                                    other = true;
                                    break;
                                }
                            }
                        }
                    }
                    if (!other && !portal) r.isolatedObsidian.add(new int[]{s.ox + x, s.oy + y, s.oz + z});
                }
            }
        }
    }

    private static void findCraters(Snapshot s, Observation o, Params p, StructureReport r, int[] top,
                                    boolean[] colKnown, boolean[] fluidAboveTop) {
        int cols = s.sx * s.sz;
        int[] surf = new int[cols];
        for (int z = 0; z < s.sz; z++) {
            for (int x = 0; x < s.sx; x++) {
                int col = x + z * s.sx;
                boolean valid = colKnown[col] && top[col] >= 0 && top[col] < s.sy - 1 && !fluidAboveTop[col];
                surf[col] = valid ? top[col] : Integer.MIN_VALUE;
            }
        }
        boolean[] crater = new boolean[cols];
        int[] depth = new int[cols];
        final int ring = 6;
        for (int z = ring; z < s.sz - ring; z++) {
            for (int x = ring; x < s.sx - ring; x++) {
                int col = x + z * s.sx;
                if (surf[col] == Integer.MIN_VALUE) continue;
                double sum = 0;
                int n = 0;
                for (int a = 0; a < 12; a++) {
                    double ang = a * Math.PI / 6.0;
                    int rx = x + (int) Math.round(Math.cos(ang) * ring);
                    int rz = z + (int) Math.round(Math.sin(ang) * ring);
                    int v = surf[rx + rz * s.sx];
                    if (v != Integer.MIN_VALUE) {
                        sum += v;
                        n++;
                    }
                }
                if (n < 9) continue;
                int d = (int) Math.round(sum / n) - surf[col];
                if (d >= p.craterDepth) {
                    crater[col] = true;
                    depth[col] = d;
                }
            }
        }
        boolean[] done = new boolean[cols];
        for (int z = 0; z < s.sz; z++) {
            for (int x = 0; x < s.sx; x++) {
                int col = x + z * s.sx;
                if (!crater[col] || done[col]) continue;
                ArrayDeque<int[]> q = new ArrayDeque<>();
                q.add(new int[]{x, z});
                done[col] = true;
                int area = 0, minX = x, maxX = x, minZ = z, maxZ = z, maxDepth = 0;
                while (!q.isEmpty()) {
                    int[] c = q.poll();
                    area++;
                    int cc = c[0] + c[1] * s.sx;
                    maxDepth = Math.max(maxDepth, depth[cc]);
                    minX = Math.min(minX, c[0]); maxX = Math.max(maxX, c[0]);
                    minZ = Math.min(minZ, c[1]); maxZ = Math.max(maxZ, c[1]);
                    for (int[] d : DIRS4) {
                        int nx = c[0] + d[0], nz = c[1] + d[1];
                        if (nx < 0 || nz < 0 || nx >= s.sx || nz >= s.sz) continue;
                        int nc = nx + nz * s.sx;
                        if (crater[nc] && !done[nc]) {
                            done[nc] = true;
                            q.add(new int[]{nx, nz});
                        }
                    }
                }
                int w = maxX - minX + 1, h = maxZ - minZ + 1;
                double aspect = Math.max(w, h) / (double) Math.min(w, h);
                double fillRatio = area / (double) (w * h);
                if (area < 10 || aspect > 2.5 || fillRatio < 0.5) continue; // not round: probably natural terrain
                int cxw = s.ox + (minX + maxX) / 2, czw = s.oz + (minZ + maxZ) / 2;
                long dx = cxw - o.centerX, dz = czw - o.centerZ;
                if (dx * dx + dz * dz > 24L * 24L) continue; // too far from the storage to matter
                // Needs building remnants around the pit, otherwise it is just a hole in the ground.
                int rem = 0;
                for (int zz = minZ - 3; zz <= maxZ + 3; zz++) {
                    for (int xx = minX - 3; xx <= maxX + 3; xx++) {
                        for (int yy = 0; yy < s.sy; yy++) {
                            if (s.get(xx, yy, zz).isStructural()) rem++;
                        }
                    }
                }
                if (rem >= 6) r.craters.add(new int[]{cxw, czw, area, maxDepth});
            }
        }
    }
}
