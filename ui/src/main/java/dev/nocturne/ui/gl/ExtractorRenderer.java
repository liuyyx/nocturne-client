package dev.nocturne.ui.gl;

import dev.nocturne.client.game.Reflect;
import dev.nocturne.ui.render.Color;
import dev.nocturne.ui.render.Renderer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Supplier;

/**
 * 26.x（SDL 渲染栈）的叠加层绘制后端：把 {@link Renderer} 的绘制原语翻译成游戏自己的
 * {@code GuiGraphicsExtractor} 调用，全程不碰 LWJGL 的 GL 绑定。
 *
 * <p><b>为什么必须换后端</b>：Minecraft 26.x 起 GL 上下文由 SDL 管理，LWJGL 的 GL 绑定在整个进程
 * 里都不可用——帧回调（缓冲交换点）、GUI 绘制路径、游戏自己的呈现入口三处调用 GL 都会让 LWJGL
 * 直接 {@code FATAL ERROR} 终止 JVM（native abort，Java 侧捕获不到）。而游戏自己的绘制 API 把绘制
 * 记录进 {@code GuiRenderState}、由游戏在它自己的管线里提交，不经过我们的 GL 调用，因此在 SDL 栈上
 * 安全（26.3 真机 spike 已验证：在 {@code extractRenderState} 内 {@code fill} 能画出矩形且游戏不崩）。
 *
 * <p><b>坐标口径</b>：extractor 用的是**游戏 GUI 缩放后的逻辑坐标**（{@code guiWidth()} 即
 * {@code Window.getGuiScaledWidth()}），与 {@link #width()} / {@link #height()} 返回的一致，因此
 * 组件布局与输入层的鼠标换算都不需要额外处理缩放（{@link #scale()} 恒为 1）。
 *
 * <p><b>字号</b>：26.x 的 {@code Font} 是固定尺寸（行高 {@code lineHeight}），没有"按字号绘制"的
 * 入口。本后端与 {@link MinecraftTextRenderer} 在无矩阵控制时的口径一致——按字体原生行高绘制，
 * 且 {@link #textWidth} / {@link #textHeight} 用同一基准度量，布局与绘制不会错位。
 *
 * <p><b>每帧有效期</b>：extractor 只在 {@code extractRenderState} 调用期间有效，因此由
 * {@link dev.nocturne.agent.GuiDrawHook} 每帧 {@link #setFrame(Object)} 一次，绘制必须当帧完成；
 * 帧结束后 {@link #clearFrame()} 释放引用，避免持有已失效的绘制上下文。
 *
 * <p>所有句柄都按**签名**解析（名字在三种环境下分别是 {@code fill}、{@code func_xxx}、混淆名，
 * 签名才是稳定的），缺项按可选处理：{@code outline} 缺失就用四条 {@code fill} 自己拼，
 * {@code enableScissor} 缺失就不裁剪（界面照画，只是溢出内容不被裁掉）。
 */
public final class ExtractorRenderer implements UiBackend {

    /** 后端短名，出现在 overlay diag 日志里。 */
    private static final String NAME = "gui-extractor";
    /** 字体行高拿不到时的兜底值（MC 字体原生行高）。 */
    private static final int FALLBACK_LINE_HEIGHT = 9;

    /** {@code fill(int x1, int y1, int x2, int y2, int argb)}。 */
    private final Method fill;
    /** {@code outline(int x, int y, int width, int height, int argb)}；缺失时为 {@code null}。 */
    private final Method outline;
    /** {@code enableScissor(int x1, int y1, int x2, int y2)}；缺失时为 {@code null}。 */
    private final Method enableScissor;
    /** {@code disableScissor()}；缺失时为 {@code null}。 */
    private final Method disableScissor;
    /** {@code text(Font font, String text, int x, int y, int argb)}。 */
    private final Method text;
    /** {@code guiWidth()}。 */
    private final Method guiWidth;
    /** {@code guiHeight()}。 */
    private final Method guiHeight;
    /** Minecraft 实例来源；字体（{@code Minecraft.font}）必须从实例上取，而实例在构造期可能还没就绪。 */
    private final Supplier<Object> minecraft;

