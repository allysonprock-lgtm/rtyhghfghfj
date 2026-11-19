package dev.basefinder.core;

import java.util.ArrayList;
import java.util.List;

/** Complete, explainable analysis of one candidate base. The three scores are kept separate on purpose. */
public final class BaseReport {
    public enum Condition {
        INTACT("Intact"), MOSTLY_INTACT("Mostly intact"), ROUGH("Rough condition"),
        PARTIALLY_DAMAGED("Partially damaged"), SIGNIFICANTLY_DAMAGED("Significantly damaged"),
        LIKELY_GRIEFED("Likely griefed"), UNCERTAIN("Uncertain");

        public final String label;

        Condition(String label) {
            this.label = label;
        }
    }

    /** The three Discord destinations (plus REJECTED = not sent). */
    public enum BaseClass {
        INTACT("Intact Base"), SUSPICIOUS("Suspicious Base"), GRIEFED("Likely Griefed Base"), REJECTED("Rejected");

        public final String label;

        BaseClass(String label) {
            this.label = label;
        }
    }

    public enum Outcome { GOOD_POTENTIAL, SUSPICIOUS, REJECTED }

    public static final class Indicator {
        public final String id;
        public final String text;
        public final double severity;

        public Indicator(String id, String text, double severity) {
            this.id = id;
            this.text = text;
            this.severity = severity;
        }
    }

    public String id = "";
    public String dimension = "";
    public int x, y, z;

    public StorageReport storage;
    public StructureReport structure;

    // A. base likelihood (0-100)
    public double likelihood;
    public final List<String> likelihoodReasons = new ArrayList<>();

    // B. structure condition
    public Condition condition = Condition.UNCERTAIN;
    public double conditionScore;   // 0-100, higher = better preserved
    public double damageScore;      // 0-100, higher = more damage
    public final List<Indicator> indicators = new ArrayList<>();
    public final List<String> conditionReasons = new ArrayList<>();

    // C. functional infrastructure (0-100)
    public double infraScore;
    public final List<String> infraReasons = new ArrayList<>();

    // suspicious blocks
    public int crystals, anchors, isolatedObsidian, tnt;
    public double penaltyTotal;
    public final List<String> suspicious = new ArrayList<>();

    // classification
    public BaseClass classification = BaseClass.SUSPICIOUS;
    public Outcome outcome = Outcome.SUSPICIOUS;
    public final List<String> classReasons = new ArrayList<>();
    public final List<String> rejectionReasons = new ArrayList<>();

    public double coverage;
    public final List<String> dataQuality = new ArrayList<>();
}
