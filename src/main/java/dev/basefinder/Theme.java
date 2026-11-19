package dev.basefinder;

/** Winter night palette (ARGB). */
final class Theme {
    private Theme() {}

    static final int NIGHT_TOP = 0xFF040A1C;
    static final int NIGHT_MID = 0xFF0B1B3A;
    static final int NIGHT_LOW = 0xFF1A3B6B;
    static final int HORIZON = 0xFF2E5C8E;

    static final int GLASS = 0xE2102244;
    static final int GLASS_HI = 0xC81A3260;
    static final int GLASS_BORDER = 0x66BFE3FF;
    static final int SHADOW = 0x55000000;

    static final int TEXT = 0xFFEAF4FF;
    static final int TEXT2 = 0xFFA8C2E0;
    static final int MUTED = 0xFF7088A8;
    static final int SNOW = 0xFFFFFFFF;
    static final int ICE = 0xFF8FD8FF;
    static final int CYAN = 0xFF5BD6FF;
    static final int SILVER = 0xFFC9D6E6;

    static final int INTACT = 0xFF5EEAB4;
    static final int SUSPICIOUS = 0xFFFBBF24;
    static final int GRIEFED = 0xFFEF4444;
    static final int PLAYER = 0xFFFF7A3D;
    static final int PEARL = 0xFFB07CFF;
    static final int DEATH = 0xFF8FA3BF;
    static final int REJECTED = 0xFFA6B4CC;
    static final int OK = 0xFF4ADE9C;
    static final int ERR = 0xFFFF6B6B;

    static int classColor(String cls) {
        if (cls == null) return REJECTED;
        return switch (cls) {
            case "INTACT" -> INTACT;
            case "SUSPICIOUS" -> SUSPICIOUS;
            case "GRIEFED" -> GRIEFED;
            case "REJECTED" -> REJECTED;
            default -> SILVER;
        };
    }

    static int channelColor(Channel c) {
        return switch (c) {
            case INTACT -> INTACT;
            case SUSPICIOUS -> SUSPICIOUS;
            case GRIEFED -> GRIEFED;
            case PLAYER -> PLAYER;
            case PEARL -> PEARL;
            case DEATH -> DEATH;
            case FALLBACK -> SILVER;
        };
    }

    static int eventColor(String type) {
        return switch (type) {
            case "PLAYER" -> PLAYER;
            case "PEARL" -> PEARL;
            case "DEATH" -> DEATH;
            case "BASE_INTACT" -> INTACT;
            case "BASE_SUSPICIOUS", "RECLASSIFIED" -> SUSPICIOUS;
            case "BASE_GRIEFED" -> GRIEFED;
            default -> REJECTED;
        };
    }

    static int withAlpha(int argb, int alpha) {
        return (alpha << 24) | (argb & 0xFFFFFF);
    }

    static int mix(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int aa = (a >>> 24) & 255, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = (b >>> 24) & 255, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return ((int) (aa + (ba - aa) * t) << 24) | ((int) (ar + (br - ar) * t) << 16)
                | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }
}
