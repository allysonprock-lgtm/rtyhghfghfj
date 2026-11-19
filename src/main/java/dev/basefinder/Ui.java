package dev.basefinder;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Immediate-mode drawing toolkit for the winter GUI: rounded frosted-glass cards, pill buttons, switches, steppers,
 * chips, gauges, spinners and tooltips. Everything is drawn from filled rectangles, so it looks identical at every GUI
 * scale. Clickable areas are collected as {@link Hit}s and handled by the screen.
 */
final class Ui {
    record Hit(int x, int y, int w, int h, Runnable action, String tip) {
        boolean contains(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    final GuiGraphicsExtractor g;
    final Font font;
    final int mx, my;
    final float dt, time;
    final boolean anim;
    final List<Hit> hits;
    private final Map<String, Float> hover;

    private int clipX1, clipY1, clipX2, clipY2;
    private boolean clipped;
    String pendingTip;

    Ui(GuiGraphicsExtractor g, Font font, int mx, int my, float dt, float time, boolean anim, List<Hit> hits, Map<String, Float> hover) {
        this.g = g;
        this.font = font;
        this.mx = mx;
        this.my = my;
        this.dt = dt;
        this.time = time;
        this.anim = anim;
        this.hits = hits;
        this.hover = hover;
    }

    // ------------------------------------------------------------------ clipping + hit testing

    // Scissor is looked up by reflection: if the method is missing in some version the menu still works, it just
    // hides items that are not fully inside the scroll area instead of cutting them.
    private static java.lang.reflect.Method scissorOn, scissorOff;
    private static boolean scissorResolved;
    private boolean scissorOk;

    private static void resolveScissor(Object g) {
        scissorResolved = true;
        try {
            scissorOn = g.getClass().getMethod("enableScissor", int.class, int.class, int.class, int.class);
            scissorOff = g.getClass().getMethod("disableScissor");
        } catch (Throwable t) {
            scissorOn = null;
            scissorOff = null;
        }
    }

    void beginClip(int x1, int y1, int x2, int y2) {
        clipped = true;
        clipX1 = x1;
        clipY1 = y1;
        clipX2 = x2;
        clipY2 = y2;
        if (!scissorResolved) resolveScissor(g);
        scissorOk = false;
        if (scissorOn != null) {
            try {
                scissorOn.invoke(g, x1, y1, x2, y2);
                scissorOk = true;
            } catch (Throwable t) {
                scissorOn = null;
            }
        }
    }

    void endClip() {
        if (scissorOk && scissorOff != null) {
            try {
                scissorOff.invoke(g);
            } catch (Throwable ignored) {
                scissorOff = null;
            }
        }
        clipped = false;
        scissorOk = false;
    }

    boolean visible(int y, int h) {
        if (!clipped) return true;
        if (scissorOk) return y + h > clipY1 && y < clipY2;
        return y >= clipY1 - 2 && y + h <= clipY2 + 2; // no scissor: only draw what fits completely
    }

    private boolean inClip(int x, int y, int w, int h) {
        if (!clipped) return true;
        return x < clipX2 && x + w > clipX1 && y < clipY2 && y + h > clipY1;
    }

    boolean hovered(int x, int y, int w, int h) {
        if (mx < x || mx >= x + w || my < y || my >= y + h) return false;
        return !clipped || (mx >= clipX1 && mx < clipX2 && my >= clipY1 && my < clipY2);
    }

    /** Registers a clickable region (only the part inside the current clip can be clicked). */
    void click(int x, int y, int w, int h, String tip, Runnable action) {
        if (!inClip(x, y, w, h)) return;
        int x1 = x, y1 = y, x2 = x + w, y2 = y + h;
        if (clipped) {
            x1 = Math.max(x1, clipX1);
            y1 = Math.max(y1, clipY1);
            x2 = Math.min(x2, clipX2);
            y2 = Math.min(y2, clipY2);
        }
        hits.add(new Hit(x1, y1, x2 - x1, y2 - y1, action, tip));
        if (tip != null && hovered(x, y, w, h)) pendingTip = tip;
    }

    /** Smoothly animated hover amount 0..1 for a widget. */
    float hov(String key, boolean isHovered) {
        float target = isHovered ? 1f : 0f;
        if (!anim) return target;
        if (hover.size() > 1500) hover.clear(); // keys include positions; keep the map small while scrolling
        float v = hover.getOrDefault(key, 0f);
        v += (target - v) * Math.min(1f, dt * 14f);
        hover.put(key, v);
        return v;
    }

    // ------------------------------------------------------------------ primitives

    void fill(int x, int y, int w, int h, int c) {
        if (w <= 0 || h <= 0) return;
        if (clipped && !scissorOk && (y + h <= clipY1 || y >= clipY2)) return;
        g.fill(x, y, x + w, y + h, c);
    }

    void rrect(int x, int y, int w, int h, int r, int color) {
        if (w <= 0 || h <= 0) return;
        if (clipped && !scissorOk && (y + h <= clipY1 || y >= clipY2)) return;
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        if (h - 2 * r > 0) g.fill(x, y + r, x + w, y + h - r, color);
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;
            int inset = r - (int) Math.round(Math.sqrt(Math.max(0, (double) r * r - dy * dy)));
            g.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
            g.fill(x + inset, y + h - 1 - i, x + w - inset, y + h - i, color);
        }
    }

