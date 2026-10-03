package dev.noturne.ui.theme;

import dev.noturne.ui.render.Color;

/**
 * The visual language: a dark translucent surface with a single saturated accent, small type and
 * generous corner radii — the "clean modern client" look.
 */
public final class Theme {

    // Surfaces (AARRGGBB)
    public static final Color SHADOW = Color.hex("66000000");
    public static final Color BACKGROUND = Color.hex("F0121216");
    public static final Color PANEL = Color.hex("F01A1A20");
    public static final Color PANEL_HEADER = Color.hex("FF232330");
    public static final Color PANEL_HEADER_HOVER = Color.hex("FF2B2B3A");
    public static final Color BORDER = Color.hex("33FFFFFF");
    public static final Color SEPARATOR = Color.hex("1AFFFFFF");

    // Content
    public static final Color TEXT = Color.hex("FFE9E9F2");
    public static final Color TEXT_DIM = Color.hex("99E9E9F2");
    public static final Color TEXT_MUTED = Color.hex("66E9E9F2");

    // Accent
    public static final Color ACCENT = Color.hex("FF7A5CFF");
    public static final Color ACCENT_HOVER = Color.hex("FF8E75FF");
    public static final Color ACCENT_SOFT = Color.hex("337A5CFF");
    public static final Color ENABLED = Color.hex("FF6EDBA0");
    public static final Color DISABLED = Color.hex("FF565666");
    public static final Color DANGER = Color.hex("FFE0645C");

    // Metrics
    public static final float RADIUS = 6f;
    public static final float RADIUS_SMALL = 3f;
    public static final float PADDING = 8f;
    public static final float GAP = 4f;
    public static final float HEADER_HEIGHT = 22f;
    public static final float ROW_HEIGHT = 18f;
    public static final float PANEL_WIDTH = 150f;

    // Type
    public static final float FONT_SIZE = 14f;
    public static final float FONT_SIZE_SMALL = 12f;

    // Motion
    public static final long HOVER_MS = 120L;
    public static final long EXPAND_MS = 180L;

    private Theme() {
    }
}
