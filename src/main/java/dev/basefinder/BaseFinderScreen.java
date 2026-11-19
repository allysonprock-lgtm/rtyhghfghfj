package dev.basefinder;

import dev.basefinder.core.BaseReport;
import dev.basefinder.core.Cat;
import dev.basefinder.core.Observation;
import dev.basefinder.core.Palette;
import dev.basefinder.core.Snapshot;
import dev.basefinder.core.StoragePoint;
import dev.basefinder.core.WebhookUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Base Finder menu: a winter night-mountain dashboard with seven pages (Dashboard, Detections, Discord, Storage,
 * Alerts, Map, Settings). Everything shown comes from the real stores, caches and config; nothing is placeholder data.
 * Open it with the key in Options > Controls (Right Shift by default).
 */
public class BaseFinderScreen extends Screen {
    private static final String[] TABS = {"Dashboard", "Detections", "Discord", "Storage", "Alerts", "Map", "Settings"};
    private static final int[] TAB_COLORS = {Theme.CYAN, Theme.INTACT, Theme.PEARL, Theme.SUSPICIOUS, Theme.PLAYER, Theme.ICE, Theme.SILVER};
    private static final int M = 8, HEADER_H = 30;

    private final Screen parent;
    private final List<Ui.Hit> hits = new ArrayList<>();
    private final Map<String, Float> hover = new HashMap<>();
    private final Map<String, Float> counters = new HashMap<>();
    private final WinterBackground bg = new WinterBackground();

    private long lastNanos;
    private float time;
    private int tab;
    private float tabAnim = 1f;
    private final float[] scroll = new float[7], scrollTarget = new float[7];
    private int contentH, viewH;

    // detections page
    private String filter = "ALL";
    private int sort;
    private String selectedId;
    // map page
    private String mapId;
    private int mapMode;          // 0 floor plan, 1 overhead, 2 voxel
    private boolean mapCut = true;
    private MapCache mapCache;
    // alerts page
    private String alertFilter = "ALL";
    // settings page
    private String section = "General";
    private final Map<String, List<SettingsRegistry.Row>> settings = SettingsRegistry.build();
    // discord page
    private final Map<Channel, String> channelMsg = new HashMap<>();
    private String status = "";
    private float statusAge;

    private long dirtySince;
    private final boolean[] keyDown = new boolean[512];

    public BaseFinderScreen(Screen parent) {
        super(Component.literal("Base Finder"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        tabAnim = 0f;
        mouseWasDown = leftMouseDown(); // a button already held while opening must not click
    }

    // ------------------------------------------------------------------ input

    private void dirty() {
        dirtySince = System.nanoTime();
    }

    private void flush() {
        if (dirtySince != 0) {
            BaseFinderClient.config.applyAndSave();
            dirtySince = 0;
        }
    }

    private void setTab(int t) {
        if (t == tab) return;
        tab = t;
        tabAnim = 0f;
        setStatus("");
    }

    private void setStatus(String s) {
        status = s;
        statusAge = 0f;
    }

    @Override
    public void onClose() {
        flush();
        this.minecraft.setScreen(parent);
    }

    /** The menu does not pause a single-player game. */
    public boolean isPauseScreen() {
        return false;
    }

    private boolean mouseWasDown;

    private static boolean leftMouseDown() {
        long win = GLFW.glfwGetCurrentContext();
        return win != 0L && GLFW.glfwGetMouseButton(win, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
    }

    /**
     * Clicks are detected by polling the left mouse button once per frame and testing the position the game passes to
     * the render call (already in GUI coordinates). This avoids depending on the screen mouse-event signature.
     */
    private void handleClick(int mx, int my) {
        boolean down = leftMouseDown();
        if (down && !mouseWasDown) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                Ui.Hit h = hits.get(i);
                if (h.contains(mx, my)) {
                    h.action().run();
                    break;
                }
            }
        }
        mouseWasDown = down;
    }

    /** Mouse wheel scrolling (declared without @Override so a signature change can never break compilation). */
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scrollBy((float) (-scrollY * 28));
        return true;
    }

    private void scrollBy(float dy) {
        float max = Math.max(0, contentH - viewH);
        scrollTarget[tab] = Math.max(0, Math.min(max, scrollTarget[tab] + dy));
    }

    private boolean pressed(long win, int key) {
        return GLFW.glfwGetKey(win, key) == GLFW.GLFW_PRESS;
    }

    /** Keyboard navigation by polling: Tab / Shift+Tab / 1-7 switch pages, arrows and Page keys scroll. */
    private void pollKeys() {
        long win = GLFW.glfwGetCurrentContext();
        if (win == 0L) return;
        int[] keys = {GLFW.GLFW_KEY_TAB, GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_PAGE_UP, GLFW.GLFW_KEY_PAGE_DOWN,
                GLFW.GLFW_KEY_HOME, GLFW.GLFW_KEY_END, GLFW.GLFW_KEY_1, GLFW.GLFW_KEY_2, GLFW.GLFW_KEY_3, GLFW.GLFW_KEY_4,
                GLFW.GLFW_KEY_5, GLFW.GLFW_KEY_6, GLFW.GLFW_KEY_7};
        for (int k : keys) {
            boolean down = pressed(win, k);
            boolean edge = down && !keyDown[k];
            keyDown[k] = down;
            if (!edge) continue;
            boolean shift = pressed(win, GLFW.GLFW_KEY_LEFT_SHIFT) || pressed(win, GLFW.GLFW_KEY_RIGHT_SHIFT);
            if (k == GLFW.GLFW_KEY_TAB) setTab((tab + (shift ? TABS.length - 1 : 1)) % TABS.length);
            else if (k == GLFW.GLFW_KEY_UP) scrollBy(-28);
            else if (k == GLFW.GLFW_KEY_DOWN) scrollBy(28);
            else if (k == GLFW.GLFW_KEY_PAGE_UP) scrollBy(-viewH * 0.85f);
            else if (k == GLFW.GLFW_KEY_PAGE_DOWN) scrollBy(viewH * 0.85f);
            else if (k == GLFW.GLFW_KEY_HOME) scrollBy(-100000);
            else if (k == GLFW.GLFW_KEY_END) scrollBy(100000);
            else if (k >= GLFW.GLFW_KEY_1 && k <= GLFW.GLFW_KEY_7) setTab(k - GLFW.GLFW_KEY_1);
        }
    }

    private static void copyToClipboard(String s) {
        long win = GLFW.glfwGetCurrentContext();
        if (win != 0L) GLFW.glfwSetClipboardString(win, s);
    }

    private static String clipboard() {
        long win = GLFW.glfwGetCurrentContext();
        return win == 0L ? null : GLFW.glfwGetClipboardString(win);
    }

    // ------------------------------------------------------------------ frame

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        Config cfg = BaseFinderClient.config;
        hits.clear();
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0f : Math.min(0.1f, (now - lastNanos) / 1_000_000_000f);
        lastNanos = now;
        time += dt;
        boolean anim = cfg.guiAnimations;
        tabAnim = anim ? Math.min(1f, tabAnim + dt * 5f) : 1f;

        Ui ui = new Ui(g, this.font, mx, my, dt, time, anim, hits, hover);
        bg.draw(ui, this.width, this.height, cfg);

        boolean compact = this.width < 470;
        int sw = compact ? 34 : 112;
        drawHeader(ui, cfg, compact);

        int top = M + HEADER_H + 6;
        int sideX = M;
        drawSidebar(ui, sideX, top, sw, compact);

        int cx = M + sw + 8, cw = this.width - cx - M, ch = this.height - top - M;
        viewH = ch;
        ui.rrectBordered(cx - 4, top - 4, cw + 8, ch + 8, 10, 0xB8060F22, 0x40BFE3FF);