    // ── 纹理支路（26.1–26.3 共用；签名已用 javap 在三个版本的 client jar 上核对）────────────
    /**
     * {@code RenderPipelines.GUI_TEXTURED} 的值；取不到就整条纹理支路不可用。
     *
     * <p>它是唯一需要「取一个游戏对象」的项；三种版本里字段名与所属类都相同，只有字段**类型**的包名
     * 在 26.3 从 {@code com.mojang.blaze3d.pipeline} 变成了 {@code com.mojang.renderpearl.api.pipeline}
     * ——反射按字段取值不受类型包名影响，这正是走反射而不是编译期链接的原因。
     */
    private final Object guiTexturedPipeline;
    /** {@code GuiGraphicsExtractor.blit(RenderPipeline, Identifier, int x, int y, float u, float v, int w, int h, int uWidth, int vHeight, int texW, int texH, int color)}。 */
    private final Method blit;
    /** {@code TextureManager.register(Identifier, AbstractTexture)}。 */
    private final Method registerTexture;
    /** {@code TextureManager.release(Identifier)}。 */
    private final Method releaseTexture;
    /** {@code Minecraft.getTextureManager()}。 */
    private final Method getTextureManager;
    /** {@code DynamicTexture(String, int, int, boolean)}。 */
    private final java.lang.reflect.Constructor<?> newDynamicTexture;
    /** {@code DynamicTexture.getPixels()}。 */
    private final Method getPixels;
    /** {@code DynamicTexture.upload()}。 */
    private final Method uploadTexture;
    /** {@code AbstractTexture.close()}；删除纹理时释放 GpuTexture。 */
    private final Method closeTexture;
    /** {@code NativeImage.setPixel(int, int, int)}（入参为 ARGB，内部转 ABGR）。 */
    private final Method setPixel;
    /** {@code NativeImage.getPointer()}；批量写像素用的原生地址。 */
    private final Method getPointer;
    /** {@code MemoryUtil.memByteBuffer(long, int)}；把原生地址包成可写的直接缓冲。 */
    private final Method memByteBuffer;
    /** {@code Identifier.fromNamespaceAndPath(String, String)}。 */
    private final Method newIdentifier;

    /** 已注册的纹理；下标即对外的纹理句柄（0 保留为"无效"）。 */
    private final java.util.List<RegisteredTexture> textures = new java.util.ArrayList<RegisteredTexture>();
    /** 纹理管理器缓存（首次取到时写入）。 */
    private Object textureManager;
    /** ARGB→ABGR 的转换暂存，避免每次上传分配。 */
    private int[] pixelScratch;
    /** 纹理支路是否可用（探针全绿）。 */
    private final boolean textureSupport;
    /** 纹理支路首次失败的诊断是否已打印（只报一次）。 */
    private boolean textureFailureLogged;

    /**
     * 一张已注册到游戏纹理管理器的动态纹理。
     *
     * <p>26.x 的自定义纹理必须交给 {@code TextureManager} 注册后才能被 {@code blit} 按 {@code Identifier}
     * 引用，因此句柄要同时记住三样东西：注册名、纹理对象（取像素）、尺寸（换算 UV）。
     */
    private static final class RegisteredTexture {
        /** 注册名。 */
        final Object identifier;
        /** {@code DynamicTexture} 实例。 */
        final Object texture;
        /** 宽（像素）。 */
        final int width;
        /** 高（像素）。 */
        final int height;

        RegisteredTexture(Object identifier, Object texture, int width, int height) {
            this.identifier = identifier;
            this.texture = texture;
            this.width = width;
            this.height = height;
        }
    }

    /** 本帧的绘制上下文；由钩子在 {@code extractRenderState} 期间设置。 */
    private volatile Object frame;
    /** 字体实例；未就绪时为 {@code null}（每帧重试，见 {@link #ensureFont()}）。 */
    private volatile Object font;
    /** {@code Font.width(String)}。 */
    private volatile Method fontWidth;
    /** 字体行高（逻辑像素）。 */
    private volatile int lineHeight = FALLBACK_LINE_HEIGHT;
    /** 本帧绘制区宽高（逻辑像素）；未知为 0。 */
    private int width;
    private int height;
    /** 绘制调用是否已失败过（只报一次，避免每帧刷屏）。 */
    private boolean drawFailureLogged;
    /** 是否已就字体缺失报过一次。 */
    private boolean fontMissingLogged;
    /** 是否已打过首帧尺寸日志（只打一次）。 */
    private boolean firstFrameLogged;

