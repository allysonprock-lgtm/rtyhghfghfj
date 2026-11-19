package dev.basefinder.core;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

/**
 * Draws reconstructions from observed blocks: a floor plan, a cut-away 3D voxel view and a full isometric view.
 * These are generated pictures, NOT screenshots, and every image says so. Unknown cells are hatched.
 * Pure Java2D; safe to run on a background thread because it only reads an immutable snapshot.
 */
public final class ImageRenderer {
    private ImageRenderer() {}

    static {
        System.setProperty("java.awt.headless", "true");
    }

    private static final Color BG_TOP = new Color(0x0B1B3A), BG_BOTTOM = new Color(0x16294D);
    private static final Color INK = new Color(0xEAF4FF), MUTED = new Color(0x9DB8D6);

    // ------------------------------------------------------------------ public API

    public static byte[] floorPlanPng(Observation o, BaseReport r) {
        try {
            return png(floorPlan(o, r));
        } catch (Throwable t) {
            return null;
        }
    }

    public static byte[] voxelPng(Observation o, BaseReport r) {
        try {
            return png(iso(o, r, true));
        } catch (Throwable t) {
            return null;
        }
    }

    public static byte[] isometricPng(Observation o, BaseReport r) {
        try {
            return png(iso(o, r, false));
        } catch (Throwable t) {
            return null;
        }
    }

    public static BufferedImage floorPlan(Observation o, BaseReport r) {
        Snapshot s = o.snap;
        int planY = planLevel(o);
        int cell = Math.max(4, Math.min(11, 520 / Math.max(s.sx, s.sz)));
        int mapW = s.sx * cell, mapH = s.sz * cell;
        int legendW = 250;
        int w = 24 + mapW + 24 + legendW + 24;
        int h = 64 + mapH + 64;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = graphics(img);
        background(g, w, h);
        int mx = 24, my = 64;

        for (int z = 0; z < s.sz; z++) {
            for (int x = 0; x < s.sx; x++) {
                Cat c = s.get(x, planY, z);
                int px = mx + x * cell, py = my + z * cell;
                if (c == Cat.UNKNOWN) {
                    unknownCell(g, px, py, cell);
                    continue;
                }
                Color col;
                if (c.passable && c != Cat.DOOR && c != Cat.WATER && c != Cat.LAVA && c != Cat.FIRE) {
                    Cat floor = Cat.UNKNOWN;
                    int depth = 0;
                    for (int d = 1; d <= 8; d++) {
                        Cat f = s.get(x, planY - d, z);
                        if (f == Cat.UNKNOWN) break;
                        if (f.solid) {
                            floor = f;
                            depth = d;
                            break;
                        }
                    }
                    if (floor == Cat.UNKNOWN) col = new Color(0x0F1B2E);          // no floor within 8 blocks: a gap/void
                    else if (floor == Cat.BUILT || floor == Cat.GLASS) col = tint(new Color(Palette.rgb(floor)), 0.8);
                    else col = tint(new Color(Palette.rgb(floor)), Math.max(0.3, 0.55 - 0.03 * depth));
                    if (c == Cat.CROP || c == Cat.PLANT) col = blend(col, new Color(Palette.rgb(c)), 0.6);
                    else if (c == Cat.LIGHT || c == Cat.DECOR) col = blend(col, new Color(Palette.rgb(c)), 0.5);
                } else {
                    col = new Color(Palette.rgb(c));
                }
                g.setColor(col);
                g.fillRect(px, py, cell, cell);
            }
        }
        // faint grid every 8 blocks
        g.setColor(new Color(255, 255, 255, 28));
        for (int x = 0; x <= s.sx; x += 8) g.drawLine(mx + x * cell, my, mx + x * cell, my + mapH);
        for (int z = 0; z <= s.sz; z += 8) g.drawLine(mx, my + z * cell, mx + mapW, my + z * cell);

        // markers: storage without roof / floating, crystals, anchors, obsidian, craters
        g.setStroke(new BasicStroke(2f));
        for (StoragePoint sp : o.storage) {
            int rx = s.relX(sp.x()), ry = s.relY(sp.y()), rz = s.relZ(sp.z());
            if (!s.in(rx, ry, rz)) continue;
            int roof = StructureAnalyzer.ray(s, rx, ry, rz, 0, 1, 0, 12);
            boolean floating = s.get(rx, ry - 1, rz).passable && s.get(rx, ry - 1, rz) != Cat.UNKNOWN;
            if (roof == -1 || floating) {
                g.setColor(floating ? new Color(0xFFA033) : new Color(0xFF4D4D));
                g.drawRect(mx + rx * cell - 1, my + rz * cell - 1, cell + 1, cell + 1);
            }
        }
        for (int[] c : o.crystals) mark(g, s, mx, my, cell, c, new Color(0x5CF2FF), 'D');
        for (int[] a : o.anchors) mark(g, s, mx, my, cell, a, new Color(0xC4A1FF), 'X');
        for (int[] ob : r.structure.isolatedObsidian) mark(g, s, mx, my, cell, ob, Color.WHITE, 'S');
        g.setColor(new Color(0xFF6B6B));
        for (int[] c : r.structure.craters) {
            int cx = mx + (c[0] - s.ox) * cell + cell / 2, cz = my + (c[1] - s.oz) * cell + cell / 2;
            int rad = (int) (Math.sqrt(c[2] / Math.PI) * cell);
            g.drawOval(cx - rad, cz - rad, rad * 2, rad * 2);
        }

        // frame + compass
        g.setColor(new Color(0x2F4A75));
        g.setStroke(new BasicStroke(1.5f));
        g.drawRect(mx, my, mapW, mapH);
        text(g, "N", mx + mapW - 14, my + 16, 14, true, INK);

        // header
        text(g, "Floor plan \u00B7 Y " + (s.oy + planY) + " \u00B7 " + r.id, mx, 30, 18, true, INK);
        text(g, EmbedFactory.prettyDim(r.dimension) + " \u00B7 " + EmbedFactory.coords(r.x, r.y, r.z), mx, 50, 12, false, MUTED);

        // legend
        int lx = mx + mapW + 24, ly = my;
        legend(g, lx, ly, new Cat[]{Cat.BUILT, Cat.NATURAL, Cat.DOOR, Cat.GLASS, Cat.CHEST, Cat.BARREL, Cat.SHULKER,
                Cat.INFRA, Cat.BED, Cat.FARMLAND, Cat.CROP, Cat.WATER, Cat.LAVA});
        int yy = ly + 13 * 20 + 8;
        yy = swatchLine(g, lx, yy, new Color(0xFF4D4D), "Storage with no roof above (outline)");
        yy = swatchLine(g, lx, yy, new Color(0xFFA033), "Storage with air below (outline)");
        yy = swatchLine(g, lx, yy, new Color(0x5CF2FF), "End crystal");
        yy = swatchLine(g, lx, yy, new Color(0xC4A1FF), "Respawn anchor");
        yy = swatchLine(g, lx, yy, Color.WHITE, "Isolated obsidian");
        yy = swatchLine(g, lx, yy, new Color(0xFF6B6B), "Possible crater (circle)");
        unknownCell(g, lx, yy - 2, 12);
        text(g, "Unknown / unloaded (hatched)", lx + 20, yy + 10, 11, false, MUTED);

        footer(g, w, h, o, r);
        g.dispose();
        return img;
    }

