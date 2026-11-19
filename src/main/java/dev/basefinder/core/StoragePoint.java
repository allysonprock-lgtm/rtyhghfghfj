package dev.basefinder.core;

/** One storage unit (a double chest is ONE unit). Positions are world coordinates. */
public record StoragePoint(int x, int y, int z, Kind kind, boolean doubleChest) {
    public enum Kind { CHEST, TRAPPED_CHEST, BARREL, SHULKER }
}
