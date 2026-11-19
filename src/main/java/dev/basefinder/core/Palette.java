package dev.basefinder.core;

/** Colours (0xRRGGBB) for each block category, shared by the PNG reconstructions and the in-game map view. */
public final class Palette {
    private Palette() {}

    public static int rgb(Cat c) {
        return switch (c) {
            case UNKNOWN -> 0x1B2433;
            case AIR -> 0xE8F1FA;
            case PLANT -> 0x4E8F4E;
            case DECOR -> 0xCDB58A;
            case LIGHT -> 0xFFE066;
            case CROP -> 0x59C25A;
            case WATER -> 0x3E7BD6;
            case LAVA -> 0xFF7A18;
            case FIRE -> 0xFF4500;
            case DOOR -> 0xB5651D;
            case PORTAL -> 0x9B59FF;
            case NATURAL -> 0x6F7A82;
            case BUILT -> 0xA9B7C9;
            case GLASS -> 0x9ADCF0;
            case FARMLAND -> 0x6B4A2B;
            case CHEST -> 0xC17B2F;
            case BARREL -> 0xE0A030;
            case SHULKER -> 0xA05CC8;
            case INFRA -> 0xE5533D;
            case BED -> 0xD84A6A;
            case TNT -> 0xE23B3B;
            case OBSIDIAN -> 0x2B1B4A;
            case ANCHOR -> 0x7C3AED;
        };
    }

    public static String label(Cat c) {
        return switch (c) {
            case UNKNOWN -> "Unknown / unloaded";
            case AIR -> "Open space";
            case NATURAL -> "Natural terrain";
            case BUILT -> "Built wall/floor";
            case GLASS -> "Glass / bars";
            case DOOR -> "Door / gate";
            case CHEST -> "Chest";
            case BARREL -> "Barrel";
            case SHULKER -> "Shulker box";
            case INFRA -> "Furnace / workstation";
            case BED -> "Bed";
            case FARMLAND -> "Farmland";
            case CROP -> "Crops";
            case WATER -> "Water";
            case LAVA -> "Lava";
            default -> c.name();
        };
    }
}