    public static BufferedImage iso(Observation o, BaseReport r, boolean cutaway) {
        Snapshot s = o.snap;
        int cutY = s.sy - 1;
        int minStorage = Integer.MAX_VALUE, maxStorage = 0;
        for (StoragePoint sp : o.storage) {
            minStorage = Math.min(minStorage, s.relY(sp.y()));
            maxStorage = Math.max(maxStorage, s.relY(sp.y()));
        }
        int centerRel = s.relY(o.centerY);
        int floorY = Math.max(0, (o.storage.isEmpty() ? centerRel : minStorage) - 4); // trim the deep terrain slab
        if (cutaway) cutY = Math.min(s.sy - 1, (o.storage.isEmpty() ? centerRel : maxStorage) + 1);
        final double a = 6.0;                 // half tile width
        final double ty = a / 2.0;            // half tile height
        final double hz = a * 0.95;           // block height on screen
        int w = Math.max(760, (int) ((s.sx + s.sz) * a) + 80);
        int h = (int) ((s.sx + s.sz) * ty + (cutY - floorY + 1) * hz) + 150;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = graphics(img);
        background(g, w, h);
        double ox = w / 2.0 + (s.sz - s.sx) * a / 2.0;
        double oy = h - 70 - (s.sx + s.sz) * ty;

        // unknown columns as hatched dark floor tiles
        for (int z = 0; z < s.sz; z++) {
            for (int x = 0; x < s.sx; x++) {
                if (s.get(x, 0, z) != Cat.UNKNOWN) continue;
                double cx = ox + (x - z) * a, cy = oy + (x + z) * ty;
                g.setColor(new Color(0x1B2433));
                g.fill(diamond(cx, cy, a, ty));
                g.setColor(new Color(255, 255, 255, 40));
                g.draw(diamond(cx, cy, a, ty));
            }
        }

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF); // no seams between voxels
        for (int y = floorY; y <= cutY; y++) {
            for (int z = 0; z < s.sz; z++) {
                for (int x = 0; x < s.sx; x++) {
                    Cat c = s.get(x, y, z);
                    if (!drawable(c)) continue;
                    boolean topVis = !opaque(s, x, y + 1, z, cutY);
                    boolean rightVis = !opaque(s, x + 1, y, z, cutY);
                    boolean leftVis = !opaque(s, x, y, z + 1, cutY);
                    if (!topVis && !rightVis && !leftVis) continue;
                    double cx = ox + (x - z) * a;
                    double cy = oy + (x + z) * ty - (y - floorY) * hz;
                    Color base = new Color(Palette.rgb(c));
                    if (c == Cat.NATURAL) base = tint(base, 0.78);
                    boolean translucent = c == Cat.GLASS || c == Cat.WATER;
                    if (translucent) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
                    if (topVis) poly(g, base, new double[]{cx, cx + a, cx, cx - a}, new double[]{cy - ty, cy, cy + ty, cy});
                    if (leftVis) poly(g, shade(base, 0.74), new double[]{cx - a, cx, cx, cx - a},
                            new double[]{cy, cy + ty, cy + ty + hz, cy + hz});
                    if (rightVis) poly(g, shade(base, 0.55), new double[]{cx, cx + a, cx + a, cx},
                            new double[]{cy + ty, cy, cy + hz, cy + ty + hz});
                    if (translucent) g.setComposite(AlphaComposite.SrcOver);
                    if (c.isStorage() && topVis) { // thin highlight so storage stands out
                        g.setColor(new Color(255, 255, 255, 120));
                        g.setStroke(new BasicStroke(1f));
                        g.draw(diamond(cx, cy, a * 0.8, ty * 0.8));
                    }
                }
            }
        }
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        for (int[] c : o.crystals) {
            int x = s.relX(c[0]), y = s.relY(c[1]), z = s.relZ(c[2]);
            if (!s.in(x, y, z) || y > cutY || y < floorY) continue;
            double cx = ox + (x - z) * a, cy = oy + (x + z) * ty - (y - floorY) * hz;
            g.setColor(new Color(0x5CF2FF));
            g.fill(diamond(cx, cy - hz, a * 0.8, a * 0.8));
        }

