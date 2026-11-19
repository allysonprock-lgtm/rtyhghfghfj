package dev.basefinder.core;

import java.util.List;

/** Groups storage units into clusters (single linkage) and computes counts, density and concentration. */
public final class StorageAnalyzer {
    private StorageAnalyzer() {}

    public static StorageReport analyze(List<StoragePoint> pts, int clusterDistance) {
        StorageReport r = new StorageReport();
        int n = Math.min(pts.size(), 3000);

        for (int i = 0; i < n; i++) {
            StoragePoint p = pts.get(i);
            switch (p.kind()) {
                case CHEST -> {
                    r.chests++;
                    if (p.doubleChest()) r.doubleChests++;
                }
                case TRAPPED_CHEST -> {
                    r.chests++;
                    r.trappedChests++;
                    if (p.doubleChest()) r.doubleChests++;
                }
                case BARREL -> r.barrels++;
                case SHULKER -> r.shulkers++;
            }
        }
        r.total = r.chests + r.barrels + r.shulkers;
        if (n == 0) return r;

        // union-find
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        long d2 = (long) clusterDistance * clusterDistance;
        double nearestSum = 0;
        for (int i = 0; i < n; i++) {
            StoragePoint a = pts.get(i);
            long best = Long.MAX_VALUE;
            for (int j = 0; j < n; j++) {
                if (i == j) continue;
                StoragePoint b = pts.get(j);
                long dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
                long dd = dx * dx + dy * dy + dz * dz;
                if (dd < best) best = dd;
                if (j > i && dd <= d2) union(parent, i, j);
            }
            nearestSum += best == Long.MAX_VALUE ? 0 : Math.sqrt(best);
        }
        r.avgNearestDistance = n > 1 ? nearestSum / n : 0;

        java.util.Map<Integer, StorageReport.Cluster> map = new java.util.HashMap<>();
        java.util.Map<Integer, long[]> sums = new java.util.HashMap<>();
        for (int i = 0; i < n; i++) {
            int root = find(parent, i);
            StoragePoint p = pts.get(i);
            StorageReport.Cluster c = map.get(root);
            if (c == null) {
                c = new StorageReport.Cluster();
                c.minX = c.maxX = p.x();
                c.minY = c.maxY = p.y();
                c.minZ = c.maxZ = p.z();
                map.put(root, c);
                sums.put(root, new long[3]);
            }
            c.count++;
            c.minX = Math.min(c.minX, p.x()); c.maxX = Math.max(c.maxX, p.x());
            c.minY = Math.min(c.minY, p.y()); c.maxY = Math.max(c.maxY, p.y());
            c.minZ = Math.min(c.minZ, p.z()); c.maxZ = Math.max(c.maxZ, p.z());
            long[] s = sums.get(root);
            s[0] += p.x(); s[1] += p.y(); s[2] += p.z();
            switch (p.kind()) {
                case CHEST, TRAPPED_CHEST -> c.chests++;
                case BARREL -> c.barrels++;
                case SHULKER -> c.shulkers++;
            }
        }
        int inClusters3 = 0;
        for (java.util.Map.Entry<Integer, StorageReport.Cluster> e : map.entrySet()) {
            StorageReport.Cluster c = e.getValue();
            long[] s = sums.get(e.getKey());
            c.cx = (int) Math.floorDiv(s[0], (long) c.count);
            c.cy = (int) Math.floorDiv(s[1], (long) c.count);
            c.cz = (int) Math.floorDiv(s[2], (long) c.count);
            double vol = (double) (c.maxX - c.minX + 1) * (c.maxY - c.minY + 1) * (c.maxZ - c.minZ + 1);
            c.density = c.count * 100.0 / vol;
            r.clusters.add(c);
            if (c.count == 1) r.isolated++;
            if (c.count >= 6) r.concentratedRooms++;
            if (c.count >= 2 && c.mixed()) r.mixedClusters++;
            if (c.count >= 3) inClusters3 += c.count;
        }
        r.clusters.sort((a, b) -> Integer.compare(b.count, a.count));
        r.largest = r.clusters.isEmpty() ? null : r.clusters.get(0);
        r.densityOfLargest = r.largest == null ? 0 : r.largest.density;
        r.concentration = n == 0 ? 0 : inClusters3 / (double) n;
        return r;
    }

    private static int find(int[] p, int i) {
        while (p[i] != i) {
            p[i] = p[p[i]];
            i = p[i];
        }
        return i;
    }

    private static void union(int[] p, int a, int b) {
        int ra = find(p, a), rb = find(p, b);
        if (ra != rb) p[ra] = rb;
    }
}
