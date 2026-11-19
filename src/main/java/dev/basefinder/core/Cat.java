package dev.basefinder.core;

/** Coarse block categories used by the analysis. No Minecraft types here, so the logic is testable on its own. */
public enum Cat {
    UNKNOWN(false, false),   // not loaded / not observed
    AIR(true, false), PLANT(true, false), DECOR(true, false), LIGHT(true, false), CROP(true, false),
    WATER(true, false), LAVA(true, false), FIRE(true, false), DOOR(true, false), PORTAL(true, false),
    NATURAL(false, true), BUILT(false, true), GLASS(false, true), FARMLAND(false, true),
    CHEST(false, true), BARREL(false, true), SHULKER(false, true), INFRA(false, true), BED(false, true),
    TNT(false, true), OBSIDIAN(false, true), ANCHOR(false, true);

    public final boolean passable;
    public final boolean solid;

    Cat(boolean passable, boolean solid) {
        this.passable = passable;
        this.solid = solid;
    }

    public boolean isStorage() {
        return this == CHEST || this == BARREL || this == SHULKER;
    }

    /** Evidence of player construction that forms walls, floors and roofs. */
    public boolean isStructural() {
        return this == BUILT || this == GLASS || this == DOOR || isStorage() || this == INFRA || this == BED;
    }

    /** Blocks that stop a ray when looking for walls/roofs (solid blocks and doors). */
    public boolean blocksRay() {
        return solid || this == DOOR;
    }
}
