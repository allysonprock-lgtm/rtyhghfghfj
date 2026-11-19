package dev.basefinder.core;

import java.util.Locale;
import java.util.Map;

/**
 * Turns an {@link Observation} into a {@link BaseReport}: storage clusters, structure measurements, then the three
 * independent scores (likelihood, condition, infrastructure) with reasons. Deterministic for the same input.
 */
public final class BaseScoringEngine {
    private BaseScoringEngine() {}

    public static BaseReport analyze(Observation o, Params p) {
        BaseReport r = new BaseReport();
        r.dimension = o.snap.dimension;
        r.x = o.centerX;
        r.y = o.centerY;
        r.z = o.centerZ;
        r.storage = StorageAnalyzer.analyze(o.storage, p.clusterDistance);
        r.structure = StructureAnalyzer.analyze(o, p);
        r.coverage = r.structure.coverage;

        scoreLikelihood(o, r, p);
        scoreInfrastructure(o, r);
        scoreCondition(r, p);
        suspiciousBlocks(r, p);
        // penalties come off the likelihood after the weighted sum
        r.likelihood = clamp(r.likelihood - r.penaltyTotal);
        dataQuality(r);
        ClassificationEngine.classify(r, p);
        return r;
    }

    static double clamp(double v) {
        return Math.max(0, Math.min(100, v));
    }

    private static String f1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    // ------------------------------------------------------------------ A. base likelihood

    private static void scoreLikelihood(Observation o, BaseReport r, Params p) {
        StorageReport sr = r.storage;
        StructureReport st = r.structure;
        double wSum = p.wStorage + p.wConcentration + p.wInfra + p.wFarm + p.wEnclosure + p.wUnderground
                + p.wBuilt + p.wConnected;
        if (wSum <= 0) wSum = 1;
        double scale = 100.0 / wSum;
        double sum = 0;

        double fStorage = Math.min(1, sr.total / 20.0);
        double pStorage = p.wStorage * scale * fStorage;
        sum += pStorage;
        r.likelihoodReasons.add(String.format(Locale.ROOT, "[observed] +%s storage: %d units (%d chests incl. %d double, %d barrels, %d shulkers)",
                f1(pStorage), sr.total, sr.chests, sr.doubleChests, sr.barrels, sr.shulkers));

        int largest = sr.largest == null ? 0 : sr.largest.count;
        double fConc = sr.concentration * Math.min(1, largest / 8.0);
        double pConc = p.wConcentration * scale * fConc;
        sum += pConc;
        r.likelihoodReasons.add(String.format(Locale.ROOT, "[observed] +%s concentration: largest cluster %d units, %d%% of storage clustered",
                f1(pConc), largest, (int) Math.round(sr.concentration * 100)));

        double fInfra = Math.min(1, st.infraCells / 8.0);
        double pInfra = p.wInfra * scale * fInfra;
        sum += pInfra;
        r.likelihoodReasons.add(String.format(Locale.ROOT, "[observed] +%s workstations/machines: %d blocks", f1(pInfra), st.infraCells));

        double fFarm = Math.min(1, (st.farmland + st.crops) / 40.0 + Math.min(1, st.livestock / 16.0) * 0.3);
        double pFarm = p.wFarm * scale * Math.min(1, fFarm);
        sum += pFarm;
        r.likelihoodReasons.add(String.format(Locale.ROOT, "[observed] +%s farms: %d farmland/crop blocks, %d animals nearby",
                f1(pFarm), st.farmland + st.crops, st.livestock));

        double housedRatio = st.evaluated >= 2 ? st.housed / (double) st.evaluated : 0;
        double sealedRatio = st.evaluated >= 2 ? st.storageInSealedRooms / (double) st.evaluated : 0;
        double fEnc = Math.min(1, 0.7 * housedRatio + 0.3 * sealedRatio);
        double pEnc = p.wEnclosure * scale * fEnc;
        sum += pEnc;
        r.likelihoodReasons.add(String.format(Locale.ROOT, "[inferred] +%s enclosure: %d of %d evaluated storage units have a roof and walls, %d sealed rooms",
                f1(pEnc), st.housed, st.evaluated, st.sealedRooms));

        double pUg = p.wUnderground * scale * Math.min(1, st.undergroundFraction);
        sum += pUg;
        r.likelihoodReasons.add(String.format(Locale.ROOT, "[inferred] +%s underground: %d%% of storage has 6+ solid blocks overhead",
                f1(pUg), (int) Math.round(st.undergroundFraction * 100)));

        double pBuilt = p.wBuilt * scale * Math.min(1, st.structural / 300.0);
        sum += pBuilt;
        r.likelihoodReasons.add(String.format(Locale.ROOT, "[inferred] +%s built blocks: %d player-style blocks (block types, not proof of placement)",
                f1(pBuilt), st.structural));

        double pConn = p.wConnected * scale * Math.min(1, st.largestComponent / 200.0);
        sum += pConn;
        r.likelihoodReasons.add(String.format(Locale.ROOT, "[inferred] +%s connected buildings: %d building(s), largest %d blocks",
                f1(pConn), st.buildings, st.largestComponent));

        r.likelihood = clamp(sum);
    }

