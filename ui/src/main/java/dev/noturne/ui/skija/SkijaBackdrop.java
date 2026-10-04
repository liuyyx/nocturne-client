/*
 * 移植自 Setsuna（上游 commit e4915ae093748d48d92ee47c38cdb8b2746a4730，作者 ShiYi，
 * 许可 GPL-3.0-or-later，见 THIRD-PARTY-NOTICES.md）。本文件相对上游的改动：
 * 1. 包名 com.setsuna.ui.screen → dev.noturne.ui.skija；类名 ScreenBackdrop → SkijaBackdrop；
 *    类与需要跨包调用的成员由包内可见提升为 public（调用方在 dev.noturne.ui.screen 等包）。
 * 2. UiTheme.* → SkijaTheme.*（同包，方法签名一致）；去掉 com.setsuna.Setsuna，
 *    日志改为 System.out.println("[noturne] ...")。
 * 3. 去掉 net.minecraft.client.Minecraft：上游唯一用途是取 gameDirectory 拼出
 *    <gameDir>/.setsuna/ui 作为背景状态目录。此处改为可注入的静态状态 + setter
 *    （setBackgroundDirectory / setGridBackground / setCustomBackgroundPath），
 *    默认目录 <user.dir>/noturne/ui，替换约定见 docs/research/setsuna-gui-port.md。
 * 4. 资源路径 /assets/setsuna/textures/mainmenu/background.png
 *    → /assets/noturne/textures/mainmenu/background.png（文件已同步复制到 ui 模块资源目录）。
 * 5. Java 16+ 语法降级到 Java 8：record TraceLine → 静态 final 类（含访问器与
 *    equals/hashCode/toString）；InputStream.readAllBytes()（Java 9+）→ 自写 readAllBytes。
 * 6. 绘制与动画的数值、顺序、算法逐行保持与上游一致，未做任何“优化”。
 */
package dev.noturne.ui.skija;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.Rect;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;

/**
 * 独立客户端界面共用的全屏建筑风动效背板。
 *
 * <p>两种模式：默认画“透视面 + 网格 + 扫描线 + 边缘压暗”的程序化动画；主菜单可改用一张
 * 自定义背景图（按视口宽高比做中心裁剪后铺满），此时只叠加纯色压暗与边缘压暗。
 *
 * <p>上游把背景状态放在 {@code <gameDir>/.setsuna/ui}：一张 {@code menu-background.png}
 * 加一个 {@code use-grid-background} 空标记文件。本移植保留这套磁盘语义（导入/重置都写盘），
 * 只把「游戏目录」这一 MC 依赖改为可注入的静态状态：默认 {@code <user.dir>/noturne/ui}，
 * 配置层通过 {@link #setBackgroundDirectory(Path)} 覆盖；若配置层已经知道模式或图片路径，
 * 也可以直接用 {@link #setGridBackground(boolean)}、{@link #setCustomBackgroundPath(String)}
 * 注入，跳过磁盘探测（显式注入优先于磁盘标记）。
 *
 * <p>所有图片都在类初始化/注入时读入并常驻，绘制路径上不再有 IO，避免每帧卡顿。
 */
public final class SkijaBackdrop {

    private static final Paint GRADIENT_PAINT = new Paint().setAntiAlias(true).setDither(true);
    private static final Paint LINE_PAINT = new Paint().setAntiAlias(true).setStrokeWidth(1.0F);
    private static final Paint IMAGE_PAINT = new Paint().setAntiAlias(true).setDither(true);
    private static final Image MAIN_MENU_BACKGROUND = loadImage(
            "/assets/noturne/textures/mainmenu/background.png");
    private static final long MAX_IMPORT_BYTES = 32L * 1024L * 1024L;
    private static final long MAX_IMPORT_PIXELS = 40_000_000L;
    private static Image customMainMenuBackground;
    private static boolean backgroundStateLoaded;
    private static boolean gridBackground;
    /** 配置层显式注入过背景模式后置位：置位时 {@link #ensureBackgroundStateLoaded()} 不再读盘探测。 */
    private static boolean gridBackgroundInjected;
    /** 配置层注入的背景目录；null 表示用默认值（见 {@link #backgroundDirectory()}）。 */
    private static Path injectedDirectory;
    /** 配置层注入的自定义背景文件；null 表示用「目录内约定文件名」。 */
    private static Path customBackgroundOverride;

