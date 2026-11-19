package dev.basefinder.core;

import java.util.ArrayList;
import java.util.List;

/** Raw structural measurements (no judgement yet). Everything here was computed from observed blocks. */
public final class StructureReport {
    public double coverage;
    public int knownCells, totalCells;
    public int[] counts = new int[Cat.values().length];

    public int structural;       // player-style building blocks
    public int naturalSolid;

    // storage placement measurements (only storage with enough observed surroundings is evaluated)
    public int evaluated;        // storage units evaluated
    public int unevaluated;      // storage units we could not judge (unloaded surroundings)
    public int housed;           // roof above and walls on >= 2 sides
    public int sealed;           // roof above and walls on >= 3 sides
    public int roofMissing;      // walls on >= 2 sides but no roof
    public int roofedOpen;       // roof but walls on <= 1 side
    public int breached;         // roof, <= 1 wall, but lots of building remnants nearby
    public int floating;         // known air directly below
    public int floatEvaluated;

    public int debris;           // structural blocks with >= 5 open sides
    public List<int[]> craters = new ArrayList<>(); // {centerX, centerZ, area, depth} world coords
    public int fire, tnt, lavaNearStorage;

    public int sealedRooms;
    public int largestRoomVolume;
    public int storageInSealedRooms;
    public double undergroundFraction;

    public int buildings;        // connected structural components of >= 20 blocks
    public int largestComponent;
    public int doors;
    public int farmland, crops;
    public int infraCells;

    public final List<int[]> isolatedObsidian = new ArrayList<>();
    public int anchors;          // respawn anchors inside the snapshot or reported nearby
    public int crystalsNear;
    public int livestock;

    public int count(Cat c) {
        return counts[c.ordinal()];
    }
}
