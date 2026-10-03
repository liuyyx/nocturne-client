package dev.noturne.ui.render;

/**
 * Immutable 0xAARRGGBB colour.
 *
 * <p>Kept as a plain int on purpose: it converts cheaply to OpenGL vertex data and can be
 * compared with {@code ==}.
 */
public final class Color {

    public static final Color TRANSPARENT = new Color(0x00000000);
    public static final Color WHITE = new Color(0xFFFFFFFF);
    public static final Color BLACK = new Color(0xFF000000);

    public final int argb;

    private Color(int argb) {
        this.argb = argb;
    }

    public static Color of(int argb) {
        return new Color(argb);
    }

    public static Color rgb(int red, int green, int blue) {
        return argb(255, red, green, blue);
    }

    public static Color argb(int alpha, int red, int green, int blue) {
        return new Color(pack(alpha, red, green, blue));
    }

    /** Parses {@code RRGGBB} or {@code AARRGGBB}. */
    public static Color hex(String hex) {
        String value = hex.startsWith("#") ? hex.substring(1) : hex;
        if (value.length() == 6) {
            return new Color(0xFF000000 | (int) Long.parseLong(value, 16));
        }
        if (value.length() == 8) {
            return new Color((int) Long.parseLong(value, 16));
        }
        throw new IllegalArgumentException("expected RRGGBB or AARRGGBB: " + hex);
    }

    public int a() {
        return (argb >>> 24) & 0xFF;
    }

    public int r() {
        return (argb >>> 16) & 0xFF;
    }

    public int g() {
        return (argb >>> 8) & 0xFF;
    }

    public int b() {
        return argb & 0xFF;
    }

    public float af() {
        return a() / 255f;
    }

    public float rf() {
        return r() / 255f;
    }

    public float gf() {
        return g() / 255f;
    }

    public float bf() {
        return b() / 255f;
    }

    public Color withAlpha(int alpha) {
        return argb(alpha, r(), g(), b());
    }

    /** Linear blend: {@code t == 0} returns {@code this}, {@code t == 1} returns {@code other}. */
    public Color mix(Color other, float t) {
        float clamped = t < 0f ? 0f : (t > 1f ? 1f : t);
        return argb(
                Math.round(a() + (other.a() - a()) * clamped),
                Math.round(r() + (other.r() - r()) * clamped),
                Math.round(g() + (other.g() - g()) * clamped),
                Math.round(b() + (other.b() - b()) * clamped));
    }

    private static int pack(int alpha, int red, int green, int blue) {
        return (clamp(alpha) << 24) | (clamp(red) << 16) | (clamp(green) << 8) | clamp(blue);
    }

    private static int clamp(int channel) {
        return channel < 0 ? 0 : (channel > 255 ? 255 : channel);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Color && ((Color) other).argb == argb;
    }

    @Override
    public int hashCode() {
        return argb;
    }

    @Override
    public String toString() {
        return String.format("#%08X", argb);
    }
}