    // ------------------------------------------------------------------ C. functional infrastructure

    private static int be(Observation o, String id) {
        Integer v = o.blockEntities.get(id);
        if (v == null) v = o.blockEntities.get("minecraft:" + id);
        return v == null ? 0 : v;
    }

    private static void scoreInfrastructure(Observation o, BaseReport r) {
        StructureReport st = r.structure;
        double score = 0;

        int smelt = be(o, "furnace") + be(o, "blast_furnace") + be(o, "smoker");
        double a = 25.0 * Math.min(1, smelt / 6.0);
        score += a;
        r.infraReasons.add("[observed] +" + f1(a) + " smelting: " + smelt + " furnaces/smokers/blast furnaces");

        double b = 20.0 * Math.min(1, st.infraCells / 10.0);
        score += b;
        r.infraReasons.add("[observed] +" + f1(b) + " workstations/machines: " + st.infraCells + " blocks");

        int magic = be(o, "enchanting_table") + be(o, "brewing_stand");
        double c = 10.0 * Math.min(1, magic / 3.0);
        score += c;
        r.infraReasons.add("[observed] +" + f1(c) + " enchanting/brewing: " + magic);

        int redstone = be(o, "hopper") + be(o, "dropper") + be(o, "dispenser") + be(o, "crafter");
        double d = 10.0 * Math.min(1, redstone / 10.0);
        score += d;
        r.infraReasons.add("[observed] +" + f1(d) + " hoppers/droppers/dispensers/crafters: " + redstone);

        double e = 20.0 * Math.min(1, (st.farmland + st.crops) / 60.0);
        score += e;
        r.infraReasons.add("[observed] +" + f1(e) + " farmland/crops: " + (st.farmland + st.crops));

        int beds = st.count(Cat.BED);
        double f = 5.0 * Math.min(1, beds / 4.0);
        score += f;
        r.infraReasons.add("[observed] +" + f1(f) + " beds: " + beds);

        double g = 5.0 * Math.min(1, st.livestock / 8.0);
        score += g;
        r.infraReasons.add("[observed] +" + f1(g) + " animals nearby (possible pens): " + st.livestock);

        int special = be(o, "beacon") * 2 + be(o, "conduit");
        double h = 5.0 * Math.min(1, special / 2.0);
        score += h;
        r.infraReasons.add("[observed] +" + f1(h) + " beacons/conduits: " + (be(o, "beacon") + be(o, "conduit")));

        r.infraScore = clamp(score);
    }

    // ------------------------------------------------------------------ B. structure condition

