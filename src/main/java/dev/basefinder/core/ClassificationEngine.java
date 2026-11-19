package dev.basefinder.core;

import java.util.Locale;

/**
 * The single place that decides where a base report goes: INTACT, SUSPICIOUS, GRIEFED or REJECTED.
 * Base likelihood and structure condition are inputs but never merged: a 95% likelihood base with heavy damage
 * is routed to GRIEFED, not INTACT. Unknown or uncertain cases fall back to SUSPICIOUS.
 */
public final class ClassificationEngine {
    private ClassificationEngine() {}

    public static void classify(BaseReport r, Params p) {
        r.classReasons.clear();
        r.rejectionReasons.clear();
        String mode = p.mode == null ? "BALANCED" : p.mode.toUpperCase(Locale.ROOT);
        double intactMin = p.intactMinLikelihood + ("STRICT".equals(mode) ? 10 : "LENIENT".equals(mode) ? -10 : 0);
        double damageMax = "STRICT".equals(mode) ? 15 : "LENIENT".equals(mode) ? 35 : 25;

        // ---- hard rejection rules ----
        if (p.rejectEndCrystals && r.crystals > 0) {
            r.rejectionReasons.add("Live end crystal(s) within " + p.crystalRadius + " blocks (rejectEndCrystals is on)");
        }
        if (p.rejectRespawnAnchors && r.anchors > 0) {
            r.rejectionReasons.add(r.anchors + " respawn anchor(s) present (rejectRespawnAnchors is on)");
        }
        if (p.rejectIsolatedObsidian && r.isolatedObsidian > 0) {
            r.rejectionReasons.add(r.isolatedObsidian + " isolated obsidian block(s) (rejectIsolatedObsidian is on)");
        }
        boolean enoughData = r.coverage >= p.minCoverage;
        if (r.likelihood < p.minimumBaseLikelihood && enoughData) {
            r.rejectionReasons.add(String.format(Locale.ROOT, "Base likelihood %.0f%% is below the minimum %.0f%%", r.likelihood, p.minimumBaseLikelihood));
        }
        if (!r.rejectionReasons.isEmpty()) {
            r.classification = BaseReport.BaseClass.REJECTED;
            r.outcome = BaseReport.Outcome.REJECTED;
            r.classReasons.addAll(r.rejectionReasons);
            return;
        }

        // ---- likely griefed: needs strong, multi-indicator evidence (decided in the condition analysis) ----
        if (r.condition == BaseReport.Condition.LIKELY_GRIEFED) {
            r.classification = BaseReport.BaseClass.GRIEFED;
            r.outcome = BaseReport.Outcome.SUSPICIOUS;
            r.classReasons.add(String.format(Locale.ROOT, "Likely griefed: damage score %.0f with %d independent indicators, while base likelihood is %.0f%%",
                    r.damageScore, r.indicators.size(), r.likelihood));
            for (BaseReport.Indicator i : r.indicators) r.classReasons.add(i.text);
            return;
        }

        // Low likelihood caused by missing data is not evidence against a base: keep it as SUSPICIOUS.
        // ---- intact: every criterion must pass ----
        java.util.List<String> blockers = new java.util.ArrayList<>();
        if (r.likelihood < intactMin) {
            blockers.add(String.format(Locale.ROOT, "base likelihood %.0f%% is below %.0f%% needed for the intact channel", r.likelihood, intactMin));
        }
        if (r.conditionScore < p.minimumBaseQuality) {
            blockers.add(String.format(Locale.ROOT, "structure condition score %.0f is below the minimum quality %.0f", r.conditionScore, p.minimumBaseQuality));
        }
        if (p.requireIntactCondition && r.condition != BaseReport.Condition.INTACT
                && r.condition != BaseReport.Condition.MOSTLY_INTACT) {
            blockers.add("condition is '" + r.condition.label + "'");
        }
        if (r.coverage < p.minCoverage) {
            blockers.add("only " + (int) Math.round(r.coverage * 100) + "% of the area is observed");
        }
        if (r.damageScore > damageMax) {
            blockers.add(String.format(Locale.ROOT, "damage score %.0f is above %.0f (%s mode)", r.damageScore, damageMax, mode));
        }
        if (r.penaltyTotal > 0) {
            blockers.add("suspicious blocks were found: " + String.join("; ", r.suspicious));
        }

        if (blockers.isEmpty()) {
            r.classification = BaseReport.BaseClass.INTACT;
            r.outcome = BaseReport.Outcome.GOOD_POTENTIAL;
            r.classReasons.add(String.format(Locale.ROOT, "Intact: likelihood %.0f%%, condition '%s' (score %.0f), damage %.0f, no suspicious blocks",
                    r.likelihood, r.condition.label, r.conditionScore, r.damageScore));
        } else {
            r.classification = BaseReport.BaseClass.SUSPICIOUS;
            r.outcome = BaseReport.Outcome.SUSPICIOUS;
            r.classReasons.add("Suspicious (not enough evidence for the intact channel, not enough for griefed):");
            for (String b : blockers) r.classReasons.add("- " + b);
        }
    }
}
