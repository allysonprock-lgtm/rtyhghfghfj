package dev.basefinder;

import java.util.Random;

/** Snowy mountains at night: gradient sky, twinkling stars, a faint aurora, layered peaks with snow caps and falling snow. */
final class WinterBackground {
    private static final class Flake {
        float x, y, speed, sway, phase;
        int size;
    }

    private final Flake[] flakes = new Flake[250];
    private final float[] starX = new float[140], starY = new float[140], starPhase = new float[140];
    private final Random rnd = new Random(1234);
    private int w, h;

    private void layout(int width, int height) {
        w = width;
        h = height;
        for (int i = 0; i < starX.length; i++) {
            starX[i] = rnd.nextFloat() * width;
            starY[i] = rnd.nextFloat() * height * 0.55f;
            starPhase[i] = rnd.nextFloat() * 6.28f;
        }
        for (int i = 0; i < flakes.length; i++) {
            Flake f = new Flake();
            f.x = rnd.nextFloat() * width;
            f.y = rnd.nextFloat() * height;
            f.speed = 12 + rnd.nextFloat() * 38;
            f.sway = 3 + rnd.nextFloat() * 12;
            f.phase = rnd.nextFloat() * 6.28f;
            float r = rnd.nextFloat();
            f.size = r < 0.6f ? 1 : (r < 0.88f ? 2 : 3);
            flakes[i] = f;
        }
    }

    void draw(Ui ui, int width, int height, Config cfg) {
        if (width != w || height != h) layout(width, height);
        boolean anim = ui.anim;

        // sky
        for (int y = 0; y < height; y += 3) {
            float t = y / (float) height;
            int c = t < 0.55f ? Theme.mix(Theme.NIGHT_TOP, Theme.NIGHT_MID, t / 0.55f)
                    : Theme.mix(Theme.NIGHT_MID, Theme.HORIZON, (t - 0.55f) / 0.45f);
            ui.fill(0, y, width, 3, c);
        }

        // stars
        for (int i = 0; i < starX.length; i++) {
            float tw = anim && cfg.starTwinkle ? 0.55f + 0.45f * (float) Math.sin(ui.time * 1.6f + starPhase[i]) : 0.8f;
            int a = (int) (60 + 150 * tw);
            int s = i % 9 == 0 ? 2 : 1;
            ui.fill((int) starX[i], (int) starY[i], s, s, Theme.withAlpha(0xFFFFFF, a));
        }

        // aurora (two soft bands)
        for (int band = 0; band < 2; band++) {
            for (int x = 0; x < width; x += 4) {
                double wave = Math.sin(x * 0.011 + band * 2.1 + (anim ? ui.time * 0.25 : 0)) * 14 + Math.sin(x * 0.027 + band) * 6;
                int cy = (int) (height * (0.22 + band * 0.1) + wave);
                int c = band == 0 ? 0x5BD6FF : 0x5EEAB4;
                ui.fill(x, cy, 4, 22, Theme.withAlpha(c, 14));
                ui.fill(x, cy + 6, 4, 10, Theme.withAlpha(c, 12));
            }
        }

        // mountains: three layers
        peaks(ui, width, height, 0.60, 0.30, 0.0075, 0.4, 0xFF13264B, 0xFF9FC5EE, 0.80);
        peaks(ui, width, height, 0.70, 0.26, 0.0105, 2.3, 0xFF1A3560, 0xFFC4DDF7, 0.74);
        peaks(ui, width, height, 0.80, 0.20, 0.0150, 4.1, 0xFF254A7F, 0xFFE6F2FF, 0.70);

        // ground snow
        for (int x = 0; x < width; x += 3) {
            int bump = 4 + (int) (3 * Math.sin(x * 0.05 + 0.7) + 2 * Math.sin(x * 0.13));
            ui.fill(x, height - 10 - bump, 3, 10 + bump, 0xFFDCEBFA);
        }
        for (int x = 0; x < width; x += 3) {
            int bump = 3 + (int) (2 * Math.sin(x * 0.07 + 2.0) + 2 * Math.sin(x * 0.17));
            ui.fill(x, height - 6 - bump, 3, 6 + bump, 0xFFF4FAFF);
        }

        // falling snow
        if (cfg.snowAnimation && cfg.snowDensity > 0) {
            int n = Math.min(flakes.length, cfg.snowDensity);
            for (int i = 0; i < n; i++) {
                Flake f = flakes[i];
                if (anim) {
                    f.y += f.speed * ui.dt;
                    f.x += (float) Math.sin(ui.time * 0.9f + f.phase) * f.sway * ui.dt;
                    if (f.y > height - 10) {
                        f.y = -4;
                        f.x = rnd.nextFloat() * width;
                    }
                }
                int a = f.size == 1 ? 0x99 : f.size == 2 ? 0xCC : 0xE6;
                int x = (int) f.x, y = (int) f.y;
                if (f.size == 3) {
                    ui.fill(x - 1, y, 3, 1, Theme.withAlpha(0xFFFFFF, a));
                    ui.fill(x, y - 1, 1, 3, Theme.withAlpha(0xFFFFFF, a));
                } else {
                    ui.fill(x, y, f.size, f.size, Theme.withAlpha(0xFFFFFF, a));
                }
            }
        }
    }

    private void peaks(Ui ui, int width, int height, double base, double amp, double freq, double phase, int body, int cap, double capLine) {
        int baseY = (int) (height * base);
        int[] tops = new int[width / 2 + 2];
        for (int i = 0; i < tops.length; i++) {
            double x = i * 2;
            double v = Math.sin(x * freq + phase) * 0.55 + Math.sin(x * freq * 2.3 + phase * 1.7) * 0.3 + Math.sin(x * freq * 5.1) * 0.15;
            double ridge = Math.abs(v); // pointed peaks
            tops[i] = baseY - (int) (ridge * height * amp);
        }
        for (int i = 0; i < tops.length; i++) {
            int x = i * 2;
            int top = tops[i];
            ui.fill(x, top, 2, height - top, body);
            int peakHeight = baseY - top;
            int snow = (int) (peakHeight * (1 - capLine) * 1.6);
            if (peakHeight > height * amp * 0.45) ui.fill(x, top, 2, Math.min(snow, 14), cap);
        }
    }
}
