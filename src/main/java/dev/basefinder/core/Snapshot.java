package dev.basefinder.core;

/** An immutable-by-convention 3D grid of {@link Cat} values the client actually observed (UNKNOWN = not loaded). */
public final class Snapshot {
    private static final Cat[] CATS = Cat.values();

    public final String dimension;
    public final int ox, oy, oz;
    public final int sx, sy, sz;
    private final byte[] cells;

    public Snapshot(String dimension, int ox, int oy, int oz, int sx, int sy, int sz) {
        this.dimension = dimension;
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        this.cells = new byte[sx * sy * sz]; // zero = UNKNOWN
    }

    public int index(int x, int y, int z) {
        return x + sx * (z + sz * y);
    }

    public boolean in(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < sx && y < sy && z < sz;
    }

    public Cat get(int x, int y, int z) {
        return in(x, y, z) ? CATS[cells[index(x, y, z)]] : Cat.UNKNOWN;
    }

    public Cat at(int index) {
        return CATS[cells[index]];
    }

    public void set(int x, int y, int z, Cat c) {
        if (in(x, y, z)) cells[index(x, y, z)] = (byte) c.ordinal();
    }

    public int size() {
        return cells.length;
    }

    public int relX(int wx) {
        return wx - ox;
    }

    public int relY(int wy) {
        return wy - oy;
    }

    public int relZ(int wz) {
        return wz - oz;
    }

    /** Share of cells that were actually observed (not UNKNOWN). */
    public double coverage() {
        int known = 0;
        for (byte b : cells) if (b != 0) known++;
        return cells.length == 0 ? 0 : known / (double) cells.length;
    }
}
