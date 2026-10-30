import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.Module;
import dev.nocturne.client.module.ModuleRegistry;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.BoundsNumberValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.GroupValue;
import dev.nocturne.client.value.KeyValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.MultiBoolValue;
import dev.nocturne.client.value.NumberBounds;
import dev.nocturne.client.value.NumberValue;
import dev.nocturne.client.value.SlotValue;
import dev.nocturne.client.value.TextValue;
import dev.nocturne.ui.fatality.FaClickGui;
import dev.nocturne.ui.render.Color;
import dev.nocturne.ui.render.Renderer;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * 离屏验证移植后的 Fatality 界面：把真实的 {@link FaClickGui} 画到一个 Java2D 光栅表面上并写成 PNG。
 *
 * <p>为什么需要它：界面在游戏里要靠 GL/extractor 后端才看得见，而那些后端需要真机 + 真人操作。
 * 本 Lab 用同一份 {@code FaClickGui} 代码（以及同一份字形图集、阴影/角弧纹理生成、瀑布流布局）
 * 画到光栅面上，因此「布局、字体、渐变、阴影、描边、层级」这些界面的实质都能在 CI/桌面上复现与查看，
 * 只有「后端把原语提交到 GL」这一段不在覆盖范围内。
 *
 * <p>用法：{@code java -Djava.awt.headless=true -cp <dist jar> LabFatalityGui <out.png>}
 */
public final class LabFatalityGui {

    /** 画布尺寸：与 Fatality 参考实现的 960×540 设计画布同一代，缩放系数会算成 1。 */
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 800;

    /**
     * 基于 Java2D 的 {@link Renderer} 实现。
     *
     * <p>纹理支路是真的：{@code uploadTexture} 保存像素、{@code texturedQuad} 把纹理按调制色
     * 画出来（逐像素乘色），因此字形图集与程序化阴影/角弧都会真实出现在结果里。
     */
    private static final class RasterRenderer implements Renderer {

        /** 目标表面。 */
        private final BufferedImage surface;
        /** Java2D 上下文。 */
        private final Graphics2D graphics;
        /** 纹理句柄 → 像素。 */
        private final Map<Integer, BufferedImage> textures = new HashMap<Integer, BufferedImage>();
        /** 下一个纹理句柄。 */
        private int nextTexture = 1;
        /** 裁剪栈（保存/恢复 clip）。 */
        private final Deque<java.awt.Shape> clips = new ArrayDeque<java.awt.Shape>();

        RasterRenderer(int width, int height) {
            surface = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            graphics = surface.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        }

        BufferedImage surface() {
            return surface;
        }

        @Override
        public void rect(float x, float y, float width, float height, Color color) {
            if (width <= 0f || height <= 0f || color == null) {
                return;
            }
            graphics.setColor(new java.awt.Color(color.argb, true));
            graphics.fill(new java.awt.geom.Rectangle2D.Float(x, y, width, height));
        }

        @Override
        public void roundedRect(float x, float y, float width, float height, float radius, Color color) {
            if (width <= 0f || height <= 0f || color == null) {
                return;
            }
            float r = Math.min(radius, Math.min(width, height) / 2f);
            graphics.setColor(new java.awt.Color(color.argb, true));
            if (r <= 0.5f) {
                graphics.fill(new java.awt.geom.Rectangle2D.Float(x, y, width, height));
                return;
            }
            graphics.fill(new RoundRectangle2D.Float(x, y, width, height, r * 2f, r * 2f));
        }

        @Override
        public void outline(float x, float y, float width, float height, float lineWidth, Color color) {
            if (width <= 0f || height <= 0f || color == null) {
                return;
            }
            graphics.setColor(new java.awt.Color(color.argb, true));
            graphics.setStroke(new BasicStroke(Math.max(0.5f, lineWidth)));
            graphics.draw(new java.awt.geom.Rectangle2D.Float(x, y, width, height));
        }

