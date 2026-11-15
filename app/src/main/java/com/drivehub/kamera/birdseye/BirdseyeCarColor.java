package com.drivehub.kamera.birdseye;

/** Stable preference/asset identifiers. Swatches approximate paint appearance on a display. */
public enum BirdseyeCarColor {
    DOVER_WHITE("dover_white", "Dover White / Arctic White", "WSB / LIY", 0xFFE8E9E5),
    ANDES_GREY("andes_grey", "Andes Grey / Cool Gray", "5ZBY / PAG", 0xFF747A80),
    PEBBLE_BLACK("pebble_black", "Pebble Black / Black Knight", "PBC", 0xFF151719),
    DIAMOND_RED("diamond_red", "Diamond Red / Topspeed Red", "5ZAU / RSJ", 0xFFBA132B),
    FIZZY_ORANGE("fizzy_orange", "Fizzy Orange / Bubble Orange", "NFZ / ZBP", 0xFFF36F14),
    PICCADILLY_BLUE("piccadilly_blue", "Piccadilly Blue / Brighton Blue", "JAY / 5JAY", 0xFF008BD8),
    COSMIC_SILVER("cosmic_silver", "Cosmic Silver / Medal Silver", "MBC / SSA", 0xFFB8BDC2),
    HUNTER_GREEN("hunter_green", "Hunter Green", "5ZCK / ZCK / GWH;ZCK", 0xFF4C604D);

    public final String id, displayName, codes;
    public final int swatch;

    BirdseyeCarColor(String id, String displayName, String codes, int swatch) {
        this.id = id; this.displayName = displayName; this.codes = codes; this.swatch = swatch;
    }

    public String label() { return displayName + " · " + codes; }

    public static BirdseyeCarColor fromId(String id) {
        for (BirdseyeCarColor color : values()) if (color.id.equals(id)) return color;
        return PICCADILLY_BLUE;
    }
}
