package dev.nocturne.ui.gl;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.module.Module;
import dev.nocturne.client.module.modules.render.AntiAliasModule;
import dev.nocturne.client.module.modules.render.CustomSkyModule;
import dev.nocturne.client.module.modules.render.FilterModule;
import dev.nocturne.client.module.modules.render.MotionBlurModule;
import dev.nocturne.client.module.modules.render.ShadersModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.RenderCapabilities;

/** 真实帧捕获、Epsilon 滤镜/抗锯齿/天空/Shader、4× 降采样与历史帧混合。 */
public final class FrameEffects {
    private int texture;
    private int[] current;
    private int[] history;
    private int[] small;
    private int width;
    private int height;
    private long lastFrame;
    private float hotbarX = -1.0f;
    private int lastSlot = -1;
    private long inventoryOpenedAt;

    public void render(OverlayDraw draw, NocturneClient client) {
        Module inventory = client == null ? null : client.modules().get("InventoryBlur");
        Module motion = client == null ? null : client.modules().get("Motion Blur");
        Module antiAlias = client == null ? null : client.modules().get("Anti Alias");
        Module filter = client == null ? null : client.modules().get("Filter");
        Module sky = client == null ? null : client.modules().get("CustomSky");
        Module shaders = client == null ? null : client.modules().get("Shaders");
        boolean inventoryActive = inventory != null && inventory.isEnabled()
                && client.gameBridge() != null && client.gameBridge().containerOpen();
        boolean motionActive = motion != null && motion.isEnabled() && motion instanceof MotionBlurModule
                && (((MotionBlurModule) motion).thirdPerson() || client.gameBridge().perspective() == 0);
        boolean postActive = antiAlias != null && antiAlias.isEnabled() || filter != null && filter.isEnabled()
                || sky != null && sky.isEnabled() || shaders != null && shaders.isEnabled();
        if (!inventoryActive && !motionActive && !postActive) {
            history = null;
            return;
        }
        RenderCapabilities capabilities = client.renderCapabilities();
        if (capabilities == null || !draw.supportsTextures()) {
            return;
        }
        int[] size = capabilities.frameSize();
        if (size == null || size.length < 4 || size[2] <= 0 || size[3] <= 0) {
            return;
        }
        int w = size[2];
        int h = size[3];
        if (w != width || h != height || current == null) {
            width = w;
            height = h;
            current = new int[w * h];
            history = null;
        }
        if (!capabilities.readPixels(w, h, current)) {
            return;
        }
        applyPost(current, w, h, client, antiAlias, filter, sky, shaders);
        int smallW = Math.max(1, w / 4);
        int smallH = Math.max(1, h / 4);
        int required = smallW * smallH;
        if (small == null || small.length != required) {
            small = new int[required];
        }
        boolean blend = motionActive && history != null;
        float historyWeight = motionActive ? motionWeight((MotionBlurModule) motion) : 0.65f;
        downsample(current, blend ? history : null, w, h, small, smallW, smallH, historyWeight);
        texture = draw.uploadTexture(texture, smallW, smallH, small);
        if (texture > 0) {
            draw.texturedQuad(texture, 0, 0, draw.width(), draw.height(),
                    0, 1, 1, 0, inventoryActive ? 0x90FFFFFF : 0xA0FFFFFF);
        }
        if (motionActive) {
            if (history == null || history.length != current.length) {
                history = new int[current.length];
            }
            System.arraycopy(current, 0, history, 0, current.length);
        } else {
            history = null;
        }
        renderAnimations(draw, client);
    }

    private void renderAnimations(OverlayDraw draw, NocturneClient client) {
        Module module = client.modules().get("Game Animation");
        if (!(module instanceof dev.nocturne.client.module.modules.render.GameAnimationModule)
                || !module.isEnabled() || client.actions() == null) {
            hotbarX = -1.0f;
            return;
        }
        dev.nocturne.client.module.modules.render.GameAnimationModule animation =
                (dev.nocturne.client.module.modules.render.GameAnimationModule) module;
        if (animation.hotbar()) {
            int slot = client.actions().readState().selectedSlot();
            float target = draw.width() * 0.5f - 90.0f + slot * 20.0f;
            if (lastSlot != slot) {
                if (hotbarX < 0.0f || Math.abs(target - hotbarX) > 240.0f) {
                    hotbarX = target;
                }
                lastSlot = slot;
            }
            hotbarX += (target - hotbarX) * 0.35f;
            draw.outline(hotbarX, draw.height() - 23.0f, 20.0f, 2.0f, 1.0f, 0xFFFFB7C5);
        }
        if (animation.inventory() && client.gameBridge().containerOpen()) {
            if (inventoryOpenedAt == 0L) {
                inventoryOpenedAt = System.currentTimeMillis();
            }
            float progress = Math.min(1.0f, (System.currentTimeMillis() - inventoryOpenedAt) / 180.0f);
            float scale = 0.85f + 0.15f * (1.0f - (float) Math.pow(1.0f - progress, 3));
            float width = draw.width() * scale;
            float height = draw.height() * scale;
            draw.outline((draw.width() - width) * 0.5f, (draw.height() - height) * 0.5f,
                    width, height, 1.0f, 0x80FFFFFF);
        } else {
            inventoryOpenedAt = 0L;
        }
    }

    private float motionWeight(MotionBlurModule module) {
        float weight = module.strength();
        if (module.centered()) {
            weight *= 0.5f;
        }
        if (module.depthBlur()) {
            weight *= 0.95f;
        }
        weight += module.samples() / 10000.0f;
        if (module.refreshRateScaling()) {
            long now = System.currentTimeMillis();
            long delta = lastFrame == 0L ? 16L : now - lastFrame;
            lastFrame = now;
            weight *= Math.max(0.5f, Math.min(1.5f, delta / 16.0f));
        }
        return Math.max(0.0f, Math.min(0.95f, weight));
    }