        text(g, (cutaway ? "3D voxel reconstruction (cut-away)" : "Isometric reconstruction") + " \u00B7 " + r.id, 24, 30, 18, true, INK);
        text(g, EmbedFactory.prettyDim(r.dimension) + " \u00B7 " + EmbedFactory.coords(r.x, r.y, r.z)
                + (cutaway ? " \u00B7 roofs and everything above Y " + (s.oy + cutY) + " hidden to show the inside" : ""), 24, 50, 12, false, MUTED);
        footer(g, w, h, o, r);
        g.dispose();
        return img;
    }

    // ------------------------------------------------------------------ helpers

    private static int planLevel(Observation o) {
        Snapshot s = o.snap;
        if (o.storage.isEmpty()) return Math.max(0, Math.min(s.sy - 1, s.relY(o.centerY)));
        int[] ys = new int[o.storage.size()];
        for (int i = 0; i < ys.length; i++) ys[i] = s.relY(o.storage.get(i).y());
        java.util.Arrays.sort(ys);
        return Math.max(0, Math.min(s.sy - 1, ys[ys.length / 2]));
    }

    private static boolean drawable(Cat c) {
        return c.solid || c == Cat.DOOR || c == Cat.WATER || c == Cat.LAVA || c == Cat.FIRE;
    }

    private static boolean opaque(Snapshot s, int x, int y, int z, int cutY) {
        if (y > cutY) return false;
        Cat c = s.get(x, y, z);
        return c.solid && c != Cat.GLASS;
    }

    private static Graphics2D graphics(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        return g;
    }

    private static void background(Graphics2D g, int w, int h) {
        g.setPaint(new GradientPaint(0, 0, BG_TOP, 0, h, BG_BOTTOM));
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(255, 255, 255, 60));
        long seed = 7;
        for (int i = 0; i < 60; i++) { // a few static stars/snow dots
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            int x = (int) ((seed >>> 33) % w);
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            int y = (int) ((seed >>> 33) % Math.max(1, h / 3));
            g.fillRect(x, y, 2, 2);
        }
    }

    private static void footer(Graphics2D g, int w, int h, Observation o, BaseReport r) {
        Color warn = new Color(0xF4C46A);
        text(g, "RECONSTRUCTION generated from blocks the client observed \u2014 not a screenshot.", 24, h - 34, 11, false, warn);
        text(g, Math.round(r.coverage * 100) + "% of the area observed. Hatched = unknown/unloaded. Shapes are estimated from block types.", 24, h - 18, 11, false, warn);
    }

    private static void text(Graphics2D g, String s, int x, int y, int size, boolean bold, Color c) {
        try {
            g.setFont(new Font(Font.SANS_SERIF, bold ? Font.BOLD : Font.PLAIN, size));
            g.setColor(c);
            g.drawString(s, x, y);
        } catch (Throwable ignored) {
            // fonts unavailable (headless server without fontconfig): skip labels, keep the picture
        }
    }

    private static void legend(Graphics2D g, int x, int y, Cat[] cats) {
        for (int i = 0; i < cats.length; i++) {
            g.setColor(new Color(Palette.rgb(cats[i])));
            g.fillRect(x, y + i * 20, 14, 14);
            g.setColor(new Color(255, 255, 255, 80));
            g.drawRect(x, y + i * 20, 14, 14);
            text(g, Palette.label(cats[i]), x + 22, y + i * 20 + 12, 12, false, INK);
        }
    }

    private static int swatchLine(Graphics2D g, int x, int y, Color c, String label) {
        g.setColor(c);
        g.setStroke(new BasicStroke(2f));
        g.drawRect(x, y, 12, 12);
        text(g, label, x + 20, y + 11, 11, false, MUTED);
        return y + 19;
    }

    private static void unknownCell(Graphics2D g, int px, int py, int cell) {
        g.setColor(new Color(0x1B2433));
        g.fillRect(px, py, cell, cell);
        g.setColor(new Color(255, 255, 255, 38));
        g.drawLine(px, py + cell, px + cell, py);
    }

    private static void mark(Graphics2D g, Snapshot s, int mx, int my, int cell, int[] pos, Color c, char kind) {
        int x = s.relX(pos[0]), z = s.relZ(pos[2]);
        if (x < 0 || z < 0 || x >= s.sx || z >= s.sz) return;
        int px = mx + x * cell, py = my + z * cell;
        g.setColor(c);
        if (kind == 'D') {
            g.fillPolygon(new int[]{px + cell / 2, px + cell, px + cell / 2, px}, new int[]{py, py + cell / 2, py + cell, py + cell / 2}, 4);
        } else if (kind == 'X') {
            g.drawLine(px, py, px + cell, py + cell);
            g.drawLine(px + cell, py, px, py + cell);
        } else {
            g.fillRect(px + 1, py + 1, cell - 2, cell - 2);
        }
    }

    private static java.awt.Polygon diamond(double cx, double cy, double a, double b) {
        return new java.awt.Polygon(new int[]{(int) Math.round(cx), (int) Math.round(cx + a), (int) Math.round(cx), (int) Math.round(cx - a)},
                new int[]{(int) Math.round(cy - b), (int) Math.round(cy), (int) Math.round(cy + b), (int) Math.round(cy)}, 4);
    }

    private static void poly(Graphics2D g, Color c, double[] xs, double[] ys) {
        int[] ix = new int[xs.length], iy = new int[ys.length];
        for (int i = 0; i < xs.length; i++) {
            ix[i] = (int) Math.round(xs[i]);
            iy[i] = (int) Math.round(ys[i]);
        }
        g.setColor(c);
        g.fillPolygon(ix, iy, ix.length);
    }

    private static Color shade(Color c, double f) {
        return new Color(Math.min(255, (int) (c.getRed() * f)), Math.min(255, (int) (c.getGreen() * f)), Math.min(255, (int) (c.getBlue() * f)));
    }

    private static Color tint(Color c, double f) {
        return shade(c, f);
    }

    private static Color blend(Color a, Color b, double t) {
        return new Color((int) (a.getRed() * (1 - t) + b.getRed() * t), (int) (a.getGreen() * (1 - t) + b.getGreen() * t),
                (int) (a.getBlue() * (1 - t) + b.getBlue() * t));
    }

    private static byte[] png(BufferedImage img) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