    private ExtractorRenderer(Class<?> extractorType, Method fill, Method outline, Method enableScissor,
                              Method disableScissor, Method text, Method guiWidth, Method guiHeight,
                              Supplier<Object> minecraft) {
        this.fill = fill;
        this.outline = outline;
        this.enableScissor = enableScissor;
        this.disableScissor = disableScissor;
        this.text = text;
        this.guiWidth = guiWidth;
        this.guiHeight = guiHeight;
        this.minecraft = minecraft;
        // 句柄 0 保留为"无效"（Renderer 契约：id<=0 表示新建），因此先占一个空槽。
        textures.add(null);
        // 纹理支路：一次把 26.1–26.3 上签名一致的入口全部绑定；缺任何一项就整条支路关掉，
        // 界面退回「游戏字体 + ASCII 图标」（见 BackendFaDraw 的降级说明）。
        ClassLoader loader = extractorType.getClassLoader();
        this.guiTexturedPipeline = Reflect.staticField(Reflect.loadWithoutInit(
                "net.minecraft.client.renderer.RenderPipelines", loader), "GUI_TEXTURED");
        this.blit = blitMethod(extractorType);
        Class<?> textureManager = Reflect.loadWithoutInit(
                "net.minecraft.client.renderer.texture.TextureManager", loader);
        this.registerTexture = Reflect.method(textureManager, "register", identifierType(loader),
                Reflect.loadWithoutInit("net.minecraft.client.renderer.texture.AbstractTexture", loader));
        this.releaseTexture = Reflect.method(textureManager, "release", identifierType(loader));
        Class<?> minecraftType = Reflect.loadWithoutInit("net.minecraft.client.Minecraft", loader);
        this.getTextureManager = Reflect.method(minecraftType, "getTextureManager");
        Class<?> dynamicTexture = Reflect.loadWithoutInit(
                "net.minecraft.client.renderer.texture.DynamicTexture", loader);
        this.newDynamicTexture = constructor(dynamicTexture, String.class, int.class, int.class,
                boolean.class);
        this.getPixels = Reflect.method(dynamicTexture, "getPixels");
        this.uploadTexture = Reflect.method(dynamicTexture, "upload");
        this.closeTexture = Reflect.method(Reflect.loadWithoutInit(
                "net.minecraft.client.renderer.texture.AbstractTexture", loader), "close");
        Class<?> nativeImage = Reflect.loadWithoutInit("com.mojang.blaze3d.platform.NativeImage", loader);
        this.setPixel = Reflect.method(nativeImage, "setPixel", int.class, int.class, int.class);
        this.getPointer = Reflect.method(nativeImage, "getPointer");
        // 批量写像素：NativeImage 只暴露逐像素 setPixel，而一张 1024² 图集是 100 万次反射调用
        // （数十毫秒，逐帧卡顿）。改走「拿原生地址 → 包成 ByteBuffer → 整块 put int」。
        // MemoryUtil 是 LWJGL 的内存工具（不是 GL 绑定），在任何渲染栈上都可用、也不会碰 GL。
        Class<?> memoryUtil = Reflect.loadWithoutInit("org.lwjgl.system.MemoryUtil", loader);
        this.memByteBuffer = Reflect.method(memoryUtil, "memByteBuffer", long.class, int.class);
        this.newIdentifier = Reflect.method(identifierType(loader), "fromNamespaceAndPath",
                String.class, String.class);
        this.textureSupport = guiTexturedPipeline != null && blit != null && registerTexture != null
                && getTextureManager != null && newDynamicTexture != null && getPixels != null
                && uploadTexture != null && setPixel != null && getPointer != null
                && memByteBuffer != null && newIdentifier != null;
        if (!textureSupport) {
            System.out.println("[nocturne] extractor backend: custom textures unavailable"
                    + " (pipeline=" + (guiTexturedPipeline != null)
                    + " blit=" + (blit != null)
                    + " register=" + (registerTexture != null)
                    + " textureManager=" + (getTextureManager != null)
                    + " dynamicTexture=" + (newDynamicTexture != null)
                    + " pixels=" + (getPixels != null)
                    + " upload=" + (uploadTexture != null)
                    + " setPixel=" + (setPixel != null)
                    + " identifier=" + (newIdentifier != null)
                    + "); glyphs fall back to the game font and icons to ASCII");
        }
    }

    /** {@code Identifier} 类；按名加载（26.x 未混淆，名字稳定）。 */
    private static Class<?> identifierType(ClassLoader loader) {
        return Reflect.loadWithoutInit("net.minecraft.resources.Identifier", loader);
    }