    private static void applyPost(int[] pixels, int width, int height, NocturneClient client,
                                  Module antiAlias, Module filter, Module sky, Module shaders) {
        if (antiAlias instanceof AntiAliasModule && antiAlias.isEnabled()) {
            fxaa(pixels, width, height);
        }
        if (filter instanceof FilterModule && filter.isEnabled()) {
            FilterModule module = (FilterModule) filter;
            tint(pixels, module.color(), module.mode().equals("LightMap") ? 0.65f : 0.35f);
        }
        if (sky instanceof CustomSkyModule && sky.isEnabled()) {
            CustomSkyModule module = (CustomSkyModule) sky;
            float phase = (float) (System.currentTimeMillis() / 1000.0 * module.speed());
            for (int y = 0; y < height / 2; y++) {
                float amount = y / (float) Math.max(1, height / 2);
                int color = module.shader().equals("Local")
                        ? blend(module.background(), module.color(), amount)
                        : blend(module.color(), 0xFF87CEEB, (float) (0.5 + 0.5 * Math.sin(phase + amount * 4.0)));
                tintRow(pixels, width, y, color, 0.18f);
            }
        }
        if (shaders instanceof ShadersModule && shaders.isEnabled()
                && ((ShadersModule) shaders).targetEnabled()) {
            shader(pixels, width, height, (ShadersModule) shaders);
        }
    }

    private static void fxaa(int[] pixels, int width, int height) {
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int at = y * width + x;
                int color = pixels[at];
                int horizontal = Math.abs((pixels[at - 1] & 255) - (pixels[at + 1] & 255));
                int vertical = Math.abs((pixels[at - width] & 255) - (pixels[at + width] & 255));
                if (horizontal + vertical > 48) {
                    pixels[at] = blend(color, pixels[at - 1], 0.125f);
                    pixels[at] = blend(pixels[at], pixels[at + 1], 0.125f);
                    pixels[at] = blend(pixels[at], pixels[at - width], 0.125f);
                    pixels[at] = blend(pixels[at], pixels[at + width], 0.125f);
                }
            }
        }
    }

    private static void shader(int[] pixels, int width, int height, ShadersModule module) {
        String mode = module.entityMode();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int at = y * width + x;
                int color = module.glowGradient()
                        ? blend(module.primaryColor(), module.secondaryColor(), y / (float) height)
                        : module.primaryColor();
                float strength = module.glow() ? Math.min(0.5f, module.glowRadius() * module.exposure() / 100.0f)
                        : 0.12f;
                if (mode.equals("Snow")) {
                    boolean snow = (x * 31 + y * 17 + (int) (System.currentTimeMillis() / 100L)) % 997 == 0;
                    if (!snow) continue;
                    color = 0xFFFFFFFF;
                    strength = 0.8f;
                } else if (mode.equals("Smoke")) {
                    strength *= 0.5 + 0.5 * Math.sin(x * 0.03 + y * 0.02
                            + System.currentTimeMillis() * 0.001);
                } else if (mode.equals("Fade")) {
                    strength *= 1.0f - y / (float) height;
                }
                pixels[at] = blend(pixels[at], color, strength);
            }
        }
    }

    private static void tint(int[] pixels, int color, float amount) {
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = blend(pixels[i], color, amount * (color >>> 24) / 255.0f);
        }
    }

    private static void tintRow(int[] pixels, int width, int y, int color, float amount) {
        int start = y * width;
        for (int x = 0; x < width; x++) {
            pixels[start + x] = blend(pixels[start + x], color, amount);
        }
    }

    /** CPU 区域平均；有历史帧时按配置的历史权重混合。 */
    static void downsample(int[] source, int[] previous, int width, int height,
                           int[] out, int outWidth, int outHeight, float historyWeight) {
        for (int y = 0; y < outHeight; y++) {
            for (int x = 0; x < outWidth; x++) {
                int r = 0, g = 0, b = 0, a = 0, count = 0;
                for (int dy = 0; dy < 4; dy++) {
                    int sy = y * 4 + dy;
                    if (sy >= height) continue;
                    for (int dx = 0; dx < 4; dx++) {
                        int sx = x * 4 + dx;
                        if (sx >= width) continue;
                        int color = source[sy * width + sx];
                        if (previous != null) {
                            color = blend(color, previous[sy * width + sx], historyWeight);
                        }
                        r += color >>> 16 & 255;
                        g += color >>> 8 & 255;
                        b += color & 255;
                        a += color >>> 24 & 255;
                        count++;
                    }
                }
                if (count > 0) {
                    out[y * outWidth + x] = (a / count << 24) | (r / count << 16)
                            | (g / count << 8) | b / count;
                }
            }
        }
    }

    private static int blend(int current, int previous, float previousWeight) {
        float currentWeight = 1.0f - previousWeight;
        int a = (int) ((current >>> 24 & 255) * currentWeight + (previous >>> 24 & 255) * previousWeight);
        int r = (int) ((current >>> 16 & 255) * currentWeight + (previous >>> 16 & 255) * previousWeight);
        int g = (int) ((current >>> 8 & 255) * currentWeight + (previous >>> 8 & 255) * previousWeight);
        int b = (int) ((current & 255) * currentWeight + (previous & 255) * previousWeight);
        return a << 24 | r << 16 | g << 8 | b;
    }
}
