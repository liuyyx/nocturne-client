package dev.nocturne.ui.gl;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.module.Module;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.RenderCapabilities;

/** 真实帧捕获、4× 降采样与历史帧混合（InventoryBlur / Motion Blur 的整帧通道）。 */
public final class FrameEffects {
    private int texture;
    private int[] current;
    private int[] history;
    private int[] small;
    private int width;
    private int height;
    /** 极暗帧阈值（0–255 平均亮度）：低于它视为回读失败，直接跳过整帧后处理。 */
    static final int TOO_DARK_LUMA = 6;
    /** 上一次绘制时所在的世界；用于切换维度/世界时作废历史帧。 */
    private Object lastWorld;
    /** 固定管线跳过整帧后处理的提示是否已打印（只打一次）。 */
    private final java.util.concurrent.atomic.AtomicBoolean fixedPipelineNotice =
            new java.util.concurrent.atomic.AtomicBoolean();

    public void render(OverlayDraw draw, NocturneClient client) {
        Module inventory = client == null ? null : client.modules().get("InventoryBlur");
        Module motion = client == null ? null : client.modules().get("Motion Blur");
        boolean inventoryActive = inventory != null && inventory.isEnabled()
                && client.gameBridge() != null && client.gameBridge().containerOpen();
        // 第三人称开关随 MotionBlur 模块删除，这里只按第一人称混合。
        boolean motionActive = motion != null && motion.isEnabled()
                && client.gameBridge() != null && client.gameBridge().perspective() == 0;
        if (!inventoryActive && !motionActive) {
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
        int smallW = Math.max(1, w / 4);
        int smallH = Math.max(1, h / 4);
        int required = smallW * smallH;
        if (small == null || small.length != required) {
            small = new int[required];
        }
        boolean blend = motionActive && history != null;
        downsample(current, blend ? history : null, w, h, small, smallW, smallH, 0.65f);
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
