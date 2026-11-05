package dev.nocturne.ui.gl;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.module.Module;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.RenderCapabilities;

/** 真实帧捕获、4× 降采样和历史帧混合。 */
public final class FrameEffects {
    private int texture;
    private int[] current;
    private int[] history;
    private int[] small;
    private int width;
    private int height;

    public void render(OverlayDraw draw, NocturneClient client) {
        Module inventory = client == null ? null : client.modules().get("InventoryBlur");
        Module motion = client == null ? null : client.modules().get("MotionBlur");
        boolean inventoryActive = inventory != null && inventory.isEnabled()
                && client.gameBridge() != null && client.gameBridge().containerOpen();
        boolean motionActive = motion != null && motion.isEnabled();
        if (!inventoryActive && !motionActive) {
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
        int smallW = Math.max(1, w / 4);
        int smallH = Math.max(1, h / 4);
        int required = smallW * smallH;
        if (small == null || small.length != required) {
            small = new int[required];
        }
        boolean blend = motionActive && history != null;
        float historyWeight = motionActive
                ? ((dev.nocturne.client.module.modules.render.MotionBlurModule) motion).strength() : 0.65f;
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

    /** CPU 区域平均；有历史帧时按配置的历史权重混合。 */
    static void downsample(int[] source, int[] previous, int width, int height,
                           int[] out, int outWidth, int outHeight, float historyWeight) {
        for (int y = 0; y < outHeight; y++) {
            for (int x = 0; x < outWidth; x++) {
                int r = 0;
                int g = 0;
                int b = 0;
                int a = 0;
                int count = 0;
                for (int dy = 0; dy < 4; dy++) {
                    int sy = y * 4 + dy;
                    if (sy >= height) continue;
                    for (int dx = 0; dx < 4; dx++) {
                        int sx = x * 4 + dx;
                        if (sx >= width) continue;
                        int color = source[sy * width + sx];
                        if (previous != null) {
                            int old = previous[sy * width + sx];
                            color = blend(color, old, historyWeight);
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

    private static int blend(int current, int previous, float historyWeight) {
        float currentWeight = 1.0f - historyWeight;
        int a = (int) ((current >>> 24 & 255) * currentWeight + (previous >>> 24 & 255) * historyWeight);
        int r = (int) ((current >>> 16 & 255) * currentWeight + (previous >>> 16 & 255) * historyWeight);
        int g = (int) ((current >>> 8 & 255) * currentWeight + (previous >>> 8 & 255) * historyWeight);
        int b = (int) ((current & 255) * currentWeight + (previous & 255) * historyWeight);
        return a << 24 | r << 16 | g << 8 | b;
    }
}