    void rrectBordered(int x, int y, int w, int h, int r, int fill, int border) {
        rrect(x, y, w, h, r, border);
        rrect(x + 1, y + 1, w - 2, h - 2, Math.max(0, r - 1), fill);
    }

    void text(String s, int x, int y, int color) {
        if (!textVisible(y)) return;
        g.text(font, s, x, y, color, false);
    }

    private boolean textVisible(int y) {
        return !clipped || scissorOk || (y >= clipY1 && y + 8 <= clipY2);
    }

    /** Bold by drawing the text twice, one pixel apart (uses only the plain string text call). */
    void bold(String s, int x, int y, int color) {
        if (!textVisible(y)) return;
        g.text(font, s, x, y, color, false);
        g.text(font, s, x + 1, y, color, false);
    }

    int width(String s) {
        return font.width(s);
    }

    void textCentered(String s, int cx, int y, int color) {
        g.text(font, s, cx - font.width(s) / 2, y, color, false);
    }

    void textRight(String s, int rightX, int y, int color) {
        g.text(font, s, rightX - font.width(s), y, color, false);
    }

    /** Truncates with an ellipsis so text can never overflow its box. */
    String fit(String s, int maxW) {
        if (s == null) return "";
        if (font.width(s) <= maxW) return s;
        String ell = "\u2026";
        int lo = 0, hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (font.width(s.substring(0, mid)) + font.width(ell) <= maxW) lo = mid;
            else hi = mid - 1;
        }
        return s.substring(0, lo) + ell;
    }

    List<String> wrap(String s, int maxW) {
        List<String> lines = new ArrayList<>();
        if (s == null) return lines;
        for (String para : s.split("\n")) {
            StringBuilder cur = new StringBuilder();
            for (String word : para.split(" ")) {
                String trial = cur.length() == 0 ? word : cur + " " + word;
                if (font.width(trial) <= maxW) {
                    cur = new StringBuilder(trial);
                } else {
                    if (cur.length() > 0) lines.add(cur.toString());
                    cur = new StringBuilder(font.width(word) > maxW ? fit(word, maxW) : word);
                }
            }
            lines.add(cur.toString());
        }
        return lines;
    }

    // ------------------------------------------------------------------ components

    /** Frosted glass card. accent != 0 draws a coloured edge on the left. */
    void card(int x, int y, int w, int h, int accent) {
        rrect(x + 2, y + 3, w, h, 9, Theme.SHADOW);
        rrectBordered(x, y, w, h, 9, Theme.GLASS, Theme.GLASS_BORDER);
        fill(x + 9, y + 1, w - 18, 1, 0x38FFFFFF); // top highlight
        if (accent != 0) rrect(x + 1, y + 7, 3, h - 14, 1, accent);
    }

    /** Pill button. style: 0 ghost, 1 primary (ice blue), 2 colour (use accent). Disabled buttons do nothing. */
    void button(int x, int y, int w, int h, String label, int style, int accent, boolean enabled, String tip, Runnable action) {
        boolean hv = enabled && hovered(x, y, w, h);
        float a = hov("b" + x + ":" + y + ":" + w, hv);
        int r = Math.min(10, h / 2);
        rrect(x, y + 2, w, h, r, 0x33000000);
        int base, border, text;
        if (!enabled) {
            base = 0xFF16233D;
            border = 0xFF27385A;
            text = Theme.MUTED;
        } else if (style == 1) {
            base = Theme.mix(0xFF2B7BD6, 0xFF4BA3FF, a);
            border = Theme.mix(0xFF4FA8FF, 0xFFB8E4FF, a);
            text = Theme.SNOW;
        } else if (style == 2) {
            base = Theme.mix(0xFF16233D, accent, 0.22f + 0.28f * a);
            border = Theme.mix(Theme.withAlpha(accent, 0xA0), accent, a);
            text = Theme.TEXT;
        } else {
            base = Theme.mix(0xFF16294D, 0xFF24427A, a);
            border = Theme.mix(0xFF35588F, 0xFF8FD8FF, a);
            text = Theme.TEXT;
        }
        rrectBordered(x, y, w, h, r, base, border);
        if (enabled) fill(x + r, y + 1, w - 2 * r, 1, 0x30FFFFFF);
        textCentered(fit(label, w - 8), x + w / 2, y + (h - 8) / 2, text);
        if (enabled) click(x, y, w, h, tip, action);
        else if (tip != null && hovered(x, y, w, h)) pendingTip = tip;
    }