        @Override
        public void text(String text, float x, float y, float size, Color color) {
            if (text == null || text.isEmpty() || color == null) {
                return;
            }
            graphics.setColor(new java.awt.Color(color.argb, true));
            graphics.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN,
                    Math.max(1, Math.round(size))));
            graphics.drawString(text, x, y + size * 0.8f);
        }

        @Override
        public float textWidth(String text, float size) {
            if (text == null || text.isEmpty()) {
                return 0f;
            }
            graphics.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN,
                    Math.max(1, Math.round(size))));
            return graphics.getFontMetrics().stringWidth(text);
        }

        @Override
        public float textHeight(float size) {
            return size;
        }

        /** 无裁剪时的哨兵：{@code Graphics2D.getClip()} 在未设置裁剪时返回 {@code null}，而栈不能存 null。 */
        private static final java.awt.Shape FULL_CLIP =
                new java.awt.geom.Rectangle2D.Float(-1e5f, -1e5f, 2e5f, 2e5f);

        @Override
        public void pushClip(float x, float y, float width, float height) {
            java.awt.Shape current = graphics.getClip();
            clips.push(current == null ? FULL_CLIP : current);
            java.awt.Shape rect = new java.awt.geom.Rectangle2D.Float(x, y, Math.max(0f, width),
                    Math.max(0f, height));
            graphics.setClip(current == null ? rect : current.getBounds2D().createIntersection(
                    rect.getBounds2D()));
        }

        @Override
        public void popClip() {
            if (clips.isEmpty()) {
                return;
            }
            java.awt.Shape previous = clips.pop();
            graphics.setClip(previous == FULL_CLIP ? null : previous);
        }

        @Override
        public boolean supportsTextures() {
            return true;
        }

        @Override
        public int uploadTexture(int id, int width, int height, int[] argb) {
            if (width <= 0 || height <= 0 || argb == null) {
                return id;
            }
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0, 0, width, height, argb, 0, width);
            int handle = id > 0 ? id : nextTexture++;
            textures.put(handle, image);
            return handle;
        }

        @Override
        public void deleteTexture(int id) {
            textures.remove(id);
        }

        @Override
        public void texturedQuad(int texture, float x, float y, float width, float height,
                                 float u0, float v0, float u1, float v1, int argb) {
            BufferedImage image = textures.get(texture);
            if (image == null || width <= 0f || height <= 0f) {
                return;
            }
            int sx = Math.round(u0 * image.getWidth());
            int sy = Math.round(v0 * image.getHeight());
            int sw = Math.max(1, Math.round((u1 - u0) * image.getWidth()));
            int sh = Math.max(1, Math.round((v1 - v0) * image.getHeight()));
            if (sx + sw > image.getWidth() || sy + sh > image.getHeight()) {
                return;
            }
            BufferedImage tinted = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_ARGB);
            tinted.setRGB(0, 0, sw, sh, image.getRGB(sx, sy, sw, sh, null, 0, sw), 0, sw);
            for (int py = 0; py < sh; py++) {
                for (int px = 0; px < sw; px++) {
                    int src = tinted.getRGB(px, py);
                    if ((src >>> 24) == 0) {
                        continue;
                    }
                    int r = ((src >> 16) & 0xFF) * ((argb >> 16) & 0xFF) / 255;
                    int g = ((src >> 8) & 0xFF) * ((argb >> 8) & 0xFF) / 255;
                    int b = (src & 0xFF) * (argb & 0xFF) / 255;
                    int a = ((src >>> 24) & 0xFF) * ((argb >>> 24) & 0xFF) / 255;
                    tinted.setRGB(px, py, a << 24 | r << 16 | g << 8 | b);
                }
            }
            graphics.setComposite(AlphaComposite.SrcOver);
            graphics.drawImage(tinted, Math.round(x), Math.round(y), Math.round(width), Math.round(height),
                    null);
        }
    }

    /** 造一批带真实设置项的模块，让卡片里出现每类控件。 */
    private static void registerModules(ModuleRegistry registry) {
        registry.register(controlsModule());
        registry.register(module("FullBright", Category.RENDER, true));
        registry.register(module("ESP", Category.RENDER, true));
        registry.register(module("NameTags", Category.RENDER, false));
        registry.register(module("Tracers", Category.RENDER, false));
        registry.register(module("Sprint", Category.MOVEMENT, false));
        registry.register(module("AutoRespawn", Category.PLAYER, false));
        registry.register(module("Panic", Category.MISC, false));
    }

    /** 扩展控件验收卡：六类新设置与展开的子设置同时出现。 */
    private static Module controlsModule() {
        return new Module() {
            final TextValue text = add(new TextValue("Text", "Nocturne", 24));
            final BoundsNumberValue bounds = add(new BoundsNumberValue("Bounds",
                    new NumberBounds(2.0, 8.0), 0.0, 12.0, 0.5));
            final SlotValue slot = add(new SlotValue("Slot", 2));
            final KeyValue key = add(new KeyValue("Key", java.awt.event.KeyEvent.VK_R));
            final MultiBoolValue multi = add(new MultiBoolValue("Targets",
                    java.util.Arrays.asList("Players", "Mobs", "Items"),
                    java.util.Arrays.asList("Players", "Mobs")));
            final GroupValue group = add(new GroupValue("Advanced",
                    java.util.Collections.singletonList(new BooleanValue("Nested", true)), true));

            @Override
            public String name() {
                return "Controls";
            }

            @Override
            public Category category() {
                return Category.RENDER;
            }
        };
    }

    /** 造一个模块：带布尔、数值、枚举、颜色四类设置项。 */
    private static Module module(final String name, final Category category, final boolean enabled) {
        Module module = new Module() {
            final BooleanValue flag = add(new BooleanValue("Flag", true));
            final NumberValue range = add(new NumberValue("Range", 4.5, 0.0, 12.0, 0.5));
            final ModeValue mode = add(new ModeValue("Mode", "Solid", "Solid", "Outline", "Gradient"));
            final ColorValue tint = add(new ColorValue("Color", 0xFF3FA0FF));

            @Override
            public String name() {
                return name;
            }

            @Override
            public Category category() {
                return category;
            }
        };
        if (enabled) {
            module.toggle();
        }
        return module;
    }

    /** 入口：画若干帧让动画收敛，然后写 PNG。 */
    public static void main(String[] args) throws Exception {
        String out = args.length > 0 ? args[0] : "fatality-ui.png";
        String popupOut = args.length > 1 ? args[1] : null;
        // 清掉可能存在的布局文件：截图应当是默认布局，不受上次运行影响。
        Files.deleteIfExists(Paths.get(System.getProperty("user.dir", "."), "nocturne/clickgui.properties"));

        ModuleRegistry registry = new ModuleRegistry();
        registerModules(registry);

        RasterRenderer renderer = new RasterRenderer(WIDTH, HEIGHT);
        FaClickGui gui = new FaClickGui(registry);
        gui.setOpen(true);
        gui.setViewport(WIDTH, HEIGHT);

        // 指针停在第一个模块卡片上，让悬停高亮也进入截图。
        double mouseX = WIDTH * 0.5;
        double mouseY = HEIGHT * 0.35;
        long now = System.currentTimeMillis();
        settle(gui, renderer, now, mouseX, mouseY);
        BufferedImage surface = renderer.surface();
        write(out, surface, gui);

        if (popupOut != null) {
            // 点一下第一张卡片的 Mode 下拉：验证浮层、列表渲染与"点在浮层内"的输入派发。
            // 坐标由主题常量推出（窗口 744×540 居中 → wx=268、wy=130；内容区从 wx+98 起）。
            float wx = (WIDTH - 744.0f) * 0.5f;
            float wy = (HEIGHT - 540.0f) * 0.5f;
            float columnW = (744.0f - 98.0f - 10.0f - 13.5f * 2) / 3.0f;
            float cardX = wx + 98.0f + 10.0f;
            float rowCenter = wy + 40.0f + 10.0f + 15.0f + 3.5f * 25.5f;
            double comboX = cardX + columnW - 15.0f - 36.0f;
            gui.mouseClicked(comboX, rowCenter, 0);
            gui.mouseReleased(comboX, rowCenter, 0);
            settle(gui, renderer, now + 2000L, comboX, rowCenter);
            write(popupOut, renderer.surface(), gui);
        }
    }

    /** 推进若干帧让动画收敛（每帧 16ms，约 1 秒）。 */
    private static void settle(FaClickGui gui, RasterRenderer renderer, long start, double mx, double my) {
        for (int frame = 0; frame < 60; frame++) {
            gui.update(start + frame * 16L, mx, my);
            gui.render(renderer);
        }
    }

    /** 写一张 PNG 并打印判定行。 */
    private static void write(String out, BufferedImage surface, FaClickGui gui) throws Exception {
        File file = new File(out);
        ImageIO.write(surface, "png", file);
        int distinct = countDistinctColors(surface);
        System.out.println("LAB distinctColors=" + distinct + " wrote=" + file.getAbsolutePath());
        System.out.println("LAB " + (distinct > 40 ? "PASS" : "FAIL")
                + " (the window must show surfaces, text and accent colours, not a blank/black frame)");
    }

    /** @return 采样后的不同颜色数（>40 说明画面上确实有内容，而不是一片纯色） */
    private static int countDistinctColors(BufferedImage image) {
        java.util.Set<Integer> colors = new java.util.HashSet<Integer>();
        for (int y = 0; y < image.getHeight(); y += 3) {
            for (int x = 0; x < image.getWidth(); x += 3) {
                colors.add(image.getRGB(x, y));
            }
        }
        return colors.size();
    }
}
