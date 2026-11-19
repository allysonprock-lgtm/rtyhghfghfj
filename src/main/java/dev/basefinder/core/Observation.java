package dev.basefinder.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Everything the client observed around a candidate base. Built on the game thread, analysed anywhere. */
public final class Observation {
    public Snapshot snap;
    public final List<StoragePoint> storage = new ArrayList<>();
    /** Live end crystal positions (absolute x,y,z) the client currently knows about. */
    public final List<int[]> crystals = new ArrayList<>();
    /** Respawn anchors known from chunk scans but possibly outside the snapshot box. */
    public final List<int[]> anchors = new ArrayList<>();
    /** Block entity id -> count within the observed region (furnaces, hoppers, beds...). */
    public final Map<String, Integer> blockEntities = new HashMap<>();
    public int livestock;
    public int bells;
    public int centerX, centerY, centerZ;
}
