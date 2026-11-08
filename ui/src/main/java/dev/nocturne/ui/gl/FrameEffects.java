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
    /** 极暗帧阈值（0–255 平均亮度）：低于它视为回读失败，直接跳过整帧后处理。 */
    static final int TOO_DARK_LUMA = 6;
    /** 上一次绘制时所在的世界；用于切换维度/世界时作废历史帧。 */
    private Object lastWorld;
    private long inventoryOpenedAt;
    /** 固定管线跳过整帧后处理的提示是否已打印（只打一次）。 */
    private final java.util.concurrent.atomic.AtomicBoolean fixedPipelineNotice =
            new java.util.concurrent.atomic.AtomicBoolean();

    public void render(OverlayDraw draw, NocturneClient client) {
        // Game Animation 是独立于整帧后期的 HUD 动画：必须在任何 return 之前执行，
        // 否则没开后期模块时它永远不画（旧代码把它放在最后，被下面的门控误伤）。
        if (client != null) {
            renderAnimations(draw, client);
        }
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
        // 固定管线（1.8.9–1.12）不做整帧后处理：回读到的帧会再以 ~63% 不透明度盖回去，
        // 而该后端的整帧 alpha 混合实测不可靠（同帧叠加/退化成不透明）——开了任何一个后期模块
        // 就等于给整个世界盖一层黑幕。这里显式跳过，并清掉历史帧避免切回现代后端时串帧。
        if (!draw.reliableAlphaBlend()) {
            // 固定管线跳过整帧后处理，一次性说明原因（否则用户只会看到"开了模块没效果"）。
            if (fixedPipelineNotice.compareAndSet(false, true)) {
                System.out.println("[nocturne] frame effects: fixed-pipeline backend,"
                        + " full-frame post-processing is skipped on this version");
            }
            history = null;
            return;
        }
        // 切换维度/世界后历史帧属于上一个场景，必须作废（否则新世界第一帧会叠旧画面）。
        Object world = client.gameBridge() == null ? null : client.gameBridge().world();
        if (world != lastWorld) {
            lastWorld = world;
            history = null;
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
        // 回读失败的另一种形态是「读到了全黑帧」（缓冲已交换/上下文错位）：此时把黑帧盖回去
        // 就是整屏黑幕。抽样亮度判定为极暗时直接跳过上传与 blit，并作废历史帧。
        if (tooDark(current)) {
            history = null;
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

    /**
     * 极暗帧判定：抽 1/step 像素求平均亮度，低于 {@link #TOO_DARK_LUMA} 视为回读失败/空帧。
     *
     * <p>阈值刻意压得很低：真实夜景在 0–255 亮度下平均也有 10 以上，只有「什么都没读到」
     * 才会低于 6。宁可少应用一次后期，也不能把黑帧贴回屏幕。
     *
     * @param pixels ARGB 帧
     * @return true 表示帧极暗，调用方必须跳过上传与 blit
     */
    static boolean tooDark(int[] pixels) {
        if (pixels == null || pixels.length == 0) {
            return true;
        }
        int step = Math.max(1, pixels.length / 4096);
        long sum = 0L;
        int count = 0;
        for (int i = 0; i < pixels.length; i += step) {
            int color = pixels[i];
            sum += ((color >>> 16 & 255) + (color >>> 8 & 255) + (color & 255)) / 3;
            count++;
        }
        return count == 0 || sum / count < TOO_DARK_LUMA;
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