    private SkijaBackdrop() {
    }

    /** 配置层注入背景状态目录（代替上游的 {@code Minecraft.gameDirectory}）。传 null 回到默认目录。 */
    public static synchronized void setBackgroundDirectory(Path directory) {
        injectedDirectory = directory;
        // 目录变了，之前按旧目录读出的背景图与标记都失效；显式注入的模式/路径仍然优先，故保留。
        backgroundStateLoaded = false;
        replaceCustomBackground(null);
    }

    /**
     * 配置层直接注入背景模式，跳过磁盘标记探测。
     *
     * <p>调用方需先调 {@link #setBackgroundDirectory(Path)}（或在导入/重置前补调），
     * 否则导入与重置会写到默认目录。
     */
    public static synchronized void setGridBackground(boolean grid) {
        gridBackground = grid;
        gridBackgroundInjected = true;
        backgroundStateLoaded = false;
        // 立即释放自定义背景图：切到网格模式后不应再持有上一张图，切回时按需重新读盘。
        replaceCustomBackground(null);
    }

    /** 配置层直接注入自定义背景文件路径；传 null 或空串表示回到「目录内约定文件名」。 */
    public static synchronized void setCustomBackgroundPath(String path) {
        customBackgroundOverride = path == null || path.trim().isEmpty() ? null : Paths.get(path);
        if (customBackgroundOverride != null) {
            // 有自定义图就不再画网格——与上游 import 语义一致。
            gridBackground = false;
            gridBackgroundInjected = true;
        }
        backgroundStateLoaded = false;
        replaceCustomBackground(null);
    }

    public static void draw(Canvas canvas, float width, float height, int shadeAlpha) {
        draw(canvas, width, height, seconds(), width * 0.5F, height * 0.5F, shadeAlpha);
    }

    public static void drawMainMenu(Canvas canvas, float width, float height, int shadeAlpha) {
        drawMainMenu(canvas, width, height, seconds(), width * 0.5F, height * 0.5F,
                shadeAlpha);
    }

    public static void drawMainMenu(Canvas canvas, float width, float height, float time,
                                    float pointerX, float pointerY, int shadeAlpha) {
        ensureBackgroundStateLoaded();
        if (gridBackground) {
            // 网格模式固定用 18 的压暗：背景图模式才有 48/纯色两条分支。
            draw(canvas, width, height, time, pointerX, pointerY, 18);
            return;
        }
        Image background = mainMenuBackground();
        if (background == null || width <= 0.0F || height <= 0.0F) {
            draw(canvas, width, height, shadeAlpha);
            return;
        }

        // 按视口宽高比做中心裁剪：只改采样源矩形，目标矩形始终铺满视口，避免拉伸变形。
        float imageWidth = background.getWidth();
        float imageHeight = background.getHeight();
        float viewportAspect = width / height;
        float imageAspect = imageWidth / imageHeight;
        float sourceWidth = imageWidth;
        float sourceHeight = imageHeight;
        if (viewportAspect > imageAspect) {
            sourceHeight = imageWidth / viewportAspect;
        } else {
            sourceWidth = imageHeight * viewportAspect;
        }
        float sourceX = (imageWidth - sourceWidth) * 0.5F;
        float sourceY = (imageHeight - sourceHeight) * 0.5F;
        canvas.drawImageRect(background,
                Rect.makeXYWH(sourceX, sourceY, sourceWidth, sourceHeight),
                Rect.makeXYWH(0.0F, 0.0F, width, height),
                SamplingMode.MITCHELL, IMAGE_PAINT, true);
        if (shadeAlpha > 0) {
            canvas.drawColor(SkijaTheme.argb(Math.min(255, shadeAlpha), 3, 4, 7));
        }
        drawEdgeShade(canvas, width, height);
    }