    private static void scoreCondition(BaseReport r, Params p) {
        StructureReport st = r.structure;
        StorageReport sr = r.storage;

        if (st.breached >= 3 && st.evaluated > 0 && st.breached / (double) st.evaluated >= 0.3) {
            r.indicators.add(new BaseReport.Indicator("walls_breached",
                    "[inferred] " + st.breached + " roofed storage units have almost no walls although many building blocks remain nearby", 22));
        }
        if (st.floating >= 2 && st.floatEvaluated > 0 && st.floating / (double) st.floatEvaluated >= 0.2) {
            r.indicators.add(new BaseReport.Indicator("floating_storage",
                    "[observed] " + st.floating + " storage units have air directly below them (support destroyed)", 20));
        }
        if (st.debris >= 12 && st.debris / (double) Math.max(1, st.structural) >= 0.12) {
            r.indicators.add(new BaseReport.Indicator("fragmented",
                    "[observed] " + st.debris + " loose building fragments (" + (int) Math.round(100.0 * st.debris / Math.max(1, st.structural)) + "% of built blocks)", 18));
        }
        double craterSev = 0;
        for (int[] c : st.craters) {
            craterSev += 30;
            r.indicators.add(new BaseReport.Indicator("crater",
                    "[inferred] Possible explosion crater near " + c[0] + ", " + c[1] + " (" + c[2] + " columns, up to " + c[3] + " blocks deep)", 30));
            if (craterSev >= 45) break;
        }
        if (st.fire >= 3) {
            r.indicators.add(new BaseReport.Indicator("fire", "[observed] " + st.fire + " fire blocks in the area", 8));
        }
        if (st.lavaNearStorage >= 3) {
            r.indicators.add(new BaseReport.Indicator("lava", "[observed] " + st.lavaNearStorage + " lava blocks next to storage", 10));
        }

        // A roofless walled area is ambiguous: it may simply be unfinished. It only counts as real damage when
        // other damage evidence agrees (rough is not griefed).
        int withWalls = st.housed + st.roofMissing;
        if (st.roofMissing >= 3 && withWalls > 0 && st.roofMissing / (double) withWalls >= 0.5) {
            boolean corroborated = false;
            for (BaseReport.Indicator i : r.indicators) {
                if (i.id.equals("walls_breached") || i.id.equals("floating_storage") || i.id.equals("crater")
                        || i.id.equals("fragmented")) corroborated = true;
            }
            if (corroborated) {
                r.indicators.add(new BaseReport.Indicator("roof_missing",
                        "[observed] Roof missing above " + st.roofMissing + " of " + withWalls + " storage units that still have walls", 25));
            } else {
                r.indicators.add(new BaseReport.Indicator("roof_open",
                        "[inferred] No roof above " + st.roofMissing + " of " + withWalls + " walled storage units (may just be unfinished)", 8));
            }
        }

        double raw = 0;
        boolean structural = false;
        for (BaseReport.Indicator i : r.indicators) {
            raw += i.severity;
            if (i.id.equals("roof_missing") || i.id.equals("walls_breached") || i.id.equals("crater")) structural = true;
        }
        r.damageScore = clamp(raw * p.damagePenalty);

        double housedRatio = st.evaluated > 0 ? st.housed / (double) st.evaluated : -1;
        r.conditionScore = housedRatio < 0 ? clamp(100 - r.damageScore)
                : clamp(0.65 * (100 - r.damageScore) + 0.35 * housedRatio * 100);

        boolean enough = st.evaluated >= 2 && r.coverage >= p.minCoverage;
        BaseReport.Condition c;
        if (!enough) {
            c = BaseReport.Condition.UNCERTAIN;
            r.conditionReasons.add("[unknown] Not enough observed data: " + st.evaluated + " storage units could be evaluated, "
                    + pct(r.coverage) + " of the area loaded (need " + pct(p.minCoverage) + " and 2+ units)");
        } else if (r.damageScore >= p.griefedDamageThreshold && r.indicators.size() >= p.griefedMinIndicators
                && r.coverage >= p.minCoverageForGriefed && structural) {
            c = BaseReport.Condition.LIKELY_GRIEFED;
            r.conditionReasons.add("[inferred] Damage score " + (int) r.damageScore + " with " + r.indicators.size()
                    + " independent indicators (needs " + (int) p.griefedDamageThreshold + " and " + p.griefedMinIndicators + ")");
        } else if (r.damageScore >= 45) {
            c = BaseReport.Condition.SIGNIFICANTLY_DAMAGED;
        } else if (r.damageScore >= 25) {
            c = BaseReport.Condition.PARTIALLY_DAMAGED;
        } else if (r.damageScore <= 10 && housedRatio >= 0.7) {
            c = BaseReport.Condition.INTACT;
        } else if (r.damageScore <= 20 && housedRatio >= 0.5) {
            c = BaseReport.Condition.MOSTLY_INTACT;
        } else {
            c = BaseReport.Condition.ROUGH;
            r.conditionReasons.add("[inferred] Looks rough or unfinished (few roofed storage units) but no damage indicators. Rough is not griefed.");
        }
        r.condition = c;
        if (r.indicators.isEmpty() && enough) {
            r.conditionReasons.add("[observed] No damage indicators found in the observed area");
        }
        for (BaseReport.Indicator i : r.indicators) r.conditionReasons.add(i.text);
    }

    private static String pct(double v) {
        return (int) Math.round(v * 100) + "%";
    }

    // ------------------------------------------------------------------ suspicious blocks

    private static void suspiciousBlocks(BaseReport r, Params p) {
        StructureReport st = r.structure;
        r.crystals = st.crystalsNear;
        r.anchors = st.anchors;
        r.isolatedObsidian = st.isolatedObsidian.size();
        r.tnt = st.tnt;

        if (r.crystals > 0) {
            r.suspicious.add("[observed] " + r.crystals + " live end crystal(s) within " + p.crystalRadius + " blocks (entities, not items)");
            if (p.penalizeEndCrystals) r.penaltyTotal += p.endCrystalPenalty;
        }
        if (r.anchors > 0) {
            r.suspicious.add("[observed] " + r.anchors + " respawn anchor(s)");
            r.penaltyTotal += p.anchorPenalty;
        }
        if (r.isolatedObsidian > 0) {
            r.suspicious.add("[observed] " + r.isolatedObsidian + " isolated single obsidian block(s) (no other obsidian within 2 blocks, no portal)");
            r.penaltyTotal += p.obsidianPenalty;
        }
        if (r.tnt > 0) {
            r.suspicious.add("[observed] " + r.tnt + " TNT block(s)");
        }
    }

    private static void dataQuality(BaseReport r) {
        double unknown = 1 - r.coverage;
        r.dataQuality.add("Observed: " + pct(r.coverage) + " of the surrounding volume was loaded; " + pct(unknown)
                + " is unknown/unloaded and was never treated as damage.");
        r.dataQuality.add("Inferred: 'built' blocks, enclosure, craters and damage are derived from block types and shapes.");
        r.dataQuality.add("Unavailable: Inventory contents unavailable (the client only receives contents when a container is opened).");
    }

    // used by tests and the embed code
    public static Map<String, Integer> beCounts(Observation o) {
        return o.blockEntities;
    }
}
