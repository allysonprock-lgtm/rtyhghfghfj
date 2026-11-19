package dev.basefinder.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Result of {@link StorageAnalyzer}. A double chest counts as ONE storage unit. */
public final class StorageReport {
    public static final class Cluster {
        public int count;
        public int cx, cy, cz;
        public int minX, minY, minZ, maxX, maxY, maxZ;
        public int chests, barrels, shulkers;
        /** Storage units per 100 blocks of bounding-box volume. */
        public double density;

        public boolean mixed() {
            int kinds = (chests > 0 ? 1 : 0) + (barrels > 0 ? 1 : 0) + (shulkers > 0 ? 1 : 0);
            return kinds >= 2;
        }
    }

    public int chests;        // chests + trapped chests, double = 1
    public int trappedChests; // subset of chests
    public int doubleChests;  // subset of chests
    public int barrels;
    public int shulkers;
    public int total;

    public final List<Cluster> clusters = new ArrayList<>();
    public Cluster largest;
    public int isolated;          // clusters of exactly one unit
    public int concentratedRooms; // clusters of 6+ units
    public int mixedClusters;
    public double avgNearestDistance;
    /** Fraction of units that sit in clusters of 3 or more. 1.0 = fully concentrated. */
    public double concentration;
    public double densityOfLargest;

    /** Always false: the client only receives container contents when the player opens a container. */
    public final boolean contentsAvailable = false;

    public Map<String, Integer> distribution() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("Chests", chests);
        m.put("Barrels", barrels);
        m.put("Shulker boxes", shulkers);
        return m;
    }
}