        // scrolling content
        scroll[tab] += (scrollTarget[tab] - scroll[tab]) * (anim ? Math.min(1f, dt * 16f) : 1f);
        ui.beginClip(cx, top, cx + cw, top + ch);
        int inner = cw - 10;
        int y0 = top - Math.round(scroll[tab]);
        int used = switch (tab) {
            case 0 -> pageDashboard(ui, cfg, cx, y0, inner);
            case 1 -> selectedId != null && BaseFinderClient.store.byId(selectedId) != null
                    ? pageDetail(ui, cx, y0, inner) : pageDetections(ui, cx, y0, inner);
            case 2 -> pageDiscord(ui, cfg, cx, y0, inner);
            case 3 -> pageStorage(ui, cx, y0, inner);
            case 4 -> pageAlerts(ui, cx, y0, inner);
            case 5 -> pageMap(ui, cfg, cx, y0, inner, ch);
            default -> pageSettings(ui, cfg, cx, y0, inner);
        };
        contentH = used + 4;
        if (tabAnim < 1f) ui.fill(cx, top, cw, ch, Theme.withAlpha(0x0B1B3A, (int) ((1f - tabAnim) * 200)));
        ui.endClip();

        // scrollbar
        if (contentH > viewH) {
            int barH = Math.max(20, (int) (viewH * (viewH / (float) contentH)));
            float frac = scroll[tab] / Math.max(1, contentH - viewH);
            int by = top + (int) ((viewH - barH) * frac);
            ui.rrect(cx + cw - 5, top, 3, ch, 1, 0x33FFFFFF);
            ui.rrect(cx + cw - 5, by, 3, barH, 1, Theme.ICE);
        }

        statusAge += dt;
        if (statusAge > 3.5f) status = "";
        if (!status.isEmpty()) {
            int tw = ui.width(status) + 16;
            ui.rrectBordered(this.width / 2 - tw / 2, this.height - M - 18, tw, 16, 8, 0xEE0A1A3A, 0xFF4FA8FF);
            ui.textCentered(status, this.width / 2, this.height - M - 14, Theme.TEXT);
        }
        ui.drawTooltip(this.width, this.height);