    /** 是否允许“重置为网格背景”（即当前不是网格模式）。配置界面用它决定重置按钮的可用性。 */
    public static boolean canResetMainMenuBackground() {
        ensureBackgroundStateLoaded();
        return !gridBackground;
    }

    /**
     * 把用户选中的图片转成 PNG 落到背景目录并立即生效。
     *
     * <p>先解码并做体积/分辨率上限校验，再写临时文件、用 Skia 试读一次，全部成功后才原子替换——
     * 任一步失败都不破坏现有背景。
     */
    public static void importMainMenuBackground(Path source) throws IOException {
        if (source == null || !Files.isRegularFile(source)) {
            throw new IOException("The selected image does not exist.");
        }
        long byteCount = Files.size(source);
        if (byteCount <= 0L || byteCount > MAX_IMPORT_BYTES) {
            throw new IOException("The image must be smaller than 32 MB.");
        }

        BufferedImage decoded = decodeImport(source);
        Path target = customBackgroundPath();
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            if (!ImageIO.write(decoded, "png", temporary.toFile())) {
                throw new IOException("The image could not be converted to PNG.");
            }
            // 先试读，确保写出的 PNG Skia 能解码，失败时磁盘上的旧背景仍是完好的。
            Image replacement = loadImage(temporary);
            if (replacement == null) {
                throw new IOException("The converted image could not be loaded.");
            }
            try {
                Files.deleteIfExists(gridBackgroundMarker());
                moveReplacing(temporary, target);
            } catch (IOException exception) {
                replacement.close();
                throw exception;
            }
            replaceCustomBackground(replacement);
            gridBackground = false;
            backgroundStateLoaded = true;
        } finally {
            Files.deleteIfExists(temporary);
            decoded.flush();
        }
    }

    /** 重置为网格背景：写标记文件并删掉自定义图。 */
    public static void resetMainMenuBackground() throws IOException {
        Path marker = gridBackgroundMarker();
        Files.createDirectories(marker.getParent());
        Files.write(marker, new byte[0]);
        Files.deleteIfExists(customBackgroundPath());
        replaceCustomBackground(null);
        gridBackground = true;
        backgroundStateLoaded = true;
    }

    public static void draw(Canvas canvas, float width, float height, float time,
                            float pointerX, float pointerY, int shadeAlpha) {
        if (width <= 0.0F || height <= 0.0F) {
            return;
        }

        drawGradient(canvas, Rect.makeXYWH(0.0F, 0.0F, width, height),
                0.0F, 0.0F, width, height,
                new int[]{0xFF06070A, 0xFF10141A, 0xFF090A0E},
                new float[]{0.0F, 0.58F, 1.0F});

        // 视差：指针相对屏幕中心的偏移映射到 ±5px / ±4px，再交给透视面与网格共用。
        float parallaxX = clamp((pointerX / width - 0.5F) * 10.0F, -5.0F, 5.0F);
        float parallaxY = clamp((pointerY / height - 0.5F) * 8.0F, -4.0F, 4.0F);
        drawPerspectivePlane(canvas, width, height, time, parallaxX, parallaxY);
        drawGrid(canvas, width, height, time, parallaxX, parallaxY);
        drawScan(canvas, width, height, time);

        if (shadeAlpha > 0) {
            canvas.drawColor(SkijaTheme.argb(Math.min(255, shadeAlpha), 4, 6, 9));
        }
        drawEdgeShade(canvas, width, height);
    }

    /** 右侧倾斜的发光竖面：随时间的正弦脉冲改变中段透明度，制造“灯带呼吸”。 */
    private static void drawPerspectivePlane(Canvas canvas, float width, float height, float time,
                                             float parallaxX, float parallaxY) {
        int accent = SkijaTheme.accent();
        float pulse = 0.5F + 0.5F * (float) Math.sin(time * 0.55F);

        canvas.save();
        canvas.translate(width * 0.73F + parallaxX, height * 0.46F + parallaxY);
        canvas.rotate(-13.0F);
        // 面板太窄时至少留 120px 宽，保证竖面在小窗口里仍然可见。
        float planeWidth = Math.max(120.0F, width * 0.22F);
        float planeHeight = height * 2.1F;
        drawGradient(canvas, Rect.makeXYWH(-planeWidth * 0.5F, -planeHeight * 0.5F,
                        planeWidth, planeHeight),
                -planeWidth * 0.5F, 0.0F, planeWidth * 0.5F, 0.0F,
                new int[]{SkijaTheme.withAlpha(accent, 0), SkijaTheme.withAlpha(accent, 12 + Math.round(pulse * 9.0F)),
                        SkijaTheme.withAlpha(0xFFF1A45D, 8), SkijaTheme.withAlpha(accent, 0)},
                new float[]{0.0F, 0.28F, 0.72F, 1.0F});
        canvas.restore();

        // 与竖面同向的一条高光斜线，把透视感延伸到画面上缘。
        TraceLine trace = traceLine(width, height, parallaxX);
        LINE_PAINT.setColor(SkijaTheme.withAlpha(0xFFF1A45D, 38)).setStrokeWidth(1.0F);
        canvas.drawLine(trace.startX(), trace.startY(), trace.endX(), trace.endY(), LINE_PAINT);
    }

    /** 高光斜线的几何：只依赖视口与视差，独立出来供命中测试/装饰复用。 */
    public static TraceLine traceLine(float width, float height, float parallaxX) {
        float startX = width * 0.82F + parallaxX * 0.7F;
        return new TraceLine(startX, height * 0.12F,
                startX - height * 0.22F, height * 0.88F);
    }

    private static void drawGrid(Canvas canvas, float width, float height, float time,
                                 float parallaxX, float parallaxY) {
        // 格子边长随宽度自适应并夹在 34–58px：宽屏不至于稀疏，窄屏不至于糊成一片。
        float spacing = Math.max(34.0F, Math.min(58.0F, width / 12.0F));
        float offsetX = positiveModulo(time * 3.5F + parallaxX, spacing);
        float offsetY = positiveModulo(time * 2.0F + parallaxY, spacing);
        LINE_PAINT.setColor(0x0EFFFFFF).setStrokeWidth(1.0F);
        for (float x = -spacing + offsetX; x < width + spacing; x += spacing) {
            canvas.drawLine(x, 0.0F, x, height, LINE_PAINT);
        }
        for (float y = -spacing + offsetY; y < height + spacing; y += spacing) {
            canvas.drawLine(0.0F, y, width, y, LINE_PAINT);
        }

        // 左右各一条更亮的竖线，给画面加“取景框”的暗示。
        LINE_PAINT.setColor(0x18FFFFFF).setStrokeWidth(1.0F);
        canvas.drawLine(width * 0.08F, 0.0F, width * 0.08F, height, LINE_PAINT);
        canvas.drawLine(width * 0.92F, 0.0F, width * 0.92F, height, LINE_PAINT);
    }

    /** 自下而上循环移动的扫描光带（上下各比可见区多 60px，进出画面时不突兀）。 */
    private static void drawScan(Canvas canvas, float width, float height, float time) {
        float travel = height + 120.0F;
        float y = positiveModulo(time * 23.0F, travel) - 60.0F;
        int accent = SkijaTheme.accent();
        drawGradient(canvas, Rect.makeXYWH(0.0F, y - 38.0F, width, 76.0F),
                0.0F, y - 38.0F, 0.0F, y + 38.0F,
                new int[]{SkijaTheme.withAlpha(accent, 0), SkijaTheme.withAlpha(accent, 11),
                        SkijaTheme.withAlpha(accent, 0)},
                new float[]{0.0F, 0.5F, 1.0F});
        LINE_PAINT.setColor(SkijaTheme.withAlpha(accent, 24)).setStrokeWidth(1.0F);
        canvas.drawLine(0.0F, y, width, y, LINE_PAINT);
    }

    /** 三边（左、右、下）压暗，把中间内容区托起来；右侧比左侧更重，呼应光源在右上。 */
    private static void drawEdgeShade(Canvas canvas, float width, float height) {
        float edge = Math.min(150.0F, width * 0.24F);
        drawGradient(canvas, Rect.makeXYWH(0.0F, 0.0F, edge, height),
                0.0F, 0.0F, edge, 0.0F,
                new int[]{0x8C020306, 0x00020306});
        drawGradient(canvas, Rect.makeXYWH(width - edge, 0.0F, edge, height),
                width, 0.0F, width - edge, 0.0F,
                new int[]{0x76020306, 0x00020306});
        float vertical = Math.min(100.0F, height * 0.25F);
        drawGradient(canvas, Rect.makeXYWH(0.0F, height - vertical, width, vertical),
                0.0F, height, 0.0F, height - vertical,
                new int[]{0x76020306, 0x00020306});
    }

    private static void drawGradient(Canvas canvas, Rect bounds, float x0, float y0, float x1, float y1,
                                     int[] colors) {
        drawGradient(canvas, bounds, x0, y0, x1, y1, colors, null);
    }

    /** 渐变随用随建、画完即关：Shader 是原生资源，不能放进静态字段长期持有。 */
    private static void drawGradient(Canvas canvas, Rect bounds, float x0, float y0, float x1, float y1,
                                     int[] colors, float[] positions) {
        try (Shader shader = positions == null
                ? Shader.makeLinearGradient(x0, y0, x1, y1, colors)
                : Shader.makeLinearGradient(x0, y0, x1, y1, colors, positions)) {
            GRADIENT_PAINT.setShader(shader);
            canvas.drawRect(bounds, GRADIENT_PAINT);
            GRADIENT_PAINT.setShader(null);
        }
    }

    /** 单调递增的秒数（纳秒取低位再换算，避免长期运行后 double 精度下降）。 */
    private static float seconds() {
        return (System.nanoTime() & 0x1FFFFFFFFFFFFFL) / 1_000_000_000.0F;
    }

    private static Image loadImage(String resource) {
        try (InputStream stream = SkijaBackdrop.class.getResourceAsStream(resource)) {
            return stream == null ? null : Image.makeFromEncoded(readAllBytes(stream));
        } catch (Exception ignored) {
            // 内置背景缺失只意味着画程序化背景，不是错误；但不能让类初始化失败。
            return null;
        }
    }

    private static Image loadImage(Path file) {
        try {
            return Files.isRegularFile(file) ? Image.makeFromEncoded(Files.readAllBytes(file)) : null;
        } catch (Exception exception) {
            System.out.println("[noturne] Unable to load custom menu background " + file
                    + ": " + exception);
            return null;
        }
    }

    /** Java 8 的 {@code InputStream} 没有 readAllBytes()，等价实现（Java 9+ 才有）。 */
    private static byte[] readAllBytes(InputStream stream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = stream.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    /** 只按元数据读尺寸做上限校验，再解码一次；避免超大图先撑爆内存。 */
    private static BufferedImage decodeImport(Path source) throws IOException {
        try (ImageInputStream stream = ImageIO.createImageInputStream(source.toFile())) {
            if (stream == null) {
                throw new IOException("The selected file is not a supported image.");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                throw new IOException("Use a PNG, JPG, BMP, or GIF image.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                int imageWidth = reader.getWidth(0);
                int imageHeight = reader.getHeight(0);
                long pixels = (long) imageWidth * imageHeight;
                if (imageWidth <= 0 || imageHeight <= 0 || pixels > MAX_IMPORT_PIXELS) {
                    throw new IOException("The image resolution is too large.");
                }
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new IOException("The selected image could not be decoded.");
                }
                return image;
            } finally {
                reader.dispose();
            }
        }
    }

    private static Image mainMenuBackground() {
        ensureBackgroundStateLoaded();
        return customMainMenuBackground != null ? customMainMenuBackground : MAIN_MENU_BACKGROUND;
    }

    /**
     * 懒加载背景状态：优先用配置层注入的值；没注入过才读磁盘（标记文件 + 约定文件名）。
     *
     * <p>之所以懒加载而不是静态初始化：配置层会在启动后、第一次绘制前设置目录，
     * 静态初始化拿不到那个目录。
     */
    private static void ensureBackgroundStateLoaded() {
        if (backgroundStateLoaded) {
            return;
        }
        if (!gridBackgroundInjected) {
            gridBackground = Files.isRegularFile(gridBackgroundMarker());
        }
        customMainMenuBackground = gridBackground ? null : loadImage(customBackgroundPath());
        backgroundStateLoaded = true;
    }

    /** 换图时立刻释放上一张原生图片，否则导入多次会泄漏 GPU/堆内存。 */
    private static void replaceCustomBackground(Image replacement) {
        Image previous = customMainMenuBackground;
        customMainMenuBackground = replacement;
        if (previous != null && previous != replacement) {
            previous.close();
        }
    }

    private static Path customBackgroundPath() {
        return customBackgroundOverride != null
                ? customBackgroundOverride
                : backgroundDirectory().resolve("menu-background.png");
    }

    private static Path gridBackgroundMarker() {
        return backgroundDirectory()
                .resolve("use-grid-background");
    }

    /**
     * 背景状态目录；上游为 {@code Minecraft.gameDirectory/.setsuna/ui}。
     *
     * <p>MC 的工作目录就是 {@code user.dir}，替换约定见 docs/research/setsuna-gui-port.md；
     * 目录名对齐本项目其余 Skija 资源（如 {@code SkijaUi.fontDirectory()} 的
     * {@code <user.dir>/noturne/fonts}）。
     */
    public static Path backgroundDirectory() {
        Path directory = injectedDirectory;
        return directory != null
                ? directory
                : Paths.get(System.getProperty("user.dir"))
                        .resolve("noturne")
                        .resolve("ui");
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            // 跨卷/部分文件系统不支持原子移动，退化为普通替换。
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static float positiveModulo(float value, float modulus) {
        float result = value % modulus;
        return result < 0.0F ? result + modulus : result;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /** 高光斜线两端的屏幕坐标；上游是 record，这里降级为 Java 8 的 final 类。 */
    public static final class TraceLine {

        private final float startX;
        private final float startY;
        private final float endX;
        private final float endY;

        public TraceLine(float startX, float startY, float endX, float endY) {
            this.startX = startX;
            this.startY = startY;
            this.endX = endX;
            this.endY = endY;
        }

        public float startX() {
            return startX;
        }

        public float startY() {
            return startY;
        }

        public float endX() {
            return endX;
        }

        public float endY() {
            return endY;
        }

        /** 与上游 record 的语义保持一致：逐字段比较（float 用 {@link Float#compare}）。 */
        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof TraceLine)) {
                return false;
            }
            TraceLine that = (TraceLine) other;
            return Float.compare(startX, that.startX) == 0
                    && Float.compare(startY, that.startY) == 0
                    && Float.compare(endX, that.endX) == 0
                    && Float.compare(endY, that.endY) == 0;
        }

        /** 与 {@link #equals(Object)} 一致。 */
        @Override
        public int hashCode() {
            int result = Float.floatToIntBits(startX);
            result = 31 * result + Float.floatToIntBits(startY);
            result = 31 * result + Float.floatToIntBits(endX);
            result = 31 * result + Float.floatToIntBits(endY);
            return result;
        }

        @Override
        public String toString() {
            return "TraceLine[startX=" + startX + ", startY=" + startY
                    + ", endX=" + endX + ", endY=" + endY + "]";
        }
    }
}
