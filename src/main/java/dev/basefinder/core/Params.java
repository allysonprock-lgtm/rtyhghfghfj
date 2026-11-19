package dev.basefinder.core;

/** All tunable analysis numbers in one place. Copied from the config on the game thread, then used anywhere. */
public final class Params {
    // storage
    public int clusterDistance = 6;

    // likelihood weights (they should add up to about 100)
    public double wStorage = 30, wConcentration = 15, wInfra = 15, wFarm = 5, wEnclosure = 15,
            wUnderground = 5, wBuilt = 10, wConnected = 5;

    // suspicious blocks
    public boolean rejectEndCrystals = true;
    public boolean penalizeEndCrystals = true;
    public double endCrystalPenalty = 35;
    public int crystalRadius = 48;
    public boolean rejectRespawnAnchors = true;
    public double anchorPenalty = 60;
    public boolean rejectIsolatedObsidian = true;
    public double obsidianPenalty = 40;

    // damage
    public boolean detectExplosionDamage = true;
    public int craterDepth = 3;
    public double damagePenalty = 1.0;
    public double griefedDamageThreshold = 60;
    public int griefedMinIndicators = 3;
    public double minCoverageForGriefed = 0.6;

    // routing
    public double minimumBaseLikelihood = 30;
    public double intactMinLikelihood = 55;
    public double minimumBaseQuality = 55;
    public double minCoverage = 0.5;
    public boolean requireIntactCondition = true;
    /** STRICT, BALANCED or LENIENT. */
    public String mode = "BALANCED";

    public Params copy() {
        Params p = new Params();
        p.clusterDistance = clusterDistance;
        p.wStorage = wStorage; p.wConcentration = wConcentration; p.wInfra = wInfra; p.wFarm = wFarm;
        p.wEnclosure = wEnclosure; p.wUnderground = wUnderground; p.wBuilt = wBuilt; p.wConnected = wConnected;
        p.rejectEndCrystals = rejectEndCrystals; p.penalizeEndCrystals = penalizeEndCrystals;
        p.endCrystalPenalty = endCrystalPenalty; p.crystalRadius = crystalRadius;
        p.rejectRespawnAnchors = rejectRespawnAnchors; p.anchorPenalty = anchorPenalty;
        p.rejectIsolatedObsidian = rejectIsolatedObsidian; p.obsidianPenalty = obsidianPenalty;
        p.detectExplosionDamage = detectExplosionDamage; p.craterDepth = craterDepth;
        p.damagePenalty = damagePenalty; p.griefedDamageThreshold = griefedDamageThreshold;
        p.griefedMinIndicators = griefedMinIndicators; p.minCoverageForGriefed = minCoverageForGriefed;
        p.minimumBaseLikelihood = minimumBaseLikelihood; p.intactMinLikelihood = intactMinLikelihood;
        p.minimumBaseQuality = minimumBaseQuality; p.minCoverage = minCoverage;
        p.requireIntactCondition = requireIntactCondition; p.mode = mode;
        return p;
    }
}