    /** Label + switch row. */
    void toggle(int x, int y, int w, int h, String label, boolean on, String tip, Runnable flip) {
        boolean hv = hovered(x, y, w, h);
        float a = hov("t" + x + ":" + y + ":" + w, hv);
        if (a > 0.01f) rrect(x - 4, y, w + 8, h, 5, Theme.withAlpha(Theme.CYAN, (int) (a * 28)));
        text(fit(label, w - 40), x, y + (h - 8) / 2, Theme.TEXT);
        int tw = 26, th = 12;
        int tx = x + w - tw, ty = y + (h - th) / 2;
        rrect(tx, ty, tw, th, 6, on ? 0xFF3B9BFF : 0xFF2A3B5C);
        rrect(on ? tx + tw - 11 : tx + 1, ty + 1, 10, 10, 5, Theme.SNOW);
        click(x - 4, y, w + 8, h, tip, flip);
    }

    /** Label  [-] value [+] row. */
    void stepper(int x, int y, int w, int h, String label, String value, String tip, Runnable dec, Runnable inc) {
        text(fit(label, w - 92), x, y + (h - 8) / 2, Theme.TEXT);
        int bx = x + w - 88;
        button(bx, y + 1, 18, h - 2, "-", 0, 0, true, tip, dec);
        textCentered(fit(value, 44), bx + 18 + 26, y + (h - 8) / 2, Theme.CYAN);
        button(bx + 70, y + 1, 18, h - 2, "+", 0, 0, true, tip, inc);
    }

    /** Filter chip. */
    int chip(int x, int y, String label, boolean selected, int accent, String tip, Runnable action) {
        int w = font.width(label) + 16, h = 16;
        boolean hv = hovered(x, y, w, h);
        float a = hov("c" + x + ":" + y, hv);
        int fill = selected ? Theme.withAlpha(accent, 0xFF) : Theme.mix(0xFF14264A, 0xFF22427A, a);
        int border = selected ? Theme.mix(accent, 0xFFFFFFFF, 0.35f) : Theme.mix(0xFF2E4A78, 0xFF8FD8FF, a);
        rrectBordered(x, y, w, h, 8, selected ? Theme.mix(0xFF0B1B3A, accent, 0.55f) : fill, border);
        textCentered(label, x + w / 2, y + 4, selected ? Theme.SNOW : Theme.TEXT2);
        click(x, y, w, h, tip, action);
        return w;
    }

    /** Small coloured label (classification etc.). */
    int tag(int x, int y, String label, int color) {
        int w = font.width(label) + 12;
        rrect(x, y, w, 12, 6, Theme.withAlpha(color, 0x38));
        rrectBordered(x, y, w, 12, 6, Theme.withAlpha(color, 0x30), Theme.withAlpha(color, 0xB0));
        text(label, x + 6, y + 2, Theme.mix(color, 0xFFFFFFFF, 0.4f));
        return w;
    }

    /** Horizontal gauge 0..1. */
    void gauge(int x, int y, int w, int h, float value, int color) {
        rrect(x, y, w, h, h / 2, 0xFF0C1630);
        int fw = Math.max(h, Math.round(w * Math.max(0f, Math.min(1f, value))));
        if (value > 0.001f) rrect(x, y, fw, h, h / 2, color);
    }

    /** Rotating dots (loading indicator). */
    void spinner(int cx, int cy, int color) {
        for (int i = 0; i < 8; i++) {
            double ang = i * Math.PI / 4 - time * 6.0;
            int px = cx + (int) Math.round(Math.cos(ang) * 5), py = cy + (int) Math.round(Math.sin(ang) * 5);
            int alpha = 70 + i * 22;
            fill(px - 1, py - 1, 2, 2, Theme.withAlpha(color, Math.min(255, alpha)));
        }
    }

    void statusDot(int x, int y, int color) {
        rrect(x, y, 6, 6, 3, color);
        rrect(x - 1, y - 1, 8, 8, 4, Theme.withAlpha(color, 0x30));
    }

    /** Draws the tooltip collected during this frame (call last). */
    void drawTooltip(int screenW, int screenH) {
        if (pendingTip == null) return;
        List<String> lines = wrap(pendingTip, 190);
        int w = 12, h = lines.size() * 11 + 8;
        for (String l : lines) w = Math.max(w, font.width(l) + 12);
        int x = Math.min(mx + 10, screenW - w - 4), y = Math.min(my + 12, screenH - h - 4);
        rrect(x + 1, y + 2, w, h, 5, 0x66000000);
        rrectBordered(x, y, w, h, 5, 0xF00A1530, 0xFF4FA8FF);
        for (int i = 0; i < lines.size(); i++) text(lines.get(i), x + 6, y + 5 + i * 11, Theme.TEXT);
    }

    static String ago(String iso) {
        try {
            long s = java.time.Duration.between(java.time.Instant.parse(iso), java.time.Instant.now()).getSeconds();
            if (s < 5) return "just now";
            if (s < 60) return s + "s ago";
            if (s < 3600) return (s / 60) + "m ago";
            if (s < 86400) return (s / 3600) + "h ago";
            return (s / 86400) + "d ago";
        } catch (Exception e) {
            return "";
        }
    }
}