    /**
     * 绑定 {@code blit}。
     *
     * <p>按「名字 + 参数个数 + 首参是 RenderPipeline、次参是 Identifier、其余为 int/float」匹配，
     * 而不是写死参数类型：26.3 把 RenderPipeline 的包名从 {@code com.mojang.blaze3d.pipeline}
     * 改成了 {@code com.mojang.renderpearl.api.pipeline}，写死类型会在那一个版本上全部失配。
     *
     * @return 13 参重载；形状不符时返回 {@code null}
     */
    private static Method blitMethod(Class<?> extractorType) {
        for (Method candidate : extractorType.getMethods()) {
            if (!"blit".equals(candidate.getName())) {
                continue;
            }
            Class<?>[] parameters = candidate.getParameterTypes();
            if (parameters.length != 13 || !parameters[0].getSimpleName().equals("RenderPipeline")
                    || !parameters[1].getSimpleName().equals("Identifier")) {
                continue;
            }
            boolean shape = true;
            for (int i = 2; i < parameters.length && shape; i++) {
                Class<?> type = parameters[i];
                shape = type == int.class || type == float.class;
            }
            if (shape) {
                return candidate;
            }
        }
        return null;
    }

    /** 按签名取构造器；缺失时返回 {@code null}。 */
    private static java.lang.reflect.Constructor<?> constructor(Class<?> owner, Class<?>... parameters) {
        if (owner == null) {
            return null;
        }
        try {
            return owner.getConstructor(parameters);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 绑定 26.x 的 extractor 后端。
     *
     * <p>必需项（{@code fill} / {@code text} / {@code guiWidth} / {@code guiHeight}）任一缺失就返回
     * {@code null}，让调用方回落——半个后端画不出界面，只会让人误以为"画了但看不见"。
     *
     * @param extractorType 已加载的 {@code GuiGraphicsExtractor} 类（由调用方从游戏加载器里取，
     *                      这样不会触发按名加载）
     * @param minecraft     Minecraft 实例来源（生产用 {@code bridge::minecraft}）；字体从它上面读
     * @return 可用后端；该版本没有 extractor（或签名不符）时返回 {@code null}
     */
    public static ExtractorRenderer bind(Class<?> extractorType, Supplier<Object> minecraft) {
        if (extractorType == null) {
            return null;
        }
        Method fill = method(extractorType, "fill", int.class, int.class, int.class, int.class, int.class);
        Method text = method(extractorType, "text", null, String.class, int.class, int.class, int.class);
        Method guiWidth = method(extractorType, "guiWidth");
        Method guiHeight = method(extractorType, "guiHeight");
        if (fill == null || text == null || guiWidth == null || guiHeight == null) {
            return null;
        }
        // 可选项：缺了也能画，只是退化（描边自拼、不裁剪）。
        Method outline = method(extractorType, "outline", int.class, int.class, int.class, int.class, int.class);
        Method enableScissor = method(extractorType, "enableScissor", int.class, int.class, int.class, int.class);
        Method disableScissor = method(extractorType, "disableScissor");
        return new ExtractorRenderer(extractorType, fill, outline, enableScissor, disableScissor, text,
                guiWidth, guiHeight, minecraft);
    }

    /**
     * 设置本帧的绘制上下文；每帧在 {@code extractRenderState} 内调用一次。
     *
     * @param graphics {@code GuiGraphicsExtractor} 实例；{@code null} 表示本帧无绘制
     */
    public void setFrame(Object graphics) {
        this.frame = graphics;
    }

    /**
     * 释放本帧的绘制上下文（帧结束后调用）；此后 {@link #ready()} 为 {@code false}。
     *
     * <p>尺寸**不清零**：它是窗口属性（GUI 缩放后的逻辑尺寸），不是某帧的临时值。输入层在
     * 帧回调里要拿它做鼠标坐标换算，而帧回调早于本帧的 extract 阶段——清零会让换算读到 0，
     * 整个界面的点击都落空（26.3 实测踩过）。
     */
    public void clearFrame() {
        this.frame = null;
    }

    @Override
    public void beginFrame() {
        Object graphics = frame;
        if (graphics == null) {
            width = 0;
            height = 0;
            return;
        }
        // 只在读到有效尺寸时更新：保留最近一次有效值（见 clearFrame 的说明）。
        int measuredWidth = intOf(guiWidth, graphics, 0);
        int measuredHeight = intOf(guiHeight, graphics, 0);
        if (measuredWidth > 0) {
            width = measuredWidth;
        }
        if (measuredHeight > 0) {
            height = measuredHeight;
        }
        // 上一帧若有组件没配平 push/pop（异常提前返回），裁剪矩形还挂在游戏的绘制状态上，
        // 会把游戏自己后续画的内容整片裁掉——每帧开头清掉这个残留。
        resetClipStack(graphics);
        ensureFont();
        // 首帧尺寸必须可见：绘制区尺寸为 0 时组件树按 0 布局，界面上一个像素都画不出来，
        // 而日志里此前只有一句"overlay attached"，看起来像装好了。
        if (!firstFrameLogged) {
            firstFrameLogged = true;
            System.out.println("[nocturne] extractor backend first frame: " + width + "x" + height
                    + ", font=" + (font == null ? "pending" : font.getClass().getSimpleName()));
        }
    }

    @Override
    public void endFrame() {
        // 无需收尾：绘制已按调用顺序记录进游戏的 GuiRenderState，由游戏自己提交。
    }

    @Override
    public String backendName() {
        return NAME;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public boolean ready() {
        return frame != null;
    }

    @Override
    public void rect(float x, float y, float width, float height, Color color) {
        if (width <= 0f || height <= 0f || color == null) {
            return;
        }
        Object graphics = frame;
        if (graphics == null) {
            return;
        }
        int x1 = Math.round(x);
        int y1 = Math.round(y);
        int x2 = Math.round(x + width);
        int y2 = Math.round(y + height);
        if (x2 <= x1 || y2 <= y1) {
            // 取整后塌陷（亚像素矩形）：跳过，否则游戏侧拿到零宽矩形。
            return;
        }
        call(fill, graphics, x1, y1, x2, y2, color.argb);
    }

    @Override
    public void roundedRect(float x, float y, float width, float height, float radius, Color color) {
        if (width <= 0f || height <= 0f || color == null) {
            return;
        }
        // 半径夹取到不超过一半短边：否则上下两条圆角行会互相穿插，画出一团错位的色带。
        float r = Math.min(radius, Math.min(width, height) / 2f);
        if (r <= 0.5f) {
            rect(x, y, width, height, color);
            return;
        }
        int rows = Math.round(r);
        // 主体：中段整宽 + 上下两条去掉左右圆角的窄段。
        rect(x, y + r, width, height - 2f * r, color);
        rect(x + r, y, width - 2f * r, r, color);
        rect(x + r, y + height - r, width - 2f * r, r, color);
        // 圆角：逐行内缩（26.x 没有圆角原语）。每行一次 fill，行数 = 半径，半径通常 ≤ 8。
        int baseY = Math.round(y);
        int bottomY = Math.round(y + height);
        for (int i = 0; i < rows; i++) {
            float dy = r - i - 0.5f;
            float inset = r - (float) Math.sqrt(Math.max(0f, r * r - dy * dy));
            int insetPx = Math.round(inset);
            rect(x + insetPx, baseY + i, width - 2f * insetPx, 1f, color);
            rect(x + insetPx, bottomY - 1 - i, width - 2f * insetPx, 1f, color);
        }
    }

    @Override
    public void outline(float x, float y, float width, float height, float lineWidth, Color color) {
        if (width <= 0f || height <= 0f || color == null) {
            return;
        }
        Object graphics = frame;
        if (graphics == null) {
            return;
        }
        int x1 = Math.round(x);
        int y1 = Math.round(y);
        int w = Math.round(width);
        int h = Math.round(height);
        if (w <= 0 || h <= 0) {
            return;
        }
        if (outline != null) {
            // 26.x 的 outline 是固定 1px 描边，lineWidth 无法表达——按 1px 画，不假装支持。
            call(outline, graphics, x1, y1, w, h, color.argb);
            return;
        }
        // 没有 outline 就自己拼四条边（1px）。
        rect(x1, y1, w, 1f, color);
        rect(x1, y1 + h - 1f, w, 1f, color);
        rect(x1, y1, 1f, h, color);
        rect(x1 + w - 1f, y1, 1f, h, color);
    }

    @Override
    public void text(String text, float x, float y, float size, Color color) {
        if (text == null || text.isEmpty() || color == null) {
            return;
        }
        Object graphics = frame;
        Object fontInstance = font;
        if (graphics == null || fontInstance == null) {
            return;
        }
        // size 被忽略：26.x 的 Font 只有原生行高一种尺寸，度量与绘制都用它，布局自洽。
        call(this.text, graphics, fontInstance, text, Math.round(x), Math.round(y), color.argb);
    }

    @Override
    public float textWidth(String text, float size) {
        if (text == null || text.isEmpty()) {
            return 0f;
        }
        ensureFont();
        Method widthHandle = fontWidth;
        Object fontInstance = font;
        if (widthHandle == null || fontInstance == null) {
            // 字体还没就绪：按字符数估算，避免布局塌陷成 0（下一帧就会用真值）。
            return text.length() * 6f;
        }
        // 必须走四参重载（带实参 text）：三参重载是「无参方法 + 兜底值」，
        // 传进来会落成 intOf(fontWidth, font, fallback=length*6)，于是 Font.width(String)
        // 根本没被调用，宽度恒等于 length×6——测试里 FakeFont 对任何文本都返回同一个值，掩盖了它。
        return intOf(widthHandle, fontInstance, text, text.length() * 6);
    }

    @Override
    public float textHeight(float size) {
        ensureFont();
        return lineHeight;
    }

    // ------------------------------------------------------------------ 纹理

    /**
     * 26.x 走游戏自己的纹理注册表：{@code DynamicTexture} + {@code TextureManager.register} +
     * {@code GuiGraphicsExtractor.blit}。三者的签名已用 {@code javap} 在 26.1.2 / 26.2 / 26.3 的
     * 客户端 jar 上逐条核对。
     *
     * <p>这里报的是**能力**而不是"此刻能不能画"：字体句柄的选型只做一次（见
     * {@code FontRasterizer.bind}），若把"当前有没有绘制上下文"也算进能力，26.2 这种
     * 「帧回调 + 绘制钩子两个时机」的版本就会在帧回调那次把字体永久定成退化实现。
     * 真正需要绘制上下文的动作（{@link #uploadTexture}）自己在帧外拒绝执行。
     */
    @Override
    public boolean supportsTextures() {
        return textureSupport;
    }

    @Override
    public int uploadTexture(int id, int width, int height, int[] argb) {
        // 帧外（例如 26.2 的帧回调时机）不碰设备的纹理对象：那时没有绘制上下文，
        // 而调用方（字形图集）会因为拿不到句柄而跳过绘制，下一次真正的绘制时机再上传。
        if (!textureSupport || frame == null || width <= 0 || height <= 0 || argb == null) {
            return id;
        }
        Object manager = textureManager();
        if (manager == null) {
            return id;
        }
        RegisteredTexture target = id > 0 && id < textures.size() ? textures.get(id) : null;
        if (target != null && (target.width != width || target.height != height)) {
            // 尺寸变了：老注册项作废（图集尺寸恒定，正常不会走到这里）。
            deleteTexture(id);
            target = null;
        }
        if (target == null) {
            int index = textures.size();
            Object identifier = Reflect.call(newIdentifier, null, NAMESPACE, "ui/atlas_" + index);
            Object texture = createTexture("nocturne/ui/atlas_" + index, width, height);
            if (identifier == null || texture == null) {
                return id;
            }
            // 注册必须在写入像素之前：blit 通过注册名解析纹理，没注册就是空指针。
            Reflect.call(registerTexture, manager, identifier, texture);
            target = new RegisteredTexture(identifier, texture, width, height);
            textures.add(target);
            id = index;
        }
        if (!writePixels(target, argb)) {
            return id;
        }
        Reflect.call(uploadTexture, target.texture);
        return id;
    }

    @Override
    public void deleteTexture(int id) {
        if (id <= 0 || id >= textures.size()) {
            return;
        }
        RegisteredTexture target = textures.get(id);
        textures.set(id, null);
        if (target == null) {
            return;
        }
        Object manager = textureManager();
        if (manager != null && releaseTexture != null) {
            Reflect.call(releaseTexture, manager, target.identifier);
        }
        // 纹理本体持有 GpuTexture，必须显式关闭，否则每换一次图集泄漏一块显存。
        Reflect.call(closeTexture, target.texture);
    }

    @Override
    public void texturedQuad(int texture, float x, float y, float width, float height,
                             float u0, float v0, float u1, float v1, int argb) {
        if (!textureSupport || width <= 0f || height <= 0f || (argb >>> 24) == 0) {
            return;
        }
        Object graphics = frame;
        if (graphics == null || texture <= 0 || texture >= textures.size()) {
            return;
        }
        RegisteredTexture target = textures.get(texture);
        if (target == null) {
            return;
        }
        // blit 的位置与 UV 都是整数：位置取整（26.x 没有浮点变体），UV 由归一化坐标换算回纹素。
        int bx = Math.round(x);
        int by = Math.round(y);
        int bw = Math.max(1, Math.round(width));
        int bh = Math.max(1, Math.round(height));
        float uStart = u0 * target.width;
        float vStart = v0 * target.height;
        int uSpan = Math.round((u1 - u0) * target.width);
        int vSpan = Math.round((v1 - v0) * target.height);
        call(blit, graphics, guiTexturedPipeline, target.identifier,
                bx, by, uStart, vStart, bw, bh, uSpan, vSpan, target.width, target.height, argb);
    }

    /** @return 纹理管理器；游戏实例尚未就绪时返回 {@code null} */
    private Object textureManager() {
        Object cached = textureManager;
        if (cached != null) {
            return cached;
        }
        Supplier<Object> source = minecraft;
        Object instance = source == null ? null : source.get();
        if (instance == null) {
            return null;
        }
        Object manager = Reflect.call(getTextureManager, instance);
        textureManager = manager;
        return manager;
    }

    /** 造一张 {@code DynamicTexture}；失败返回 {@code null}。 */
    private Object createTexture(String name, int width, int height) {
        try {
            // useCalloc=true：像素从零开始，没被写到的格子是透明而不是垃圾值。
            return newDynamicTexture.newInstance(name, width, height, Boolean.TRUE);
        } catch (Throwable t) {
            if (!textureFailureLogged) {
                textureFailureLogged = true;
                System.out.println("[nocturne] extractor backend: DynamicTexture creation failed: " + t);
            }
            return null;
        }
    }

    /**
     * 把整块像素写进纹理的原生缓冲。
     *
     * <p>逐像素 {@code setPixel} 是 100 万次反射调用（一张 1024² 图集数十毫秒，逐帧卡顿），因此走
     * 「原生地址 → {@code MemoryUtil.memByteBuffer} → 整块写 int」。颜色按 {@code setPixel} 的同一
     * 口径转换（ARGB → ABGR），纹理内容与非 26.x 路径完全一致。
     *
     * @return 写入成功返回 {@code true}
     */
    private boolean writePixels(RegisteredTexture target, int[] argb) {
        Object pixels = Reflect.call(getPixels, target.texture);
        if (pixels == null) {
            return false;
        }
        int count = target.width * target.height;
        if (pixelScratch == null || pixelScratch.length < count) {
            pixelScratch = new int[count];
        }
        for (int i = 0; i < count; i++) {
            int value = argb[i];
            // 与 NativeImage.setPixel 内部一致：ABGR 布局（A<<24|B<<16|G<<8|R）。
            pixelScratch[i] = (value & 0xFF00FF00) | ((value >>> 16) & 0xFF) | ((value & 0xFF) << 16);
        }
        Object pointer = Reflect.call(getPointer, pixels);
        if (!(pointer instanceof Number)) {
            return false;
        }
        long address = ((Number) pointer).longValue();
        Object buffer = Reflect.call(memByteBuffer, null, address, count * 4);
        if (!(buffer instanceof java.nio.ByteBuffer)) {
            return false;
        }
        java.nio.ByteBuffer view = ((java.nio.ByteBuffer) buffer)
                .order(java.nio.ByteOrder.nativeOrder());
        view.asIntBuffer().put(pixelScratch, 0, count);
        return true;
    }

    /** 把动态纹理注册进游戏自己的纹理管理器时用的命名空间。 */
    private static final String NAMESPACE = "nocturne";

    /**
     * 裁剪矩形栈：每层是 {@code [x1, y1, x2, y2]}。
     *
     * <p>GL 只有一个裁剪矩形，所以"嵌套"必须自己记账：内层 pop 之后要把外层的矩形重新启用，
     * 否则一次嵌套就把外层的裁剪丢掉。都是浮点入参、整数落地的矩形，直接存 int。
     */
    private final java.util.ArrayDeque<int[]> clipStack = new java.util.ArrayDeque<int[]>();

    @Override
    public void pushClip(float x, float y, float width, float height) {
        Object graphics = frame;
        if (graphics == null || enableScissor == null) {
            return;
        }
        int[] rect = {Math.round(x), Math.round(y),
                Math.round(x + width), Math.round(y + height)};
        clipStack.push(rect);
        call(enableScissor, graphics, rect[0], rect[1], rect[2], rect[3]);
    }

    @Override
    public void popClip() {
        Object graphics = frame;
        if (graphics == null || disableScissor == null) {
            return;
        }
        if (clipStack.isEmpty()) {
            // 没有配对的 push：什么都不做。多余地 disable 会把**外层**（可能是游戏自己的）
            // 裁剪区域一起关掉。
            return;
        }
        clipStack.pop();
        if (clipStack.isEmpty()) {
            call(disableScissor, graphics);
            return;
        }
        int[] outer = clipStack.peek();
        call(enableScissor, graphics, outer[0], outer[1], outer[2], outer[3]);
    }

    /**
     * 每帧开始前收拾裁剪残留：上一帧若有不配对的 push（组件抛异常提前返回），
     * 裁剪矩形会一直挂在游戏的绘制状态上，把游戏后续画的内容整片裁掉。
     */
    private void resetClipStack(Object graphics) {
        if (clipStack.isEmpty()) {
            return;
        }
        clipStack.clear();
        if (graphics != null && disableScissor != null) {
            call(disableScissor, graphics);
        }
    }

    /**
     * 取字体句柄：{@code Minecraft.font} 在构造期可能还没就绪（GL 可用早于实例构造），因此每帧重试。
     *
     * <p>字体缺失时界面照画（矩形全在），只是没有文字——所以这里只报一次，且措辞指向真正的原因，
     * 避免被当成"界面没生效"。
     */
    private void ensureFont() {
        if (font != null) {
            return;
        }
        Supplier<Object> source = minecraft;
        Object instance = source == null ? null : source.get();
        if (instance == null) {
            return;
        }
        Object candidate = fieldValue(instance, "font");
        if (candidate == null) {
            if (!fontMissingLogged) {
                fontMissingLogged = true;
                System.out.println("[nocturne] extractor backend: Minecraft has no readable 'font' field;"
                        + " the overlay will draw without text");
            }
            return;
        }
        font = candidate;
        fontWidth = method(candidate.getClass(), "width", String.class);
        lineHeight = intField(candidate, "lineHeight", FALLBACK_LINE_HEIGHT);
    }

    /** 调用方法；句柄为 {@code null} 时什么也不做，失败只报一次（限流）。 */
    private void call(Method method, Object target, Object... args) {
        if (method == null) {
            return;
        }
        try {
            method.invoke(target, args);
        } catch (Throwable t) {
            if (!drawFailureLogged) {
                drawFailureLogged = true;
                System.out.println("[nocturne] extractor backend draw call failed (" + method.getName()
                        + "): " + t);
            }
        }
    }

    /** 调用返回 int 的方法；失败时返回兜底值。 */
    private static int intOf(Method method, Object target, int fallback) {
        if (method == null) {
            return fallback;
        }
        try {
            Object result = method.invoke(target);
            return result instanceof Number ? ((Number) result).intValue() : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** 调用返回 int 的单参方法（{@code Font.width(String)}）。 */
    private static int intOf(Method method, Object target, Object argument, int fallback) {
        if (method == null) {
            return fallback;
        }
        try {
            Object result = method.invoke(target, argument);
            return result instanceof Number ? ((Number) result).intValue() : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }

    /**
     * 按签名查找方法。
     *
     * <p>用签名而不是名字：同一个方法在官方混淆 / SRG / 未混淆三种环境下的名字完全不同，签名才稳定。
     * {@code firstParameter} 为 {@code null} 时表示"只要参数个数与其余类型匹配"，用于
     * {@code text(Font, String, int, int, int)} 这种首参类型由运行期决定的场景。
     */
    private static Method method(Class<?> owner, String name, Class<?>... parameterTypes) {
        if (owner == null) {
            return null;
        }
        for (Method candidate : owner.getMethods()) {
            if (!candidate.getName().equals(name)) {
                continue;
            }
            Class<?>[] actual = candidate.getParameterTypes();
            if (actual.length != parameterTypes.length) {
                continue;
            }
            boolean match = true;
            for (int i = 0; i < actual.length; i++) {
                if (parameterTypes[i] != null && parameterTypes[i] != actual[i]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                try {
                    candidate.setAccessible(true);
                } catch (Throwable ignored) {
                    // 公开方法无需 setAccessible；失败也照用（调用时会再失败一次并报出来）。
                }
                return candidate;
            }
        }
        return null;
    }

    /** 读取实例字段的值；字段缺失或读失败时返回 {@code null}。 */
    private static Object fieldValue(Object target, String name) {
        Field field = fieldHandle(target.getClass(), name);
        if (field == null) {
            return null;
        }
        try {
            return field.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 读取 int 实例字段；字段缺失或类型不符时返回兜底值。 */
    private static int intField(Object target, String name, int fallback) {
        Object value = fieldValue(target, name);
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    /** 取字段句柄（沿继承链找）；找不到返回 {@code null}。 */
    private static Field fieldHandle(Class<?> owner, String name) {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (Throwable ignored) {
                // 继续往父类找。
            }
        }
        return null;
    }
}
