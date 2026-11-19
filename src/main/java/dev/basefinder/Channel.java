package dev.basefinder;

/** Every independent Discord destination. Base channels never fall back to each other. */
public enum Channel {
    INTACT("Intact Bases", true),
    SUSPICIOUS("Suspicious Bases", true),
    GRIEFED("Likely Griefed Bases", true),
    PLAYER("Player Alerts", false),
    PEARL("Ender Pearl Alerts", false),
    DEATH("Death Alerts", false),
    FALLBACK("Priority Fallback", false);

    public final String label;
    /** True for the three ordinary base-classification channels. */
    public final boolean base;

    Channel(String label, boolean base) {
        this.label = label;
        this.base = base;
    }
}