        handleClick(mx, my);
        pollKeys();
        if (dirtySince != 0 && now - dirtySince > 350_000_000L) flush(); // autosave shortly after the last change
    }

    // ------------------------------------------------------------------ header + sidebar

    private void drawHeader(Ui ui, Config cfg, boolean compact) {
        int y = M;
        ui.card(M, y, this.width - 2 * M, HEADER_H, 0);
        snowflake(ui, M + 18, y + 15, Theme.ICE);
        ui.bold("BASE FINDER", M + 32, y + 6, Theme.SNOW);
        ui.text("winter edition", M + 32, y + 17, Theme.MUTED);

        int rx = this.width - M - 10;
        int bw = compact ? 40 : 62;
        ui.button(rx - bw, y + 6, bw, 18, "Done", 1, 0, true, "Save and close (Esc)", this::onClose);
        rx -= bw + 6;
        boolean on = cfg.enabled;
        int pw = compact ? 52 : 74;
        ui.button(rx - pw, y + 6, pw, 18, on ? "Pause" : "Resume", 2, on ? Theme.SUSPICIOUS : Theme.OK, true,
                on ? "Pause base detection (players, pearls and death alerts keep working)" : "Resume base detection", () -> {
                    cfg.enabled = !cfg.enabled;
                    dirty();
                });
        rx -= pw + 8;
        if (!compact) {
            ui.tag(rx - ui.width(on ? "ACTIVE" : "PAUSED") - 12, y + 9, on ? "ACTIVE" : "PAUSED", on ? Theme.OK : Theme.SUSPICIOUS);
        }
    }

    private void drawSidebar(Ui ui, int x, int y, int w, boolean compact) {
        int h = TABS.length * 26 + 10;
        ui.card(x, y, w, h, 0);
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            int iy = y + 6 + i * 26;
            boolean sel = tab == i;
            boolean hv = ui.hovered(x + 4, iy, w - 8, 24);
            float a = ui.hov("tab" + i, hv);
            if (sel) {
                ui.rrect(x + 4, iy, w - 8, 24, 7, Theme.withAlpha(TAB_COLORS[i], 0x30));
                ui.rrect(x + 4, iy + 5, 3, 14, 1, TAB_COLORS[i]);
            } else if (a > 0.01f) {
                ui.rrect(x + 4, iy, w - 8, 24, 7, Theme.withAlpha(Theme.CYAN, (int) (a * 30)));
            }
            if (compact) {
                ui.textCentered(String.valueOf(i + 1), x + w / 2, iy + 8, sel ? TAB_COLORS[i] : Theme.TEXT2);
            } else {
                ui.statusDot(x + 14, iy + 9, TAB_COLORS[i]);
                ui.text(ui.fit(TABS[i], w - 30), x + 26, iy + 8, sel ? Theme.SNOW : Theme.TEXT2);
            }
            ui.click(x + 4, iy, w - 8, 24, TABS[i] + " (key " + (i + 1) + ")", () -> setTab(idx));
        }
    }

    private static void snowflake(Ui ui, int cx, int cy, int color) {
        ui.fill(cx - 6, cy, 13, 1, color);
        ui.fill(cx, cy - 6, 1, 13, color);
        for (int i = -4; i <= 4; i++) {
            ui.fill(cx + i, cy + i, 1, 1, color);
            ui.fill(cx + i, cy - i, 1, 1, color);
        }
        ui.fill(cx - 2, cy - 5, 5, 1, color);
        ui.fill(cx - 2, cy + 5, 5, 1, color);
    }

    // ------------------------------------------------------------------ shared widgets

    private int heading(Ui ui, int x, int y, int w, String title) {
        ui.bold(title, x, y, Theme.CYAN);
        ui.fill(x + ui.width(title) + 8, y + 4, Math.max(0, w - ui.width(title) - 8), 1, 0x33BFE3FF);
        return y + 15;
    }

    private int counter(String key, int target) {
        float v = counters.getOrDefault(key, 0f);
        v += (target - v) * Math.min(1f, 0.15f);
        if (Math.abs(target - v) < 0.5f) v = target;
        counters.put(key, v);
        return Math.round(v);
    }

    private void tile(Ui ui, int x, int y, int w, int h, String label, String value, int color, String tip) {
        boolean hv = ui.hovered(x, y, w, h);
        float a = ui.hov("tile" + x + ":" + y, hv);
        ui.card(x, y, w, h, color);
        if (a > 0.01f) ui.rrect(x + 1, y + 1, w - 2, h - 2, 8, Theme.withAlpha(color, (int) (a * 26)));
        ui.bold(ui.fit(value, w - 16), x + 10, y + 8, color);
        ui.text(ui.fit(label, w - 16), x + 10, y + h - 15, Theme.TEXT2);
        if (tip != null && hv) ui.pendingTip = tip;
    }

    private int columns(int w, int minTile) {
        return Math.max(1, Math.min(5, (w + 6) / (minTile + 6)));
    }

    // ------------------------------------------------------------------ DASHBOARD

    private int pageDashboard(Ui ui, Config cfg, int x, int y0, int w) {
        int y = y0;
        DetectionStore st = BaseFinderClient.store;

        // status card
        int sh = 58;
        ui.card(x, y, w, sh, cfg.enabled ? Theme.OK : Theme.SUSPICIOUS);
        ui.tag(x + 14, y + 10, cfg.enabled ? "ACTIVE" : "PAUSED", cfg.enabled ? Theme.OK : Theme.SUSPICIOUS);
        int ready = 0;
        for (Channel c : Channel.values()) {
            DiscordManager.State s = BaseFinderClient.discord.status(c).state();
            if (s == DiscordManager.State.OK || s == DiscordManager.State.READY) ready++;
        }
        String line1 = "Tracking " + BaseFinderClient.scanner.trackedChunks() + " chunks with storage \u00B7 "
                + BaseFinderClient.pipeline.queued() + " queued \u00B7 " + BaseFinderClient.pipeline.running() + " analysing";
        ui.text(ui.fit(line1, w - 28), x + 14, y + 28, Theme.TEXT2);
        ui.text(ui.fit("Passive: reads only what the client already received.", w - 28),
                x + 14, y + 41, Theme.MUTED);
        if (BaseFinderClient.pipeline.running() > 0) ui.spinner(x + w - 20, y + 16, Theme.CYAN);
        y += sh + 8;

        // tiles
        int cols = columns(w, 92);
        int tw = (w - (cols - 1) * 6) / cols;
        int th = 42;
        String[][] t1 = {
                {"Total detections", String.valueOf(counter("total", st.size())), String.valueOf(Theme.CYAN), "Everything in your detection history."},
                {"Intact bases", String.valueOf(counter("intact", st.count("INTACT"))), String.valueOf(Theme.INTACT), "Routed to the Intact Bases channel."},
                {"Suspicious", String.valueOf(counter("susp", st.count("SUSPICIOUS"))), String.valueOf(Theme.SUSPICIOUS), "Uncertain, rough or possibly damaged."},
                {"Likely griefed", String.valueOf(counter("grief", st.count("GRIEFED"))), String.valueOf(Theme.GRIEFED), "Strong multi-indicator damage evidence."},
                {"Rejected", String.valueOf(counter("rej", st.count("REJECTED"))), String.valueOf(Theme.REJECTED), "Saved locally with reasons, never sent to base channels."}};
        y = tileRow(ui, t1, x, y, tw, th, cols);

        int storage = 0;
        for (DetectionStore.Detection d : st.snapshot()) if (d.classification != null && !d.classification.equals("REJECTED")) storage += d.totalStorage;
        String[][] t2 = {
                {"Other players nearby", String.valueOf(BaseFinderClient.players.nearbyCount()), String.valueOf(Theme.PLAYER), "Other players visible right now. You are never counted."},
                {"Ender pearl alerts", String.valueOf(counter("pearl", BaseFinderClient.history.count("PEARL"))), String.valueOf(Theme.PEARL), "Live pearl projectiles seen."},
                {"Death notices", String.valueOf(counter("death", BaseFinderClient.history.count("DEATH"))), String.valueOf(Theme.DEATH), "Your own deaths."},
                {"Storage units found", String.valueOf(counter("stor", storage)), String.valueOf(Theme.SUSPICIOUS), "Chests (double = 1), barrels and shulkers in analysed bases."},
                {"Discord ready", ready + "/" + Channel.values().length, String.valueOf(Theme.ICE), "Destinations that are configured and not failing."}};
        y = tileRow(ui, t2, x, y, tw, th, cols);
        y += 4;

        // storage mini chart + recent activity
        boolean side = w >= 420;
        int lw = side ? (w - 8) / 2 : w;
        int rowH = 12;
        List<HistoryStore.Event> ev = BaseFinderClient.history.recent(8);
        int ah = 30 + Math.max(1, ev.size()) * rowH + 8;
        int sh2 = 96;
        int cardH = side ? Math.max(ah, sh2) : sh2;

        int chests = 0, barrels = 0, shulkers = 0;
        for (DetectionStore.Detection d : st.snapshot()) {
            if (d.classification == null || d.classification.equals("REJECTED")) continue;
            chests += d.chests;
            barrels += d.barrels;
            shulkers += d.shulkers;
        }
        ui.card(x, y, lw, cardH, 0);
        ui.bold("Storage overview", x + 12, y + 9, Theme.CYAN);
        int tot = chests + barrels + shulkers;
        if (tot == 0) {
            ui.text("No analysed bases yet.", x + 12, y + 28, Theme.MUTED);
        } else {
            stackedBar(ui, x + 12, y + 28, lw - 24, 10, chests, barrels, shulkers);
            ui.text("Chests " + chests, x + 12, y + 46, 0xFFC17B2F);
            ui.text("Barrels " + barrels, x + 12, y + 58, 0xFFE0A030);
            ui.text("Shulkers " + shulkers, x + 12, y + 70, 0xFFA05CC8);
            ui.text("Inventory contents unavailable", x + 12, y + 83, Theme.MUTED);
        }

        int ax = side ? x + lw + 8 : x;
        int ay = side ? y : y + cardH + 8;
        int aw = side ? w - lw - 8 : w;
        ui.card(ax, ay, aw, ah, 0);
        ui.bold("Recent activity", ax + 12, ay + 9, Theme.CYAN);
        if (ev.isEmpty()) ui.text("Nothing yet. Explore to load chunks.", ax + 12, ay + 28, Theme.MUTED);
        int ry = ay + 26;
        for (HistoryStore.Event e : ev) {
            ui.statusDot(ax + 12, ry + 2, Theme.eventColor(e.type));
            String ago = Ui.ago(e.time);
            ui.textRight(ago, ax + aw - 10, ry, Theme.MUTED);
            ui.text(ui.fit(e.text == null ? e.type : e.text, aw - 36 - ui.width(ago)), ax + 24, ry, Theme.TEXT);
            ry += rowH;
        }
        y = side ? y + cardH : ay + ah;
        return y - y0;
    }

    private int tileRow(Ui ui, String[][] tiles, int x, int y, int tw, int th, int cols) {
        for (int i = 0; i < tiles.length; i++) {
            int col = i % cols, row = i / cols;
            tile(ui, x + col * (tw + 6), y + row * (th + 6), tw, th, tiles[i][0], tiles[i][1], Integer.parseInt(tiles[i][2]), tiles[i][3]);
        }
        int rows = (tiles.length + cols - 1) / cols;
        return y + rows * (th + 6) + 2;
    }

    private void stackedBar(Ui ui, int x, int y, int w, int h, int a, int b, int c) {
        int tot = Math.max(1, a + b + c);
        ui.rrect(x, y, w, h, h / 2, 0xFF0C1630);
        int wa = w * a / tot, wb = w * b / tot, wc = w - wa - wb;
        int px = x;
        if (a > 0) ui.rrect(px, y, Math.max(h, wa), h, h / 2, 0xFFC17B2F);
        px += wa;
        if (b > 0) ui.fill(px, y, wb, h, 0xFFE0A030);
        px += wb;
        if (c > 0 && wc > 0) ui.rrect(px, y, wc, h, h / 2, 0xFFA05CC8);
    }

    // ------------------------------------------------------------------ DETECTIONS

    private List<DetectionStore.Detection> filtered() {
        List<DetectionStore.Detection> all = BaseFinderClient.store.snapshot();
        List<DetectionStore.Detection> out = new ArrayList<>();
        for (DetectionStore.Detection d : all) if (filter.equals("ALL") || filter.equals(d.classOrLegacy())) out.add(d);
        switch (sort) {
            case 0 -> java.util.Collections.reverse(out);
            case 1 -> out.sort((a, b) -> Integer.compare(b.likelihood, a.likelihood));
            case 2 -> out.sort((a, b) -> Integer.compare(b.totalStorage, a.totalStorage));
            default -> out.sort((a, b) -> Integer.compare(b.conditionScore, a.conditionScore));
        }
        return out;
    }

    private int pageDetections(Ui ui, int x, int y0, int w) {
        int y = y0;
        DetectionStore st = BaseFinderClient.store;
        y = heading(ui, x, y, w, "Detections");

        int cx = x;
        String[][] chips = {{"ALL", "All"}, {"INTACT", "Intact"}, {"SUSPICIOUS", "Suspicious"}, {"GRIEFED", "Griefed"}, {"REJECTED", "Rejected"}, {"LEGACY", "Legacy"}};
        int cy = y;
        for (String[] c : chips) {
            if (c[0].equals("LEGACY") && st.count("LEGACY") == 0) continue;
            int n = c[0].equals("ALL") ? st.size() : st.count(c[0]);
            String label = c[1] + " " + n;
            int cwid = ui.width(label) + 16;
            if (cx + cwid > x + w) {
                cx = x;
                cy += 20;
            }
            final String f = c[0];
            cx += ui.chip(cx, cy, label, filter.equals(c[0]), c[0].equals("ALL") ? Theme.CYAN : Theme.classColor(c[0]),
                    "Show " + c[1].toLowerCase() + " detections", () -> filter = f) + 5;
        }
        String[] sortNames = {"Newest", "Likelihood", "Storage", "Condition"};
        int sw = ui.width("Sort: " + sortNames[sort]) + 18;
        if (cx + sw > x + w) {
            cx = x;
            cy += 20;
        }
        ui.button(cx, cy, sw, 16, "Sort: " + sortNames[sort], 0, 0, true, "Change the sort order", () -> sort = (sort + 1) % 4);
        y = cy + 24;

        List<DetectionStore.Detection> list = filtered();
        if (list.isEmpty()) {
            ui.card(x, y, w, 56, 0);
            ui.text(st.size() == 0 ? "No detections yet." : "No detections match this filter.", x + 14, y + 14, Theme.TEXT);
            ui.text("Base Finder analyses storage clusters as chunks load. Explore to find bases.", x + 14, y + 29, Theme.MUTED);
            return y + 56 - y0;
        }
        for (DetectionStore.Detection d : list) {
            int h = 64;
            if (ui.visible(y, h)) detectionCard(ui, d, x, y, w, h);
            y += h + 6;
        }
        return y - y0;
    }

    private void detectionCard(Ui ui, DetectionStore.Detection d, int x, int y, int w, int h) {
        int color = Theme.classColor(d.classification);
        boolean hv = ui.hovered(x, y, w, h);
        float a = ui.hov("det" + d.id, hv);
        ui.card(x, y, w, h, color);
        if (a > 0.01f) ui.rrect(x + 1, y + 1, w - 2, h - 2, 8, Theme.withAlpha(color, (int) (a * 22)));
        String cls = d.legacy() ? "LEGACY" : d.classification;
        int tw = ui.tag(x + w - 12 - (ui.width(cls) + 12), y + 8, cls, color);
        ui.bold(ui.fit(d.x + ", " + d.y + ", " + d.z, w - tw - 110), x + 12, y + 8, Theme.TEXT);
        ui.text(ui.fit(d.id + " \u00B7 " + Alerts.prettyDim(d.dimension), 150), x + 12 + 0, y + 19, Theme.MUTED);
        if (d.legacy()) {
            ui.text(ui.fit("Saved by an earlier version - open for details. Analysed again when seen.", w - 24), x + 12, y + 33, Theme.TEXT2);
            ui.text(ui.fit(d.storageCount + " storage blocks \u00B7 " + Ui.ago(d.foundAt), w - 24), x + 12, y + 46, Theme.MUTED);
        } else {
            ui.text(ui.fit("Likelihood " + d.likelihood + "% \u00B7 " + d.condition + " (" + d.conditionScore + ") \u00B7 Damage " + d.damageScore
                    + " \u00B7 Infra " + d.infraScore, w - 24), x + 12, y + 33, Theme.TEXT2);
            ui.text(ui.fit("Chests " + d.chests + (d.doubleChests > 0 ? " (" + d.doubleChests + " dbl)" : "") + " \u00B7 Barrels " + d.barrels
                    + " \u00B7 Shulkers " + d.shulkers + " \u00B7 Total " + d.totalStorage + " \u00B7 Cluster " + d.largestCluster
                    + " \u00B7 " + Ui.ago(d.foundAt), w - 24), x + 12, y + 46, Theme.MUTED);
        }
        final String id = d.id;
        ui.click(x, y, w, h, "Open details", () -> {
            selectedId = id;
            scrollTarget[1] = 0;
            scroll[1] = 0;
        });
    }

    // ------------------------------------------------------------------ DETAIL

    private int pageDetail(Ui ui, int x, int y0, int w) {
        DetectionStore.Detection d = BaseFinderClient.store.byId(selectedId);
        int y = y0;
        ui.button(x, y, 60, 18, "\u2190 Back", 0, 0, true, "Back to the list", () -> {
            selectedId = null;
            scrollTarget[1] = 0;
        });
        int color = Theme.classColor(d.classification);
        String cls = d.legacy() ? "LEGACY" : d.classification;
        ui.tag(x + 68, y + 3, cls, color);
        ui.text(d.id, x + 68 + ui.width(cls) + 20, y + 5, Theme.MUTED);
        y += 26;

        // coordinates card
        ui.card(x, y, w, 56, color);
        ui.bold(d.x + ", " + d.y + ", " + d.z, x + 14, y + 9, Theme.SNOW);
        ui.text("Chunk " + d.chunkX + ", " + d.chunkZ + " \u00B7 " + Alerts.prettyDim(d.dimension) + " \u00B7 found " + Ui.ago(d.foundAt), x + 14, y + 22, Theme.TEXT2);
        ui.text(ui.fit(d.server, w - 200), x + 14, y + 36, Theme.MUTED);
        final String coords = d.x + " " + d.y + " " + d.z;
        ui.button(x + w - 96, y + 8, 84, 18, "Copy coords", 1, 0, true, "Copy X Y Z to the clipboard", () -> {
            copyToClipboard(coords);
            setStatus("Coordinates copied");
        });
        boolean cached = BaseFinderClient.pipeline.cached(d.id) != null;
        final String id = d.id;
        ui.button(x + w - 96, y + 30, 84, 18, "View on map", 0, 0, cached,
                cached ? "Open the reconstruction" : "The reconstruction is no longer in memory (cache holds the latest detections). Re-observe the area.",
                () -> {
                    mapId = id;
                    mapCache = null;
                    setTab(5);
                });
        y += 64;

        if (d.legacy()) {
            ui.card(x, y, w, 50, Theme.SILVER);
            ui.text("This detection was saved by Base Finder 1.x.", x + 14, y + 12, Theme.TEXT);
            ui.text("It has no scores yet. It is analysed quietly (no duplicate report) when you see the area again.", x + 14, y + 26, Theme.MUTED);
            return y + 50 - y0;
        }

        // three independent scores
        int cols = w >= 380 ? 3 : 1;
        int sw = (w - (cols - 1) * 6) / cols;
        int sy = y;
        scoreCard(ui, x, sy, sw, "Base likelihood", d.likelihood + "%", d.likelihood / 100f, Theme.CYAN, "How likely this is a player base");
        scoreCard(ui, x + (cols == 3 ? sw + 6 : 0), sy + (cols == 3 ? 0 : 52), sw, "Structure condition", d.conditionScore + "/100 \u00B7 " + d.condition,
                d.conditionScore / 100f, d.damageScore >= 45 ? Theme.GRIEFED : d.damageScore >= 20 ? Theme.SUSPICIOUS : Theme.INTACT,
                "Damage score " + d.damageScore + " (kept separate from likelihood)");
        scoreCard(ui, x + (cols == 3 ? 2 * (sw + 6) : 0), sy + (cols == 3 ? 0 : 104), sw, "Functional infrastructure", d.infraScore + "/100",
                d.infraScore / 100f, Theme.PEARL, "Useful machines, farms, beds");
        y = sy + (cols == 3 ? 52 : 156) + 8;

        // storage
        ui.card(x, y, w, 62, 0);
        ui.bold("Storage", x + 12, y + 8, Theme.CYAN);
        ui.text(ui.fit(d.totalStorage + " units: " + d.chests + " chests (" + d.doubleChests + " double, counted once) \u00B7 " + d.barrels + " barrels \u00B7 "
                + d.shulkers + " shulker boxes", w - 24), x + 12, y + 22, Theme.TEXT);
        ui.text(ui.fit(d.clusters + " clusters \u00B7 largest " + d.largestCluster + " units \u00B7 density " + d.density + " per 100 blocks\u00B3", w - 24), x + 12, y + 34, Theme.TEXT2);
        ui.text("Inventory contents unavailable", x + 12, y + 47, Theme.MUTED);
        y += 70;

        y = reasons(ui, x, y, w, "Why " + cls.toLowerCase() + "", d.reasons, color);
        if (!d.rejectionReasons.isEmpty()) y = reasons(ui, x, y, w, "Rejection reasons", d.rejectionReasons, Theme.REJECTED);
        if (!d.suspicious.isEmpty()) y = reasons(ui, x, y, w, "Suspicious blocks", d.suspicious, Theme.SUSPICIOUS);
        y = reasons(ui, x, y, w, "Structure condition", d.conditionReasons, Theme.SUSPICIOUS);
        y = reasons(ui, x, y, w, "Base likelihood breakdown", d.likelihoodReasons, Theme.CYAN);
        y = reasons(ui, x, y, w, "Infrastructure breakdown", d.infraReasons, Theme.PEARL);
        y = reasons(ui, x, y, w, "Data quality", d.dataQuality, Theme.MUTED);
        if (!d.classHistory.isEmpty()) y = reasons(ui, x, y, w, "Classification history", d.classHistory, Theme.SILVER);
        return y - y0;
    }

    private void scoreCard(Ui ui, int x, int y, int w, String title, String value, float frac, int color, String sub) {
        ui.card(x, y, w, 46, color);
        ui.text(ui.fit(title, w - 20), x + 12, y + 7, Theme.TEXT2);
        ui.bold(ui.fit(value, w - 20), x + 12, y + 18, color);
        ui.gauge(x + 12, y + 31, w - 24, 5, frac, color);
        if (ui.hovered(x, y, w, 46)) ui.pendingTip = sub;
    }

    /** A titled card with wrapped bullet lines. [observed]/[inferred]/[unknown] prefixes are coloured. */
    private int reasons(Ui ui, int x, int y, int w, String title, List<String> lines, int color) {
        if (lines == null || lines.isEmpty()) return y;
        List<String[]> rows = new ArrayList<>(); // {tag, text}
        for (String l : lines) {
            String tag = "", text = l;
            if (l.startsWith("[") && l.indexOf(']') > 0) {
                tag = l.substring(1, l.indexOf(']'));
                text = l.substring(l.indexOf(']') + 1).trim();
            }
            rows.add(new String[]{tag, text});
        }
        List<List<String>> wrapped = new ArrayList<>();
        int h = 26;
        for (String[] r : rows) {
            int off = r[0].isEmpty() ? 0 : ui.width(r[0]) + 8;
            List<String> wl = ui.wrap(r[1], w - 36 - off);
            wrapped.add(wl);
            h += wl.size() * 11 + 2;
        }
        h += 6;
        if (ui.visible(y, h)) {
            ui.card(x, y, w, h, color);
            ui.bold(title, x + 12, y + 8, color);
            int ty = y + 22;
            for (int i = 0; i < rows.size(); i++) {
                String tag = rows.get(i)[0];
                int off = 0;
                if (!tag.isEmpty()) {
                    int tc = tag.equals("observed") ? Theme.OK : tag.equals("inferred") ? Theme.SUSPICIOUS : Theme.MUTED;
                    ui.text(tag, x + 14, ty, tc);
                    off = ui.width(tag) + 8;
                }
                for (String wl : wrapped.get(i)) {
                    ui.text(wl, x + 14 + off, ty, Theme.TEXT);
                    ty += 11;
                }
                ty += 2;
            }
        }
        return y + h + 6;
    }

    // ------------------------------------------------------------------ DISCORD

    private int pageDiscord(Ui ui, Config cfg, int x, int y0, int w) {
        int y = y0;
        y = heading(ui, x, y, w, "Discord channels");

        boolean noneConfigured = !cfg.anyNewWebhookConfigured();
        if (noneConfigured) {
            int bh = 56;
            ui.card(x, y, w, bh, Theme.SUSPICIOUS);
            ui.bold("Set up your Discord channels", x + 14, y + 8, Theme.SUSPICIOUS);
            List<String> ls = ui.wrap("No webhook is configured yet. Base reports are kept locally until you set the Intact, Suspicious and Griefed channels below. "
                    + "Nothing is ever sent to a channel you did not configure.", w - 28);
            for (int i = 0; i < Math.min(3, ls.size()); i++) ui.text(ls.get(i), x + 14, y + 22 + i * 11, Theme.TEXT2);
            y += bh + 8;
        }
        if (!cfg.discordWebhookUrl.isBlank()) {
            int bh = 50;
            ui.card(x, y, w, bh, Theme.ICE);
            ui.bold("Old single webhook found", x + 14, y + 7, Theme.ICE);
            ui.text(ui.fit(WebhookUtil.mask(cfg.discordWebhookUrl) + " (kept, never used for routing)", w - 28), x + 14, y + 20, Theme.MUTED);
            int bx = x + 14;
            for (Channel c : new Channel[]{Channel.INTACT, Channel.SUSPICIOUS, Channel.GRIEFED}) {
                final Channel ch = c;
                String label = "Use for " + c.label.replace(" Bases", "");
                int bw = ui.width(label) + 16;
                ui.button(bx, y + 30, bw, 16, label, 0, 0, true, "Copy the old webhook into the " + c.label + " channel", () -> {
                    cfg.webhook(ch).url = cfg.discordWebhookUrl;
                    cfg.webhook(ch).enabled = true;
                    cfg.migrationNotice = "";
                    channelMsg.put(ch, "Imported the old webhook");
                    dirty();
                });
                bx += bw + 6;
            }
            y += bh + 8;
        }

        y = heading(ui, x, y, w, "Base classification (each report goes to exactly one)");
        Channel[] base = {Channel.INTACT, Channel.SUSPICIOUS, Channel.GRIEFED};
        y = channelCards(ui, cfg, base, x, y, w);
        y = heading(ui, x, y + 2, w, "TOP PRIORITY alerts (independent of bases)");
        y = channelCards(ui, cfg, new Channel[]{Channel.PLAYER, Channel.PEARL, Channel.DEATH}, x, y, w);
        y = heading(ui, x, y + 2, w, "Fallback for priority alerts");
        y = channelCards(ui, cfg, new Channel[]{Channel.FALLBACK}, x, y, w);

        y = heading(ui, x, y + 2, w, "Message options");
        ui.card(x, y, w, 84, 0);
        ui.toggle(x + 14, y + 8, w - 28, 18, "Attach reconstructions (2D map, 3D, isometric)", cfg.attachReconstructions, "Attach the generated PNGs to base reports",
                () -> { cfg.attachReconstructions = !cfg.attachReconstructions; dirty(); });
        ui.toggle(x + 14, y + 29, w - 28, 18, "Show server address in messages", cfg.includeServerAddress, "Include the server in the footer",
                () -> { cfg.includeServerAddress = !cfg.includeServerAddress; dirty(); });
        ui.toggle(x + 14, y + 50, w - 28, 18, "Notify when a base is reclassified", cfg.notifyReclassification, "Send a new message if a reported base changes class",
                () -> { cfg.notifyReclassification = !cfg.notifyReclassification; dirty(); });
        y += 84;
        return y - y0 + 8;
    }

    private int channelCards(Ui ui, Config cfg, Channel[] chans, int x, int y, int w) {
        int cols = w >= 560 ? Math.min(3, chans.length) : (w >= 360 ? Math.min(2, chans.length) : 1);
        int cw = (w - (cols - 1) * 6) / cols;
        int ch = 112;
        for (int i = 0; i < chans.length; i++) {
            int col = i % cols, row = i / cols;
            channelCard(ui, cfg, chans[i], x + col * (cw + 6), y + row * (ch + 6), cw, ch);
        }
        int rows = (chans.length + cols - 1) / cols;
        return y + rows * (ch + 6);
    }

    private void channelCard(Ui ui, Config cfg, Channel c, int x, int y, int w, int h) {
        int color = Theme.channelColor(c);
        Config.Webhook wh = cfg.webhook(c);
        DiscordManager.Status s = BaseFinderClient.discord.status(c);
        ui.card(x, y, w, h, color);
        ui.bold(ui.fit(c.label, w - 70), x + 12, y + 8, color);
        ui.statusDot(x + w - 18, y + 10, switch (s.state()) {
            case OK, READY -> Theme.OK;
            case SENDING -> Theme.CYAN;
            case ERROR, INVALID -> Theme.ERR;
            case DISABLED -> Theme.SUSPICIOUS;
            default -> Theme.MUTED;
        });
        if (s.state() == DiscordManager.State.SENDING) ui.spinner(x + w - 34, y + 13, Theme.CYAN);
        String msg = channelMsg.get(c);
        int sc = s.state() == DiscordManager.State.ERROR || s.state() == DiscordManager.State.INVALID ? Theme.ERR
                : s.state() == DiscordManager.State.OK ? Theme.OK : Theme.TEXT2;
        ui.text(ui.fit(s.message(), w - 24), x + 12, y + 22, sc);
        ui.text(ui.fit(WebhookUtil.mask(wh.url), w - 24), x + 12, y + 34, Theme.MUTED);
        if (msg != null) ui.text(ui.fit(msg, w - 24), x + 12, y + 46, Theme.SUSPICIOUS);

        final Channel ch = c;
        ui.toggle(x + 12, y + 58, w - 24, 14, "Enabled", wh.enabled, "Turn this destination on or off", () -> {
            wh.enabled = !wh.enabled;
            dirty();
        });
        int bw = (w - 24 - 8) / 3;
        int by = y + 78;
        ui.button(x + 12, by, bw, 18, "Paste", 0, 0, true, "Paste a Discord webhook URL from the clipboard", () -> {
            String clip = clipboard();
            if (clip != null && WebhookUtil.isValid(clip.trim())) {
                wh.url = clip.trim();
                wh.enabled = true;
                cfg.migrationNotice = "";
                channelMsg.put(ch, "Webhook saved");
                dirty();
            } else {
                channelMsg.put(ch, "Clipboard is not a valid Discord webhook URL");
            }
        });
        ui.button(x + 12 + bw + 4, by, bw, 18, "Clear", 0, 0, !wh.url.isBlank(), "Remove this webhook", () -> {
            wh.url = "";
            channelMsg.put(ch, "Cleared");
            dirty();
        });
        boolean canTest = !wh.url.isBlank() && wh.enabled && WebhookUtil.isValid(wh.url) && !BaseFinderClient.discord.busy(c);
        ui.button(x + 12 + 2 * (bw + 4), by, bw, 18, "Test", 2, color, canTest,
                canTest ? "Send a test message to this destination" : "Configure and enable a valid webhook first", () -> {
                    flush();
                    BaseFinderClient.discord.test(ch);
                    channelMsg.put(ch, "Test sent...");
                });
    }

    // ------------------------------------------------------------------ STORAGE

    private int pageStorage(Ui ui, int x, int y0, int w) {
        int y = y0;
        y = heading(ui, x, y, w, "Storage analysis");
        List<DetectionStore.Detection> list = new ArrayList<>();
        for (DetectionStore.Detection d : BaseFinderClient.store.snapshot()) {
            if (d.classification != null && !d.classification.equals("REJECTED")) list.add(d);
        }
        if (list.isEmpty()) {
            ui.card(x, y, w, 56, 0);
            ui.text("No analysed bases yet.", x + 14, y + 14, Theme.TEXT);
            ui.text("Storage statistics appear after Base Finder analyses a base.", x + 14, y + 29, Theme.MUTED);
            return y + 56 - y0;
        }
        int chests = 0, dbl = 0, barrels = 0, shulkers = 0, clusters = 0, largest = 0;
        double dens = 0;
        for (DetectionStore.Detection d : list) {
            chests += d.chests;
            dbl += d.doubleChests;
            barrels += d.barrels;
            shulkers += d.shulkers;
            clusters += d.clusters;
            largest = Math.max(largest, d.largestCluster);
            dens += d.density;
        }
        int cols = columns(w, 92);
        int tw = (w - (cols - 1) * 6) / cols;
        String[][] t = {
                {"Total storage units", String.valueOf(chests + barrels + shulkers), String.valueOf(Theme.CYAN), "Double chests count once."},
                {"Chests", chests + " (" + dbl + " double)", String.valueOf(0xFFC17B2F), "Chests and trapped chests."},
                {"Barrels", String.valueOf(barrels), String.valueOf(0xFFE0A030), "Barrels."},
                {"Shulker boxes", String.valueOf(shulkers), String.valueOf(0xFFA05CC8), "Shulker boxes of every colour."},
                {"Storage clusters", String.valueOf(clusters), String.valueOf(Theme.ICE), "Groups of nearby storage."},
                {"Largest cluster", largest + " units", String.valueOf(Theme.INTACT), "Biggest single cluster seen."},
                {"Average density", String.format(java.util.Locale.ROOT, "%.1f", dens / list.size()), String.valueOf(Theme.SUSPICIOUS), "Units per 100 blocks\u00B3 in each base's largest cluster."}};
        y = tileRow(ui, t, x, y, tw, 42, cols);

        ui.card(x, y, w, 52, 0);
        ui.bold("Distribution", x + 12, y + 8, Theme.CYAN);
        stackedBar(ui, x + 12, y + 24, w - 24, 10, chests, barrels, shulkers);
        ui.text("Chests", x + 12, y + 38, 0xFFC17B2F);
        ui.text("Barrels", x + 12 + ui.width("Chests") + 14, y + 38, 0xFFE0A030);
        ui.text("Shulkers", x + 12 + ui.width("Chests") + ui.width("Barrels") + 28, y + 38, 0xFFA05CC8);
        y += 60;

        ui.card(x, y, w, 52, Theme.SUSPICIOUS);
        ui.bold("Inventory analysis", x + 12, y + 8, Theme.SUSPICIOUS);
        List<String> ls = ui.wrap("Inventory contents unavailable. The client only receives what is inside a container when a player opens it. "
                + "Base Finder never opens or interacts with containers, so it cannot tell an empty chest from a full one.", w - 28);
        for (int i = 0; i < Math.min(3, ls.size()); i++) ui.text(ls.get(i), x + 12, y + 22 + i * 10, Theme.TEXT2);
        y += 60;

        y = heading(ui, x, y, w, "Largest clusters");
        List<DetectionStore.Detection> top = new ArrayList<>(list);
        top.sort((a, b) -> Integer.compare(b.largestCluster, a.largestCluster));
        for (int i = 0; i < Math.min(6, top.size()); i++) {
            DetectionStore.Detection d = top.get(i);
            ui.statusDot(x + 6, y + 3, Theme.classColor(d.classification));
            ui.text(ui.fit(d.largestCluster + " units \u00B7 " + d.x + ", " + d.y + ", " + d.z + " \u00B7 " + d.id, w - 20), x + 20, y, Theme.TEXT);
            y += 13;
        }
        return y - y0 + 4;
    }

    // ------------------------------------------------------------------ ALERTS

    private int pageAlerts(Ui ui, int x, int y0, int w) {
        int y = y0;
        y = heading(ui, x, y, w, "Alerts and history");
        HistoryStore h = BaseFinderClient.history;
        String[][] chips = {{"ALL", "All"}, {"PLAYER", "Players"}, {"PEARL", "Ender pearls"}, {"DEATH", "Deaths"}, {"BASE_INTACT", "Intact"},
                {"BASE_SUSPICIOUS", "Suspicious"}, {"BASE_GRIEFED", "Griefed"}, {"BASE_REJECTED", "Rejected"}};
        int cx = x, cy = y;
        for (String[] c : chips) {
            int n = c[0].equals("ALL") ? h.recent(100000).size() : h.count(c[0]);
            String label = c[1] + " " + n;
            int cwid = ui.width(label) + 16;
            if (cx + cwid > x + w) {
                cx = x;
                cy += 20;
            }
            final String f = c[0];
            cx += ui.chip(cx, cy, label, alertFilter.equals(c[0]), c[0].equals("ALL") ? Theme.CYAN : Theme.eventColor(c[0]), "Filter the list", () -> alertFilter = f) + 5;
        }
        y = cy + 24;

        List<HistoryStore.Event> ev = alertFilter.equals("ALL") ? h.recent(60) : h.ofType(alertFilter, 60);
        if (ev.isEmpty()) {
            ui.card(x, y, w, 56, 0);
            ui.text("Nothing here yet.", x + 14, y + 14, Theme.TEXT);
            ui.text("Player, ender pearl, death and base alerts are listed as they happen.", x + 14, y + 29, Theme.MUTED);
            return y + 56 - y0;
        }
        for (HistoryStore.Event e : ev) {
            int color = Theme.eventColor(e.type);
            String detail = e.detail == null ? "" : e.detail;
            List<String> dl = ui.wrap(detail, w - 28);
            int hgt = 30 + Math.min(3, dl.size()) * 11;
            if (ui.visible(y, hgt)) {
                ui.card(x, y, w, hgt, color);
                String label = switch (e.type) {
                    case "PLAYER" -> "PLAYER";
                    case "PEARL" -> "ENDER PEARL";
                    case "DEATH" -> "DEATH";
                    case "RECLASSIFIED" -> "RECLASSIFIED";
                    default -> e.type.replace("BASE_", "");
                };
                int tw = ui.tag(x + 12, y + 7, label, color);
                ui.text(ui.fit(e.text == null ? "" : e.text, w - tw - 100), x + 12 + tw + 8, y + 8, Theme.TEXT);
                ui.textRight(Ui.ago(e.time), x + w - 12, y + 8, Theme.MUTED);
                String pos = e.hasPos ? e.x + ", " + e.y + ", " + e.z + " \u00B7 " + Alerts.prettyDim(e.dimension) : Alerts.prettyDim(e.dimension == null ? "" : e.dimension);
                ui.text(ui.fit(pos, w - 24), x + 12, y + 21, Theme.TEXT2);
                for (int i = 0; i < Math.min(3, dl.size()); i++) ui.text(dl.get(i), x + 12, y + 32 + i * 11, Theme.MUTED);
            }
            y += hgt + 5;
        }
        return y - y0;
    }

    // ------------------------------------------------------------------ MAP

    private static final class MapCache {
        String key;
        int[] colors;           // floor plan / overhead: sx * sz ARGB
        int cols, rows;
        final List<int[]> voxels = new ArrayList<>(); // x, y, z, frontColor, capColor
        int planY;
        int floorY, cutY;
        double observed;
    }

    private int pageMap(Ui ui, Config cfg, int x, int y0, int w, int viewHeight) {
        int y = y0;
        y = heading(ui, x, y, w, "Map and reconstruction");

        List<String> ids = BaseFinderClient.pipeline.cachedIds();
        if (ids.isEmpty()) {
            ui.card(x, y, w, 70, 0);
            ui.text("No reconstruction is available yet.", x + 14, y + 12, Theme.TEXT);
            ui.text(ui.fit("Reconstructions are built from blocks the client observed when a base is analysed.", w - 28), x + 14, y + 27, Theme.MUTED);
            ui.text(ui.fit("The latest " + cfg.snapshotCacheSize + " are kept in memory; PNG copies are saved in config/basefinder/reconstructions.", w - 28), x + 14, y + 40, Theme.MUTED);
            return y + 70 - y0;
        }
        if (mapId == null || !ids.contains(mapId)) {
            mapId = ids.get(ids.size() - 1);
            mapCache = null;
        }
        int idx = ids.indexOf(mapId);
        DetectionPipeline.Cached cached = BaseFinderClient.pipeline.cached(mapId);
        DetectionStore.Detection det = BaseFinderClient.store.byId(mapId);

        // selector
        ui.button(x, y, 22, 18, "<", 0, 0, true, "Previous location", () -> {
            mapId = ids.get((idx + ids.size() - 1) % ids.size());
            mapCache = null;
        });
        ui.button(x + 26, y, 22, 18, ">", 0, 0, true, "Next location", () -> {
            mapId = ids.get((idx + 1) % ids.size());
            mapCache = null;
        });
        String title = (idx + 1) + "/" + ids.size() + "  " + mapId + (det != null ? "  " + det.x + ", " + det.y + ", " + det.z : "");
        ui.text(ui.fit(title, w - 60), x + 56, y + 5, Theme.TEXT);
        y += 24;

        String[] modes = {"Floor plan", "Overhead", "3D voxel"};
        int cx = x;
        for (int i = 0; i < modes.length; i++) {
            final int m = i;
            cx += ui.chip(cx, y, modes[i], mapMode == i, Theme.CYAN, i == 0 ? "Walls and rooms at the storage level" : i == 1 ? "Top-down view" : "Oblique 3D view", () -> {
                mapMode = m;
                mapCache = null;
            }) + 5;
        }
        if (mapMode == 2) {
            cx += ui.chip(cx, y, mapCut ? "Cut-away: on" : "Cut-away: off", mapCut, Theme.SUSPICIOUS, "Hide roofs to see inside", () -> {
                mapCut = !mapCut;
                mapCache = null;
            }) + 5;
        }
        y += 22;

        Observation o = cached.obs();
        BaseReport r = cached.report();
        Snapshot s = o.snap;
        String key = mapId + "|" + mapMode + "|" + mapCut;
        if (mapCache == null || !key.equals(mapCache.key)) mapCache = buildMap(o, r, key);

        int availW = w - 24;
        int cell;
        int mapW, mapH;
        if (mapMode == 2) {
            int unitsW = (2 * s.sx + s.sz) / 2 + 2, unitsH = (s.sz + 2 * (mapCache.cutY - mapCache.floorY + 1)) / 2 + 2;
            cell = Math.max(2, Math.min(7, Math.min(availW / unitsW, 220 / unitsH)));
            mapW = unitsW * cell;
            mapH = unitsH * cell;
        } else {
            cell = Math.max(2, Math.min(7, Math.min(availW / s.sx, 230 / s.sz)));
            mapW = s.sx * cell;
            mapH = s.sz * cell;
        }
        int cardH = mapH + 30;
        ui.card(x, y, w, cardH, 0);
        int mx0 = x + 12, my0 = y + 20;
        ui.text(mapMode == 0 ? "Floor plan at Y " + (s.oy + mapCache.planY) : mapMode == 1 ? "Overhead" : "3D voxel (oblique)", x + 12, y + 7, Theme.CYAN);
        ui.textRight((int) Math.round(mapCache.observed * 100) + "% observed", x + w - 12, y + 7, Theme.TEXT2);
        ui.rrect(mx0 - 2, my0 - 2, mapW + 4, mapH + 4, 4, 0xFF050B18);
        if (mapMode == 2) {
            drawVoxels(ui, mapCache, mx0, my0, cell);
        } else {
            for (int z = 0; z < mapCache.rows; z++) {
                for (int xx = 0; xx < mapCache.cols; xx++) {
                    int c = mapCache.colors[xx + z * mapCache.cols];
                    ui.fill(mx0 + xx * cell, my0 + z * cell, cell, cell, c);
                    if (c == 0xFF1B2433 && cell >= 3 && (xx + z) % 2 == 0) ui.fill(mx0 + xx * cell, my0 + z * cell, 1, 1, 0xFF3A4A66); // hatch
                }
            }
            // storage markers
            for (StoragePoint sp : o.storage) {
                int rx = s.relX(sp.x()), rz = s.relZ(sp.z());
                if (rx < 0 || rz < 0 || rx >= s.sx || rz >= s.sz) continue;
                ui.fill(mx0 + rx * cell, my0 + rz * cell, cell, 1, 0xFFFFFFFF);
            }
        }
        y += cardH + 8;

        // legend + accuracy
        ui.card(x, y, w, 66, 0);
        Cat[] legend = {Cat.BUILT, Cat.NATURAL, Cat.DOOR, Cat.GLASS, Cat.CHEST, Cat.BARREL, Cat.SHULKER, Cat.INFRA, Cat.BED, Cat.FARMLAND, Cat.UNKNOWN};
        int lx = x + 12, ly = y + 8;
        for (Cat c : legend) {
            String label = Palette.label(c);
            int lw = ui.width(label) + 24;
            if (lx + lw > x + w - 8) {
                lx = x + 12;
                ly += 12;
            }
            ui.fill(lx, ly + 1, 8, 8, 0xFF000000 | Palette.rgb(c));
            ui.text(label, lx + 12, ly, Theme.TEXT2);
            lx += lw + 4;
        }
        ui.text(ui.fit("Known blocks are shown as seen. Dark hatched = unknown/unloaded. Shapes are estimated from block types. Not a screenshot.", w - 24),
                x + 12, y + 50, Theme.SUSPICIOUS);
        y += 74;
        if (det != null && det.hasMaps) {
            ui.text(ui.fit("PNG files: config/basefinder/reconstructions/" + det.id + "-plan.png / -voxel.png / -iso.png", w - 8), x, y, Theme.MUTED);
            y += 14;
        }
        return y - y0;
    }

    private MapCache buildMap(Observation o, BaseReport r, String key) {
        Snapshot s = o.snap;
        MapCache m = new MapCache();
        m.key = key;
        m.cols = s.sx;
        m.rows = s.sz;
        m.observed = r.coverage;
        // plan level = median storage level
        if (o.storage.isEmpty()) m.planY = Math.max(0, Math.min(s.sy - 1, s.relY(o.centerY)));
        else {
            int[] ys = new int[o.storage.size()];
            for (int i = 0; i < ys.length; i++) ys[i] = s.relY(o.storage.get(i).y());
            java.util.Arrays.sort(ys);
            m.planY = Math.max(0, Math.min(s.sy - 1, ys[ys.length / 2]));
        }
        int minSt = Integer.MAX_VALUE, maxSt = 0;
        for (StoragePoint sp : o.storage) {
            minSt = Math.min(minSt, s.relY(sp.y()));
            maxSt = Math.max(maxSt, s.relY(sp.y()));
        }
        int cr = s.relY(o.centerY);
        m.floorY = Math.max(0, (o.storage.isEmpty() ? cr : minSt) - 4);
        m.cutY = mapCut ? Math.min(s.sy - 1, (o.storage.isEmpty() ? cr : maxSt) + 1) : s.sy - 1;

        if (mapMode == 0) {
            m.colors = new int[s.sx * s.sz];
            for (int z = 0; z < s.sz; z++) {
                for (int x = 0; x < s.sx; x++) {
                    Cat c = s.get(x, m.planY, z);
                    int col;
                    if (c == Cat.UNKNOWN) col = 0xFF1B2433;
                    else if (c.passable && c != Cat.DOOR && c != Cat.WATER && c != Cat.LAVA && c != Cat.FIRE) {
                        Cat floor = Cat.UNKNOWN;
                        int depth = 0;
                        for (int d = 1; d <= 8; d++) {
                            Cat f = s.get(x, m.planY - d, z);
                            if (f == Cat.UNKNOWN) break;
                            if (f.solid) {
                                floor = f;
                                depth = d;
                                break;
                            }
                        }
                        col = floor == Cat.UNKNOWN ? 0xFF0F1B2E : shade(0xFF000000 | Palette.rgb(floor), floor == Cat.BUILT ? 0.8f : Math.max(0.3f, 0.55f - 0.03f * depth));
                    } else col = 0xFF000000 | Palette.rgb(c);
                    m.colors[x + z * s.sx] = col;
                }
            }
        } else if (mapMode == 1) {
            m.colors = new int[s.sx * s.sz];
            for (int z = 0; z < s.sz; z++) {
                for (int x = 0; x < s.sx; x++) {
                    int col = 0xFF1B2433;
                    for (int y = s.sy - 1; y >= 0; y--) {
                        Cat c = s.get(x, y, z);
                        if (c == Cat.UNKNOWN) break;
                        if (c == Cat.AIR || c == Cat.PLANT || c == Cat.DECOR || c == Cat.LIGHT || c == Cat.CROP || c == Cat.PORTAL || c == Cat.FIRE) continue;
                        col = shade(0xFF000000 | Palette.rgb(c), 0.7f + 0.3f * (y / (float) s.sy));
                        break;
                    }
                    m.colors[x + z * s.sx] = col;
                }
            }
        } else {
            for (int z = 0; z < s.sz && m.voxels.size() < 9000; z++) {
                for (int y = m.floorY; y <= m.cutY && m.voxels.size() < 9000; y++) {
                    for (int x = 0; x < s.sx; x++) {
                        Cat c = s.get(x, y, z);
                        if (!(c.solid || c == Cat.DOOR || c == Cat.WATER || c == Cat.LAVA)) continue;
                        boolean top = !opaque(s, x, y + 1, z, m.cutY), front = !opaque(s, x, y, z + 1, m.cutY), right = !opaque(s, x + 1, y, z, m.cutY);
                        if (!top && !front && !right) continue;
                        int base = 0xFF000000 | Palette.rgb(c);
                        if (c == Cat.NATURAL) base = shade(base, 0.78f);
                        m.voxels.add(new int[]{x, y, z, shade(base, 0.72f), base});
                        if (m.voxels.size() >= 9000) break;
                    }
                }
            }
        }
        return m;
    }

    private static boolean opaque(Snapshot s, int x, int y, int z, int cutY) {
        if (y > cutY) return false;
        Cat c = s.get(x, y, z);
        return c.solid && c != Cat.GLASS;
    }

    private static int shade(int argb, float f) {
        int r = Math.min(255, (int) (((argb >> 16) & 255) * f)), g = Math.min(255, (int) (((argb >> 8) & 255) * f)), b = Math.min(255, (int) ((argb & 255) * f));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private void drawVoxels(Ui ui, MapCache m, int ox, int oy, int cell) {
        for (int[] v : m.voxels) {
            int sx = ox + (v[0] * 2 + v[2]) * cell / 2;
            int sy = oy + (v[2] - 2 * (v[1] - m.floorY)) * cell / 2 + (m.cutY - m.floorY) * cell;
            ui.fill(sx, sy, cell, cell, v[3]);
            ui.fill(sx, sy - Math.max(1, cell / 2), cell, Math.max(1, cell / 2), v[4]);
        }
    }

    // ------------------------------------------------------------------ SETTINGS

    private int pageSettings(Ui ui, Config cfg, int x, int y0, int w) {
        int y = y0;
        y = heading(ui, x, y, w, "Settings (saved automatically)");

        int cx = x, cy = y;
        for (String name : settings.keySet()) {
            int cwid = ui.width(name) + 16;
            if (cx + cwid > x + w) {
                cx = x;
                cy += 20;
            }
            final String n = name;
            cx += ui.chip(cx, cy, name, section.equals(name), Theme.CYAN, "Show " + name + " settings", () -> {
                section = n;
                scrollTarget[6] = 0;
            }) + 5;
        }
        String disc = "Discord \u2192";
        int dw = ui.width(disc) + 16;
        if (cx + dw > x + w) {
            cx = x;
            cy += 20;
        }
        ui.chip(cx, cy, disc, false, Theme.PEARL, "Open the Discord page", () -> setTab(2));
        y = cy + 24;

        List<SettingsRegistry.Row> rows = settings.get(section);
        int rowH = 22;
        int total = 12;
        for (SettingsRegistry.Row r : rows) total += r.kind == SettingsRegistry.Row.INFO ? 12 + ui.wrap(r.tip, w - 28).size() * 11 + 6 : rowH;
        ui.card(x, y, w, total, 0);
        int ry = y + 6;
        for (SettingsRegistry.Row r : rows) {
            switch (r.kind) {
                case SettingsRegistry.Row.BOOL -> ui.toggle(x + 14, ry, w - 28, rowH - 2, r.label, r.getB.getAsBoolean(), r.tip, () -> {
                    r.setB.accept(!r.getB.getAsBoolean());
                    dirty();
                });
                case SettingsRegistry.Row.INT -> ui.stepper(x + 14, ry, w - 28, rowH - 2, r.label, String.valueOf(r.getI.getAsInt()), r.tip, () -> {
                    r.setI.accept(Math.max(r.min, r.getI.getAsInt() - r.step));
                    dirty();
                }, () -> {
                    r.setI.accept(Math.min(r.max, r.getI.getAsInt() + r.step));
                    dirty();
                });
                case SettingsRegistry.Row.DOUBLE -> ui.stepper(x + 14, ry, w - 28, rowH - 2, r.label, SettingsRegistry.fmt(r.getD.getAsDouble()), r.tip, () -> {
                    r.setD.accept(Math.max(r.dmin, Math.round((r.getD.getAsDouble() - r.dstep) * 1000) / 1000.0));
                    dirty();
                }, () -> {
                    r.setD.accept(Math.min(r.dmax, Math.round((r.getD.getAsDouble() + r.dstep) * 1000) / 1000.0));
                    dirty();
                });
                case SettingsRegistry.Row.CYCLE -> {
                    ui.text(ui.fit(r.label, w - 150), x + 14, ry + 7, Theme.TEXT);
                    ui.button(x + w - 14 - 96, ry + 1, 96, rowH - 4, r.cycleLabel.get(), 0, 0, true, r.tip, () -> {
                        r.cycleNext.run();
                        dirty();
                    });
                }
                default -> {
                    ui.bold(r.label, x + 14, ry + 4, Theme.SUSPICIOUS);
                    int ty = ry + 16;
                    for (String l : ui.wrap(r.tip, w - 28)) {
                        ui.text(l, x + 14, ty, Theme.TEXT2);
                        ty += 11;
                    }
                    ry += 12 + ui.wrap(r.tip, w - 28).size() * 11 + 6 - rowH;
                }
            }
            ry += rowH;
        }
        y += total + 8;

        ui.button(x, y, 150, 18, "Test in-game alert", 0, 0, true, "Shows a chat message and plays the alert sound", () -> {
            flush();
            Alerts.testAlert(this.minecraft);
            setStatus("In-game test alert shown");
        });
        y += 24;
        return y - y0;
    }
}
