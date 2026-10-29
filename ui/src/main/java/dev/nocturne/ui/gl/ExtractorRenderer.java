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
public final class ExtractorRenderer implements UiBackend, PlayerModelPreview {

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
    private Object guiTexturedPipeline;
    /** {@code GuiGraphicsExtractor.blit(RenderPipeline, Identifier, int x, int y, float u, float v, int w, int h, int uWidth, int vHeight, int texW, int texH, int color)}。 */
    private Method blit;
    /** 1.20–1.21：{@code blit(Identifier,int,int,float,float,int,int,int,int)}，UV 为纹素。 */
    private Method blitColored;
    /** {@code GuiGraphics.setColor(float,float,float,float)}；配合无颜色重载做着色。 */
    private Method setColorMethod;
    /** {@code TextureManager.register(Identifier, AbstractTexture)}。 */
    private Method registerTexture;
    /** {@code TextureManager.release(Identifier)}。 */
    private Method releaseTexture;
    /** {@code Minecraft.getTextureManager()}。 */
    private Method getTextureManager;
    /** {@code DynamicTexture(String, int, int, boolean)}。 */
    private java.lang.reflect.Constructor<?> newDynamicTexture;
    /** {@code DynamicTexture.getPixels()}。 */
    private Method getPixels;
    /** {@code DynamicTexture.upload()}。 */
    private Method uploadTexture;
    /** {@code NativeImage.setFilter(boolean blur, boolean mipmap)}；1.20–1.21 可选。 */
    private Method textureFilter;
    /** 1.20–1.21 的 RenderSystem.enableBlend/defaultBlendFunc。 */
    private Method enableBlend;
    private Method defaultBlendFunc;
    /** {@code AbstractTexture.close()}；删除纹理时释放 GpuTexture。 */
    private Method closeTexture;
    /** {@code NativeImage.setPixel(int, int, int)}（原生 ABGR 布局）。 */
    private Method setPixel;
    /** {@code DynamicTexture.setPixels(NativeImage)}；函数式写入返回新图时的替换入口。 */
    private Method setPixels;
    /** {@code NativeImage.getPointer()}；有公开入口时直接取原生地址。 */
    private Method getPointer;
    /** 无公开 getPointer 时的原生地址字段（非 final long；final long 是 size）。 */
    private Field pointerField;
    /** {@code MemoryUtil.memByteBuffer(long, int)}；把原生地址包成可写的直接缓冲。 */
    private Method memByteBuffer;
    /** {@code Identifier.fromNamespaceAndPath(String, String)}。 */
    private Method newIdentifier;

    /** 已注册的纹理；下标即对外的纹理句柄（0 保留为"无效"）。 */
    private final java.util.List<RegisteredTexture> textures = new java.util.ArrayList<RegisteredTexture>();
    /** 纹理管理器缓存（首次取到时写入）。 */
    private Object textureManager;
    /** ARGB→ABGR 的转换暂存，避免每次上传分配。 */
    private int[] pixelScratch;
    /** 纹理支路是否可用（探针全绿）。 */
    private boolean textureSupport;
    /** 1.21.6+ / 1.20-1.21 的「无 pipeline」blit：{@code (Identifier,int,int,int,int,float,float,float,float)}。 */
    private Method blitSimple;
    /** 旧版无颜色 blit：{@code (Identifier,int,int,int,int,int,int,int,int)}（x,y,u,v,w,h,texW,texH）。 */
    private Method blitPixel;
    /** {@code NativeImage.applyToPixels(IntUnaryOperator)}；返回新图，必须配合 setPixels。 */
    private Method applyPixels;
    /** 注册入口本身就会上传（1.21.11 的 TextureManager.register(Identifier, TextureContents)）。 */
    private boolean registerUploads;
    /** 旧版 {@code new ResourceLocation(ns, path)}。 */
    private java.lang.reflect.Constructor<?> identifierCtor;
    /** 纹理支路首次失败的诊断是否已打印（只报一次）。 */
    private boolean textureFailureLogged;
    /** 26.x 的线性采样器；同一后端创建的图集共用。 */
    private Object linearSampler;
    /** {@code AbstractTexture.sampler}；游戏未暴露该能力时为 {@code null}。 */
    private Field samplerField;
    /** 是否已解析过线性采样能力，避免每张图集重复扫描。 */
    private boolean samplerResolved;
    /** 上传诊断计数。 */
    private int uploadDiag;
    /** 绘制诊断计数。 */
    private int drawDiag;

    private void quadDiag(String message) {
        if (System.getProperty("nocturne.ui.debug") == null || drawDiag >= 4) {
            return;
        }
        drawDiag++;
        System.out.println("[nocturne] extractor quad: " + message);
    }

    private static int bx0(float value) {
        return Math.round(value);
    }

    private static int by0(float value) {
        return Math.round(value);
    }

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
    /** 原生库存模型绘制入口；成功绑定后跨帧复用，帧对象本身绝不缓存。 */
    private InventoryPlayerPreview playerPreview;
    /** 方法在当前游戏映射下不存在时不再逐帧扫描方法表。 */
    private boolean previewAttempted;
    /** {@code GuiGraphics.pose()}（1.20+ 的 2D 矩阵栈）。 */
    private final Method poseMethod;
    /** {@code Matrix3x2fStack.pushMatrix()}。 */
    private final Method pushMatrix;
    /** {@code Matrix3x2fStack.popMatrix()}。 */
    private final Method popMatrix;
    /** {@code Matrix3x2fStack.scale(float, float)}。 */
    private final Method scaleMatrix;
    /** {@code Matrix3x2fStack.translate(float, float)}；26.x 纹理几何保留小数时使用。 */
    private final Method translateMatrix;
    /** 字体的原生像素高度（MC 字体 9px）；缩放基准。 */
    private static final float NATIVE_FONT_PX = 9f;

    /** 映射表；用于按运行期名字解析字体度量（{@code Font.width} 在 Fabric 下是 method_1727）。 */
    private final dev.nocturne.client.mapping.Mapping mapping;
    /** 字体来源（按映射表解析；{@code null} 时退回按名读字段）。 */
    private final Supplier<Object> fontSource;
    /** 字体实例；未就绪时为 {@code null}（每帧重试，见 {@link #ensureFont()}）。 */
    private volatile Object font;
    /** {@code Font.width(String)}。 */
    private volatile Method fontWidth;
    /** 字体行高（逻辑像素）。 */
    private volatile int lineHeight = FALLBACK_LINE_HEIGHT;
    /** 本帧绘制区宽高（逻辑像素）；未知为 0。 */
    private int width;
    private int height;
    /*
     * GUI 缩放（物理像素 / 逻辑像素）由 {@link #guiScale} 承担：
     * 组件树按「设计单位 × scale」排版。1.20+ 的 GuiGraphics 用逻辑坐标，若 scale 恒为 1，
     * 字体（逻辑 9px）相对 427 宽的布局显得过大，标签会被 FaTheme.fit 大面积截断。
     */
    /** 绘制调用是否已失败过（只报一次，避免每帧刷屏）。 */
    private boolean drawFailureLogged;

    /**
     * 设备像素 / GUI 逻辑像素的倍率（= 游戏的 GUI 缩放）。
     *
     * <p>26.x 的尺寸语义与固定管线不同：{@code guiWidth()/guiHeight()} 返回的是**GUI 缩放后**的
     * 逻辑尺寸（例如 427x240），而真实帧缓冲是 2560x1600。{@code UiBackend.scale()} 默认返回 1，
     * 于是字形图集按"1 设备像素/GUI 像素"栅格，游戏再把它放大 3~6 倍 → **文字必然发糊**。
     * 这里用「帧缓冲宽 / 逻辑宽」算出真实倍率，栅格密度才是对的。
     */
    private int guiScale = 1;
    /** 已解析的 {@code Minecraft#getWindow}（惰性，解析一次）。 */
    private Method windowGetter;
    /** 已解析的 {@code Window#getWidth}（帧缓冲物理宽）。 */
    private Method windowWidth;
    /** 已解析的 {@code Window#getHeight}。 */
    private Method windowHeight;
    /** 已解析的 {@code Window#getGuiScaledWidth}（1.20+：GuiGraphics 没有尺寸方法时用）。 */
    private Method guiScaledWidth;
    /** 已解析的 {@code Window#getGuiScaledHeight}。 */
    private Method guiScaledHeight;
    /** 窗口句柄解析是否已尝试过（失败也只试一次，避免每帧刷日志）。 */
    private boolean windowProbeResolved;
    /** 是否已就字体缺失报过一次。 */
    private boolean fontMissingLogged;
    /** 是否已打过首帧尺寸日志（只打一次）。 */
    private boolean firstFrameLogged;

    private ExtractorRenderer(Class<?> extractorType, Method fill, Method outline, Method enableScissor,
                              Method disableScissor, Method text, Method guiWidth, Method guiHeight,
                              Supplier<Object> minecraft, Method windowGetter,
                              Method guiScaledWidth, Method guiScaledHeight,
                              Method physicalWidth, Method physicalHeight,
                              Supplier<Object> fontSource,
                              dev.nocturne.client.mapping.Mapping mapping,
                              Method poseMethod, Method pushMatrix, Method popMatrix,
                              Method scaleMatrix, Method translateMatrix) {
        this.fill = fill;
        this.outline = outline;
        this.enableScissor = enableScissor;
        this.disableScissor = disableScissor;
        this.text = text;
        this.guiWidth = guiWidth;
        this.guiHeight = guiHeight;
        this.minecraft = minecraft;
        // 1.20–1.21：GuiGraphics 没有尺寸方法，窗口句柄与 GUI 缩放尺寸在绑定时就解析好
        // （运行期名字是 intermediary，构造后再按 Mojmap 名找必然落空）。
        this.windowGetter = windowGetter;
        this.guiScaledWidth = guiScaledWidth;
        this.guiScaledHeight = guiScaledHeight;
        this.windowWidth = physicalWidth;
        this.windowHeight = physicalHeight;
        this.windowProbeResolved = windowGetter != null;
        this.fontSource = fontSource;
        this.mapping = mapping;
        this.poseMethod = poseMethod;
        this.pushMatrix = pushMatrix;
        this.popMatrix = popMatrix;
        this.scaleMatrix = scaleMatrix;
        this.translateMatrix = translateMatrix;
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
     * 1.20–1.21 的浮点 UV blit。优先选择能分别表达「屏幕尺寸 / UV 区域尺寸」的 11 参重载：
     * {@code (id,x,y,w,h,u,v,regionW,regionH,texW,texH)}。9 参重载会让 w/h 同时决定
     * UV 区域，只能在两者相等时使用；高密度图集按一半尺寸绘制时会只采到字形的左上区域。
     */
    static Method blitWithFloatUv(Class<?> type, Class<?> identifier) {
        for (Method candidate : type.getMethods()) {
            Class<?>[] p = candidate.getParameterTypes();
            if (p.length == 11 && p[0] == identifier
                    && p[1] == int.class && p[2] == int.class && p[3] == int.class && p[4] == int.class
                    && p[5] == float.class && p[6] == float.class
                    && p[7] == int.class && p[8] == int.class && p[9] == int.class && p[10] == int.class
                    && candidate.getReturnType() == void.class) {
                return candidate;
            }
        }
        for (Method candidate : type.getMethods()) {
            Class<?>[] p = candidate.getParameterTypes();
            if (p.length == 9 && p[0] == identifier && p[1] == int.class && p[2] == int.class
                    && p[3] == float.class && p[4] == float.class && p[5] == int.class
                    && p[6] == int.class && p[7] == int.class && p[8] == int.class
                    && candidate.getReturnType() == void.class) {
                return candidate;
            }
        }
        return null;
    }

    /** {@code setColor(float,float,float,float)}；混淆环境下名字会变，按签名取。 */
    private static Method colorMethod(Class<?> type) {
        for (Method candidate : type.getMethods()) {
            Class<?>[] p = candidate.getParameterTypes();
            if (p.length == 4 && p[0] == float.class && p[1] == float.class
                    && p[2] == float.class && p[3] == float.class
                    && candidate.getReturnType() == void.class) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 绑定 26.x 的 13 参 {@code blit}。
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

    /** 26.x 是 {@code (String,int,int,boolean)}；1.20–1.21 是 {@code (int,int,boolean)}。 */
    static java.lang.reflect.Constructor<?> dynamicTextureConstructor(Class<?> owner) {
        java.lang.reflect.Constructor<?> found = constructor(owner, String.class, int.class,
                int.class, boolean.class);
        return found != null ? found : constructor(owner, int.class, int.class, boolean.class);
    }

    /**
     * 按映射表绑定 1.20–1.21 的 {@code GuiGraphics}。
     *
     * <p>与 26.x 的区别只在名字：运行期是 intermediary / SRG，必须查表。方法形状完全相同
     * （{@code fill(IIIII)} / {@code drawString(Font,String,int,int,int)} / {@code enableScissor(IIII)}）。
     *
     * @param type       {@code GuiGraphics} 类
     * @param mapping    当前映射表
     * @param minecraft  Minecraft 实例来源（字体与窗口尺寸）
     * @return 可用后端；名字全部对不上时返回 {@code null}
     */
    public static ExtractorRenderer bindMapped(Class<?> type,
                                               dev.nocturne.client.mapping.Mapping mapping,
                                               Supplier<Object> minecraft,
                                               Supplier<Object> fontSource) {
        if (type == null || mapping == null) {
            return null;
        }
        Method pose = byCandidates(type, mapping, "pose");
        Method pushMatrix = null;
        Method popMatrix = null;
        Method scaleMatrix = null;
        if (pose != null) {
            Class<?> matrix = pose.getReturnType();
            if (methodInHierarchy(matrix, "pushMatrix") != null) {
                // 1.21.11+：JOML Matrix3x2fStack（2D）。
                pushMatrix = methodInHierarchy(matrix, "pushMatrix");
                popMatrix = methodInHierarchy(matrix, "popMatrix");
                scaleMatrix = methodInHierarchy(matrix, "scale", float.class, float.class);
            } else {
                // 1.20–1.21.10：PoseStack（3D）；Fabric 运行期名是 method_22903/22909/22905。
                pushMatrix = methodInHierarchy(matrix, "pushPose");
                if (pushMatrix == null) {
                    pushMatrix = methodInHierarchy(matrix, "method_22903");
                }
                popMatrix = methodInHierarchy(matrix, "popPose");
                if (popMatrix == null) {
                    popMatrix = methodInHierarchy(matrix, "method_22909");
                }
                scaleMatrix = methodInHierarchy(matrix, "scale", float.class, float.class, float.class);
                if (scaleMatrix == null) {
                    scaleMatrix = methodInHierarchy(matrix, "method_22905",
                            float.class, float.class, float.class);
                }
            }
        }
        Method fill = byCandidates(type, mapping, "fill",
                int.class, int.class, int.class, int.class, int.class);
        Method text = byCandidates(type, mapping, "drawString",
                null, String.class, int.class, int.class, int.class);
        Method enableScissor = byCandidates(type, mapping, "enableScissor",
                int.class, int.class, int.class, int.class);
        Method disableScissor = byCandidates(type, mapping, "disableScissor");
        if (fill == null || text == null) {
            System.out.println("[nocturne] gui-graphics bind failed: fill=" + (fill != null)
                    + " text=" + (text != null) + " scissor=" + (enableScissor != null));
            return null;
        }
        Object mc = minecraft == null ? null : minecraft.get();
        Class<?> mcType = mc == null ? null : mc.getClass();
        Method windowGetter = mcType == null ? null
                : byCandidates(mcType, mapping, dev.nocturne.client.mapping.ClassType.MINECRAFT,
                        "getWindow");
        Class<?> windowType = windowGetter == null ? null : windowGetter.getReturnType();
        Method scaledWidth = windowType == null ? null
                : byCandidates(windowType, mapping, dev.nocturne.client.mapping.ClassType.WINDOW,
                        "getGuiScaledWidth");
        Method scaledHeight = windowType == null ? null
                : byCandidates(windowType, mapping, dev.nocturne.client.mapping.ClassType.WINDOW,
                        "getGuiScaledHeight");
        Method physicalWidth = windowType == null ? null
                : byCandidates(windowType, mapping, dev.nocturne.client.mapping.ClassType.WINDOW,
                        "getWidth");
        Method physicalHeight = windowType == null ? null
                : byCandidates(windowType, mapping, dev.nocturne.client.mapping.ClassType.WINDOW,
                        "getHeight");
        ExtractorRenderer renderer = new ExtractorRenderer(type, fill, null, enableScissor, disableScissor,
                text, null, null, minecraft, windowGetter, scaledWidth, scaledHeight,
                physicalWidth, physicalHeight, fontSource, mapping, pose, pushMatrix, popMatrix,
                scaleMatrix, null);
        // 1.20–1.21 的自定义纹理：类名在 Fabric 运行期是 intermediary（class_XXXX），
        // 方法名也是 method_XXXXX，因此按候选名解析，而不是沿用 26.x 的 Mojang 名。
        renderer.bindTextures(type.getClassLoader(), type);
        return renderer;
    }

    /**
     * 绑定 1.20–1.21 的自定义纹理支路。
     *
     * <p>Fabric 运行期是 intermediary：{@code DynamicTexture}/{@code TextureManager}/
     * {@code ResourceLocation} 的类名是 {@code class_1043}/{@code class_1060}/{@code class_2960}，
     * 方法名是 {@code method_xxxxx}；而 {@code com.mojang.blaze3d.*} 不参与重映射
     * （{@code NativeImage} 名字稳定）。因此每个句柄都给「Mojang 名 → intermediary 名」候选。
     *
     * <p>绘制入口按优先级选：带 RenderPipeline 的 13 参 {@code blit}（26.x，可调制颜色）、
     * 1.21.6+ 的 {@code blit(Identifier,x,y,w,h,u0,v0,u1,v1)}、1.20–1.21.5 的
     * {@code blit(Identifier,x,y,u,v,w,h,texW,texH,color)}。后两者不需要 pipeline 实例
     * （{@code RenderPipelines.GUI_TEXTURED} 在 intermediary 下没有稳定名）。
     */
    private void bindTextures(ClassLoader loader, Class<?> extractorType) {
        if (System.getProperty("nocturne.ui.debug") != null) {
            System.out.println("[nocturne] bindTextures loader=" + loader
                    + " probeTM=" + Reflect.loadWithoutInit("net.minecraft.class_1060", loader)
                    + " probeNI=" + Reflect.loadWithoutInit("com.mojang.blaze3d.platform.NativeImage", loader)
                    + " extractor=" + extractorType.getName());
        }
        Class<?> identifier = loadAny(loader, "net.minecraft.resources.Identifier",
                "net.minecraft.resources.ResourceLocation", "net.minecraft.class_2960");
        Class<?> textureManager = loadAny(loader,
                "net.minecraft.client.renderer.texture.TextureManager", "net.minecraft.class_1060");
        Class<?> abstractTexture = loadAny(loader,
                "net.minecraft.client.renderer.texture.AbstractTexture", "net.minecraft.class_1044");
        Class<?> dynamicTexture = loadAny(loader,
                "net.minecraft.client.renderer.texture.DynamicTexture", "net.minecraft.class_1043");
        Class<?> minecraft = loadAny(loader, "net.minecraft.client.Minecraft", "net.minecraft.class_310");
        Class<?> renderSystem = loadAny(loader, "com.mojang.blaze3d.systems.RenderSystem");
        enableBlend = methodAny(renderSystem, new String[]{"enableBlend"});
        defaultBlendFunc = methodAny(renderSystem, new String[]{"defaultBlendFunc"});
        // Fabric 1.21.x 连 blaze3d 包一起重映射：NativeImage 运行期是 class_1011。
        Class<?> nativeImage = loadAny(loader, "com.mojang.blaze3d.platform.NativeImage",
                "net.minecraft.class_1011");
        if (identifier == null || textureManager == null || abstractTexture == null
                || dynamicTexture == null || minecraft == null || nativeImage == null) {
            reportTextureSupport();
            return;
        }
        blitColored = blitWithFloatUv(extractorType, identifier);
        setColorMethod = colorMethod(extractorType);
        if (newIdentifier == null && identifierCtor == null) {
            newIdentifier = methodAny(identifier, new String[]{"fromNamespaceAndPath", "method_60655"},
                    String.class, String.class);
            if (newIdentifier == null) {
                try {
                    identifierCtor = identifier.getConstructor(String.class, String.class);
                } catch (Throwable ignored) {
                    // 1.21 之前才有 public 构造；拿不到就说明连 Identifier 都造不出来。
                }
            }
        }
        if (registerTexture == null) {
            // 1.21.11 起 TextureManager 的注册入口是 register(Identifier, TextureContents)，
            // 注册时就把 NativeImage 交给 GPU；旧的 register(Identifier, AbstractTexture) +
            // DynamicTexture.upload() 在这版上不再产生可采样纹理（实测：上传成功、采样全透明）。
            for (Method candidate : textureManager.getMethods()) {
                Class<?>[] parameters = candidate.getParameterTypes();
                if (parameters.length == 2 && parameters[0] == identifier
                        && parameters[1].isAssignableFrom(dynamicTexture)
                        && !parameters[1].equals(abstractTexture)) {
                    registerTexture = candidate;
                    registerUploads = true;
                    break;
                }
            }
        }
        if (registerTexture == null) {
            registerTexture = methodAny(textureManager, new String[]{"register", "method_4616"},
                    identifier, abstractTexture);
        }
        if (releaseTexture == null) {
            releaseTexture = methodAny(textureManager, new String[]{"release", "method_4615"},
                    identifier);
        }
        if (getTextureManager == null) {
            getTextureManager = methodAny(minecraft,
                    new String[]{"getTextureManager", "method_1531"});
        }
        if (newDynamicTexture == null) {
            newDynamicTexture = dynamicTextureConstructor(dynamicTexture);
        }
        if (getPixels == null) {
            getPixels = methodAny(dynamicTexture, new String[]{"getPixels", "method_4525"});
        }
        if (setPixels == null) {
            setPixels = methodAny(dynamicTexture, new String[]{"setPixels", "method_4526"}, nativeImage);
        }
        if (uploadTexture == null) {
            uploadTexture = methodAny(dynamicTexture, new String[]{"upload", "method_4524"});
        }
        if (textureFilter == null) {
            for (String name : new String[]{"setFilter", "method_4308"}) {
                try {
                    Method candidate = nativeImage.getDeclaredMethod(name, boolean.class, boolean.class);
                    candidate.setAccessible(true);
                    textureFilter = candidate;
                    break;
                } catch (Throwable ignored) {
                    // 没有该入口时保持 DynamicTexture 的默认最近邻过滤。
                }
            }
        }
        if (closeTexture == null) {
            closeTexture = methodAny(abstractTexture, new String[]{"close"});
        }
        if (setPixel == null) {
            setPixel = methodAny(nativeImage, new String[]{"setPixel", "method_4305"},
                    int.class, int.class, int.class);
        }
        if (getPointer == null) {
            getPointer = methodAny(nativeImage, new String[]{"getPointer"});
        }
        if (getPointer == null && pointerField == null) {
            // 1.21.1 没有公开 getPointer。NativeImage 里有两个 long：final 的是 size，
            // 非 final 的才是像素地址。按这一结构取值，避免把 Forge SRG 字段名写死。
            for (Field candidate : nativeImage.getDeclaredFields()) {
                if (candidate.getType() != long.class
                        || java.lang.reflect.Modifier.isFinal(candidate.getModifiers())) {
                    continue;
                }
                try {
                    candidate.setAccessible(true);
                    pointerField = candidate;
                    break;
                } catch (Throwable ignored) {
                    // 模块限制下退回 applyToPixels + setPixels；不能伪装成写入成功。
                }
            }
        }
        if (applyPixels == null) {
            applyPixels = methodAny(nativeImage, new String[]{"applyToPixels", "method_48462"},
                    java.util.function.IntUnaryOperator.class);
        }
        // 无 pipeline 的绘制重载：按参数形状匹配（名字在 intermediary 下是 method_xxxxx）。
        for (Method candidate : extractorType.getMethods()) {
            Class<?>[] parameters = candidate.getParameterTypes();
            if (parameters.length < 5 || parameters[0] != identifier) {
                continue;
            }
            if (parameters.length == 9 && parameters[1] == int.class && parameters[2] == int.class
                    && parameters[3] == int.class && parameters[4] == int.class
                    && parameters[5] == float.class && parameters[6] == float.class
                    && parameters[7] == float.class && parameters[8] == float.class) {
                blitSimple = candidate;
            } else if (parameters.length == 10 && allInts(parameters, 1, 9)) {
                blitPixel = candidate;
            }
        }
        // 1.21.6+ 的 TextureManager 只接受 ReloadableTexture（抽象类，必须由 Java 子类实现
        // loadContents），反射构造不出来；DynamicTexture 在这版上不再产生可采样纹理。
        // 与其让文字全部消失，不如关掉纹理支路、回到游戏字体 + ASCII 图标。
        Class<?> reloadable = loadAny(loader,
                "net.minecraft.client.renderer.texture.ReloadableTexture", "net.minecraft.class_10537");
        boolean modernTextureApi = false;
        if (reloadable != null) {
            for (Method candidate : textureManager.getMethods()) {
                Class<?>[] parameters = candidate.getParameterTypes();
                if (parameters.length == 2 && parameters[0] == identifier
                        && parameters[1] == reloadable) {
                    modernTextureApi = true;
                    break;
                }
            }
        }
        boolean drawable = blit != null || blitSimple != null || blitPixel != null
                || blitColored != null;
        boolean directWrite = memByteBuffer != null && (getPointer != null || pointerField != null);
        textureSupport = !modernTextureApi && drawable && (newIdentifier != null || identifierCtor != null)
                && registerTexture != null && getTextureManager != null && newDynamicTexture != null
                && getPixels != null && uploadTexture != null
                && (directWrite || setPixel != null || (applyPixels != null && setPixels != null));
        reportTextureSupport();
    }

    /** @return {@code [from, to)} 区间的参数是否全是 {@code int} */
    private static boolean allInts(Class<?>[] parameters, int from, int to) {
        for (int i = from; i < to; i++) {
            if (parameters[i] != int.class) {
                return false;
            }
        }
        return true;
    }

    /** 按候选名加载类；全部失败返回 {@code null}。 */
    private static Class<?> loadAny(ClassLoader loader, String... names) {
        for (String name : names) {
            Class<?> type = Reflect.loadWithoutInit(name, loader);
            if (type != null) {
                return type;
            }
        }
        return null;
    }

    /** 按候选名找方法；全部失败返回 {@code null}。 */
    private static Method methodAny(Class<?> owner, String[] names, Class<?>... parameterTypes) {
        if (owner == null) {
            return null;
        }
        for (String name : names) {
            Method found = Reflect.method(owner, name, parameterTypes);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** 打印纹理支路的能力（缺陷排查时最需要的一行）；可用时不打印。 */
    private void reportTextureSupport() {
        if (textureSupport) {
            return;
        }
        System.out.println("[nocturne] extractor backend: custom textures unavailable"
                + " (pipeline=" + (guiTexturedPipeline != null)
                + " blit=" + (blit != null)
                + " blitSimple=" + (blitSimple != null)
                + " blitPixel=" + (blitPixel != null)
                + " register=" + (registerTexture != null)
                + " textureManager=" + (getTextureManager != null)
                + " dynamicTexture=" + (newDynamicTexture != null)
                + " pixels=" + (getPixels != null)
                + " upload=" + (uploadTexture != null)
                + " setPixel=" + (setPixel != null)
                + " applyPixels=" + (applyPixels != null)
                + " identifier=" + (newIdentifier != null || identifierCtor != null)
                + "); glyphs fall back to the game font and icons to ASCII");
    }

    /** 沿继承链查找方法（JOML 的矩阵方法多定义在父类上）。 */
    private static Method methodInHierarchy(Class<?> type, String name, Class<?>... parameterTypes) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            Method found = Reflect.method(current, name, parameterTypes);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** 按映射表的方法名候选逐个尝试。 */
    private static Method byCandidates(Class<?> owner,
                                       dev.nocturne.client.mapping.Mapping mapping,
                                       String canonicalName, Class<?>... parameterTypes) {
        return byCandidates(owner, mapping, dev.nocturne.client.mapping.ClassType.GUI_GRAPHICS,
                canonicalName, parameterTypes);
    }

    private static Method byCandidates(Class<?> owner,
                                       dev.nocturne.client.mapping.Mapping mapping,
                                       dev.nocturne.client.mapping.ClassType type,
                                       String canonicalName, Class<?>... parameterTypes) {
        for (dev.nocturne.client.mapping.MethodCandidate candidate
                : mapping.methodCandidates(type, canonicalName)) {
            Method found = method(owner, candidate.name(), parameterTypes);
            if (found == null) {
                continue;
            }
            // 混淆环境大量无参方法共用 a/b/c；只匹配参数会拿到同名但返回类型无关的方法。
            // 名字与描述符必须来自同一命名空间，并用描述符核对返回值。
            if (candidate.descriptor() != null) {
                Class<?> expected = dev.nocturne.client.game.JniTypes.returnType(
                        candidate.descriptor(), owner.getClassLoader());
                if (expected == null || found.getReturnType() != expected) {
                    continue;
                }
            }
            return found;
        }
        return null;
    }

    /**
     * 只按参数类型找方法（忽略名字），用于 1.20–1.21 的混淆名环境。
     *
     * <p>{@code parameterTypes} 中的 {@code null} 表示「任意引用类型」。
     *
     * @return 第一个匹配的公开方法；没有则为 {@code null}
     */
    private static Method methodByTypes(Class<?> owner, Class<?>... parameterTypes) {
        if (owner == null) {
            return null;
        }
        for (Method candidate : owner.getMethods()) {
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
                    // 公开方法无需 setAccessible。
                }
                return candidate;
            }
        }
        return null;
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
        // 26.x：方法未混淆，按名字取。1.20–1.21：运行期是 intermediary / SRG 名，
        // 名字对不上，改按参数类型回退（fill 与 outline 同为 (IIIII)V，仅在按名失败时才用签名）。
        Method fill = method(extractorType, "fill", int.class, int.class, int.class, int.class, int.class);
        if (fill == null) {
            fill = methodByTypes(extractorType, int.class, int.class, int.class, int.class, int.class);
        }
        // 26.x 叫 text(Font,String,int,int,int)；1.20–1.21 的 GuiGraphics 叫 drawString(Font,...)，
        // 两者参数形态一致，只是名字不同——按候选列表取，命中即用。
        Method text = method(extractorType, "text", null, String.class, int.class, int.class, int.class);
        if (text == null) {
            text = method(extractorType, "drawString", null, String.class, int.class, int.class, int.class);
        }
        if (text == null) {
            text = methodByTypes(extractorType, null, String.class, int.class, int.class, int.class);
        }
        // 26.x 有 guiWidth/guiHeight；1.20–1.21 的 GuiGraphics 没有尺寸方法，回退到 Window 的
        // guiScaledWidth/Height（见 beginFrame）。
        Method guiWidth = method(extractorType, "guiWidth");
        Method guiHeight = method(extractorType, "guiHeight");
        if (fill == null || text == null) {
            return null;
        }
        // 可选项：缺了也能画，只是退化（描边自拼、不裁剪）。
        Method outline = method(extractorType, "outline", int.class, int.class, int.class, int.class, int.class);
        Method enableScissor = method(extractorType, "enableScissor", int.class, int.class, int.class, int.class);
        if (enableScissor == null) {
            enableScissor = methodByTypes(extractorType, int.class, int.class, int.class, int.class);
        }
        Method disableScissor = method(extractorType, "disableScissor");
        Method pose = method(extractorType, "pose");
        Method pushMatrix = null;
        Method popMatrix = null;
        Method scaleMatrix = null;
        Method translateMatrix = null;
        if (pose != null) {
            Class<?> matrix = pose.getReturnType();
            pushMatrix = methodInHierarchy(matrix, "pushMatrix");
            popMatrix = methodInHierarchy(matrix, "popMatrix");
            scaleMatrix = methodInHierarchy(matrix, "scale", float.class, float.class);
            translateMatrix = methodInHierarchy(matrix, "translate", float.class, float.class);
        }
        return new ExtractorRenderer(extractorType, fill, outline, enableScissor, disableScissor, text,
                guiWidth, guiHeight, minecraft, null, null, null, null, null,
                null, null, pose, pushMatrix, popMatrix, scaleMatrix, translateMatrix);
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
    public boolean drawPlayer(Object player, float left, float top, float right, float bottom,
                              float mouseX, float mouseY) {
        Object graphics = frame;
        if (graphics == null || player == null || right <= left || bottom <= top) {
            return false;
        }
        if (!previewAttempted) {
            dev.nocturne.client.NocturneClient client = dev.nocturne.client.NocturneClient.get();
            dev.nocturne.client.game.GameBridge bridge = client == null ? null : client.gameBridge();
            if (bridge != null) {
                playerPreview = InventoryPlayerPreview.bind(bridge.mapping(),
                        bridge.gameLoader(), graphics.getClass());
                previewAttempted = true;
                if (playerPreview == null) {
                    System.out.println("[nocturne] native inventory player preview unavailable for "
                            + graphics.getClass().getName());
                }
            }
        }
        InventoryPlayerPreview preview = playerPreview;
        return preview != null && preview.draw(graphics, player, left, top, right, bottom, mouseX, mouseY);
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
        // 1.20–1.21 的 GuiGraphics 没有尺寸方法：回退到 Window 的 GUI 缩放尺寸。
        int measuredWidth = intOf(guiWidth, graphics, 0);
        int measuredHeight = intOf(guiHeight, graphics, 0);
        if (measuredWidth <= 0 || measuredHeight <= 0) {
            int[] scaled = guiScaledSize();
            if (scaled != null) {
                updateUiScale(scaled[0]);
            }
            if (scaled != null) {
                if (measuredWidth <= 0) {
                    measuredWidth = scaled[0];
                }
                if (measuredHeight <= 0) {
                    measuredHeight = scaled[1];
                }
            }
        }
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
        updateGuiScale();
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
    public int scale() {
        return guiScale;
    }

    @Override
    public int rasterScale() {
        // 1.20–1.21 的 11 参 blit 能独立表达屏幕尺寸和 UV 区域，且上传后使用线性滤波。
        // 仅这条路径做 2× 超采样；26.x 与其他后端保持原密度，避免未验收的行为变化。
        return blitColored == null ? scale() : Math.max(1, guiScale * 2);
    }

    /**
     * 用「帧缓冲物理宽 / GUI 逻辑宽」算出真实设备倍率。
     *
     * <p>只在两边都有效时更新；拿不到窗口句柄时保持 1（退化成旧的模糊渲染，不会崩）。
     */
    private void updateGuiScale() {
        if (!windowProbeResolved) {
            windowProbeResolved = true;
            Object mc = minecraft == null ? null : minecraft.get();
            if (mc != null) {
                windowGetter = mappedMethod(mc.getClass(),
                        dev.nocturne.client.mapping.ClassType.MINECRAFT, "getWindow");
                Object window = null;
                if (windowGetter != null) {
                    window = Reflect.call(windowGetter, mc);
                }
                if (window != null) {
                    windowWidth = mappedMethod(window.getClass(),
                            dev.nocturne.client.mapping.ClassType.WINDOW, "getWidth");
                    windowHeight = mappedMethod(window.getClass(),
                            dev.nocturne.client.mapping.ClassType.WINDOW, "getHeight");
                    guiScaledWidth = mappedMethod(window.getClass(),
                            dev.nocturne.client.mapping.ClassType.WINDOW, "getGuiScaledWidth");
                    guiScaledHeight = mappedMethod(window.getClass(),
                            dev.nocturne.client.mapping.ClassType.WINDOW, "getGuiScaledHeight");
                }
            }
        }
        if (windowWidth == null || width <= 0) {
            return;
        }
        if (minecraft == null || windowGetter == null) {
            return;
        }
        Object mc = minecraft.get();
        if (mc == null) {
            return;
        }
        Object window = Reflect.call(windowGetter, mc);
        if (window == null) {
            return;
        }
        int physical = intOf(windowWidth, window, 0);
        if (physical <= 0) {
            return;
        }
        int factor = Math.round((float) physical / (float) width);
        guiScale = Math.max(1, Math.min(16, factor));
    }

    /**
     * 读窗口的 GUI 缩放尺寸（逻辑像素）。
     *
     * <p>1.20–1.21 的 {@code GuiGraphics} 不提供尺寸方法，而组件布局必须拿到逻辑尺寸，
     * 否则界面按 0 排版、一个像素都画不出来。
     *
     * @return {@code {width, height}}；窗口不可用时为 {@code null}
     */
    private int[] guiScaledSize() {
        if (!windowProbeResolved) {
            updateGuiScale();
        }
        // getGuiScaledHeight 不是必需的：缺失时用物理宽高比例换算（见下）。
        if (guiScaledWidth == null || minecraft == null || windowGetter == null) {
            return null;
        }
        Object mc = minecraft.get();
        if (mc == null) {
            return null;
        }
        Object window = Reflect.call(windowGetter, mc);
        if (window == null) {
            return null;
        }
        int scaledWidth = intOf(guiScaledWidth, window, 0);
        int scaledHeight = intOf(guiScaledHeight, window, 0);
        if (scaledWidth > 0 && scaledHeight <= 0) {
            // 没有 getGuiScaledHeight：用物理宽高比例换算（GUI 缩放是等比整数）。
            int physicalWidthValue = intOf(windowWidth, window, 0);
            int physicalHeightValue = intOf(windowHeight, window, 0);
            if (physicalWidthValue > 0 && physicalHeightValue > 0) {
                scaledHeight = Math.round(scaledWidth * physicalHeightValue / (float) physicalWidthValue);
            }
        }
        return scaledWidth > 0 && scaledHeight > 0 ? new int[]{scaledWidth, scaledHeight} : null;
    }

    /** 延迟解析也必须走映射；直接写 Mojmap 名在 Fabric 上只会得到 null。 */
    private Method mappedMethod(Class<?> owner, dev.nocturne.client.mapping.ClassType ownerType,
                                String canonicalName) {
        if (mapping != null) {
            return byCandidates(owner, mapping, ownerType, canonicalName);
        }
        return method(owner, canonicalName);
    }

    /**
     * 由「物理宽 / 逻辑宽」推算 GUI 缩放；只在拿到有效值时更新。
     *
     * @param logicalWidth 本帧的逻辑宽度
     */
    private void updateUiScale(int logicalWidth) {
        if (logicalWidth <= 0 || windowWidth == null || minecraft == null || windowGetter == null) {
            return;
        }
        Object mc = minecraft.get();
        if (mc == null) {
            return;
        }
        Object window = Reflect.call(windowGetter, mc);
        if (window == null) {
            return;
        }
        int physical = intOf(windowWidth, window, 0);
        if (physical <= 0) {
            return;
        }
        int factor = Math.round(physical / (float) logicalWidth);
        if (factor > 0) {
            guiScale = Math.max(1, Math.min(16, factor));
        }
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
        // 游戏字体是固定 9px；设计字号 size 可能更小。按 size/9 缩放矩阵，
        // 使「绘制出来的字宽」与 textWidth 返回的宽度一致（否则 1.21 的 427 逻辑空间里
        // 字体相对布局过大，标签会被 FaTheme.fit 大面积截断）。
        float factor = size > 0f ? size / NATIVE_FONT_PX : 1f;
        boolean scaled = Math.abs(factor - 1f) > 0.01f && poseMethod != null && pushMatrix != null
                && popMatrix != null && scaleMatrix != null;
        if (scaled) {
            Object pose = Reflect.call(poseMethod, graphics);
            if (pose != null) {
                Reflect.call(pushMatrix, pose);
                if (scaleMatrix.getParameterCount() == 2) {
                    Reflect.call(scaleMatrix, pose, factor, factor);
                } else {
                    Reflect.call(scaleMatrix, pose, factor, factor, 1.0f);
                }
                call(this.text, graphics, fontInstance, text,
                        Math.round(x / factor), Math.round(y / factor), color.argb);
                Reflect.call(popMatrix, pose);
                return;
            }
        }
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
        // 只有能缩放绘制的后端才同步缩放度量；26.x 没有 pose，保持原口径（否则度量与绘制会分叉）。
        float factor = size > 0f && poseMethod != null ? size / NATIVE_FONT_PX : 1f;
        return intOf(widthHandle, fontInstance, text, text.length() * 6) * factor;
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

    /** 当前实际采用的纹理绘制入口能否应用 ARGB 调制。 */
    @Override
    public boolean supportsTextureTint() {
        if (blit != null && guiTexturedPipeline != null) {
            return true;
        }
        if (blitColored != null) {
            return setColorMethod != null;
        }
        return blitSimple == null && blitPixel != null;
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
            diag("manager=null");
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
            Object identifier = createIdentifier("ui/atlas_" + index);
            Object texture = createTexture("nocturne/ui/atlas_" + index, width, height);
            if (identifier == null || texture == null) {
                diag("identifier=" + identifier + " texture=" + texture);
                return id;
            }
            target = new RegisteredTexture(identifier, texture, width, height);
            textures.add(target);
            id = index;
            if (!registerUploads && !writePixels(target, argb)) {
                diag("writePixels failed (pointer=" + (getPointer != null || pointerField != null)
                        + " apply=" + (applyPixels != null) + " setPixels=" + (setPixels != null)
                        + " setPixel=" + (setPixel != null) + ")");
                return id;
            }
            // 旧路径：注册必须在写入像素之前（blit 通过注册名解析纹理）。
            // 新路径（registerUploads）：注册动作自己取当前像素上传，因此必须排在写像素之后。
            Reflect.call(registerTexture, manager, identifier, texture);
            if (!registerUploads) {
                // 新纹理第一次只写了 NativeImage，GPU 端仍是空的；旧路径必须显式上传一次。
                upload(target);
            }
            diag("ok id=" + id + " " + target.width + "x" + target.height
                    + " registerUploads=" + registerUploads);
            return id;
        }
        if (registerUploads) {
            return id;
        }
        if (!writePixels(target, argb)) {
            diag("writePixels failed (pointer=" + (getPointer != null || pointerField != null)
                    + " apply=" + (applyPixels != null) + " setPixels=" + (setPixels != null)
                    + " setPixel=" + (setPixel != null) + ")");
            return id;
        }
        upload(target);
        diag("ok id=" + id + " " + target.width + "x" + target.height);
        return id;
    }

    /** 上传当前 NativeImage，并恢复该纹理使用的滤波参数。 */
    private void upload(RegisteredTexture target) {
        Reflect.call(uploadTexture, target.texture);
        if (textureFilter != null) {
            // DynamicTexture.upload 固定 blur=false；这里仅对 1.20–1.21 的图集启用线性采样。
            Reflect.call(textureFilter, null, true, false);
        }
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
            quadDiag("missing tex=" + texture + " size=" + textures.size());
            return;
        }
        quadDiag("tex=" + texture + " " + bx0(x) + "," + by0(y) + " " + width + "x" + height
                + " uv=" + u0 + "," + v0 + ".." + u1 + "," + v1
                + " color=" + Integer.toHexString(argb));
        // UV 由归一化坐标换算回纹素；整数位置与尺寸仅供没有矩阵能力的后端回退使用。
        int bx = Math.round(x);
        int by = Math.round(y);
        int bw = Math.max(1, Math.round(width));
        int bh = Math.max(1, Math.round(height));
        float uStart = u0 * target.width;
        float vStart = v0 * target.height;
        int uSpan = Math.round((u1 - u0) * target.width);
        int vSpan = Math.round((v1 - v0) * target.height);
        if (blit != null && guiTexturedPipeline != null) {
            // 26.x 的 blit 只收整数坐标；逐字形取整在高 GUI 倍率下会放大成不均匀字距。
            // 用单位四边形 + 2D 矩阵保留原始浮点几何；innerBlit 会复制当前矩阵，因此随后弹栈安全。
            if (poseMethod != null && pushMatrix != null && popMatrix != null
                    && translateMatrix != null && scaleMatrix != null) {
                Object pose = Reflect.call(poseMethod, graphics);
                if (pose != null) {
                    Reflect.call(pushMatrix, pose);
                    try {
                        Reflect.call(translateMatrix, pose, x, y);
                        Reflect.call(scaleMatrix, pose, width, height);
                        call(blit, graphics, guiTexturedPipeline, target.identifier,
                                0, 0, uStart, vStart, 1, 1, uSpan, vSpan,
                                target.width, target.height, argb);
                    } finally {
                        Reflect.call(popMatrix, pose);
                    }
                    return;
                }
            }
            call(blit, graphics, guiTexturedPipeline, target.identifier,
                    bx, by, uStart, vStart, bw, bh, uSpan, vSpan, target.width, target.height, argb);
            return;
        }
        // 1.20–1.21：使用浮点 UV 的 blit + setColor；先前的 10-int 形状会误中 blitSprite，
        // 纹理虽然上传成功，字形区域却按精灵图解释，屏幕上不出现文字。
        if (blitColored != null) {
            prepareTextureBlend();
            if (setColorMethod != null) {
                // 图集是白化字形（RGB=白、A=覆盖度），颜色由 setColor 调制。
                call(setColorMethod, graphics,
                        ((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f,
                        (argb & 0xFF) / 255f, ((argb >>> 24) & 0xFF) / 255f);
            }
            if (blitColored.getParameterCount() == 11) {
                call(blitColored, graphics, target.identifier, bx, by, bw, bh, uStart, vStart,
                        uSpan, vSpan, target.width, target.height);
            } else {
                call(blitColored, graphics, target.identifier, bx, by, uStart, vStart,
                        bw, bh, target.width, target.height);
            }
            if (setColorMethod != null) {
                call(setColorMethod, graphics, 1f, 1f, 1f, 1f);
            }
            return;
        }
        // 1.21.6+：UV 归一化、无调制色（我们的图集是白化字形，白色即所需颜色）。
        if (blitSimple != null) {
            prepareTextureBlend();
            call(blitSimple, graphics, target.identifier, bx, by, bw, bh, u0, v0, u1, v1);
            return;
        }
        // 1.20–1.21.5：(Identifier, x, y, u, v, w, h, texW, texH, color)，UV 为纹素。
        if (blitPixel != null) {
            prepareTextureBlend();
            call(blitPixel, graphics, target.identifier, bx, by,
                    Math.round(uStart), Math.round(vStart), bw, bh, target.width, target.height, argb);
        }
    }

    /** 旧 GuiGraphics.blit 不管混合状态；白化图集必须按非预乘 alpha 混合。 */
    private void prepareTextureBlend() {
        Reflect.call(enableBlend, null);
        Reflect.call(defaultBlendFunc, null);
    }

    /** 造一个 {@code ResourceLocation}/{@code Identifier}；两种构造方式都失败时返回 {@code null}。 */
    private Object createIdentifier(String path) {
        if (newIdentifier != null) {
            Object identifier = Reflect.call(newIdentifier, null, NAMESPACE, path);
            if (identifier != null) {
                return identifier;
            }
        }
        if (identifierCtor != null) {
            try {
                return identifierCtor.newInstance(NAMESPACE, path);
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    /** 纹理上传诊断（仅 -Dnocturne.ui.debug，前 4 次）。 */
    private void diag(String message) {
        if (System.getProperty("nocturne.ui.debug") == null || uploadDiag >= 4) {
            return;
        }
        uploadDiag++;
        System.out.println("[nocturne] extractor upload: " + message);
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
            // 26.x 构造带名字；1.20–1.21 只有 (int,int,boolean)。
            Object texture = newDynamicTexture.getParameterCount() == 4
                    ? newDynamicTexture.newInstance(name, width, height, Boolean.TRUE)
                    : newDynamicTexture.newInstance(width, height, Boolean.TRUE);
            installLinearSampler(texture);
            return texture;
        } catch (Throwable t) {
            if (!textureFailureLogged) {
                textureFailureLogged = true;
                System.out.println("[nocturne] extractor backend: DynamicTexture creation failed: " + t);
            }
            return null;
        }
    }

    /**
     * 26.x 的 {@code DynamicTexture} 默认使用最近邻采样；高密度字形经过 GUI 矩阵绘制时会产生锯齿。
     * 游戏没有公开的滤波 setter，但纹理的采样器字段可替换为同一个缓存里的线性实例。
     * 旧版本缺少这些类时保持原有 {@code NativeImage#setFilter} 路径，不影响其既有行为。
     */
    private void installLinearSampler(Object texture) {
        if (texture == null) {
            return;
        }
        if (!samplerResolved) {
            samplerResolved = true;
            ClassLoader loader = texture.getClass().getClassLoader();
            Class<?> filterMode = Reflect.loadWithoutInit(
                    "com.mojang.renderpearl.api.textures.FilterMode", loader);
            if (filterMode == null) {
                filterMode = Reflect.loadWithoutInit("com.mojang.blaze3d.textures.FilterMode", loader);
            }
            Class<?> renderSystem = Reflect.loadWithoutInit("com.mojang.blaze3d.systems.RenderSystem", loader);
            Class<?> samplerCache = Reflect.loadWithoutInit("com.mojang.blaze3d.systems.SamplerCache", loader);
            Method cacheMethod = Reflect.method(renderSystem, "getSamplerCache");
            Method clampMethod = Reflect.method(samplerCache, "getClampToEdge", filterMode);
            Object linear = Reflect.staticField(filterMode, "LINEAR");
            Object cache = Reflect.call(cacheMethod, null);
            linearSampler = Reflect.call(clampMethod, cache, linear);
            try {
                Class<?> abstractTexture = Reflect.loadWithoutInit(
                        "net.minecraft.client.renderer.texture.AbstractTexture", loader);
                samplerField = abstractTexture == null ? null : abstractTexture.getDeclaredField("sampler");
                if (samplerField != null) {
                    samplerField.setAccessible(true);
                }
            } catch (Throwable ignored) {
                samplerField = null;
            }
            if (linearSampler == null) {
                samplerField = null;
            }
            if (System.getProperty("nocturne.ui.debug") != null) {
                System.out.println("[nocturne] extractor sampler: linear=" + (linearSampler != null)
                        + " writable=" + (samplerField != null));
            }
        }
        if (linearSampler == null || samplerField == null) {
            return;
        }
        try {
            // ClampToEdge：图集边缘不能按默认 REPEAT 绕到另一端，否则线性采样会串色。
            samplerField.set(texture, linearSampler);
        } catch (Throwable ignored) {
            // 能力不可写时保留游戏默认采样器，不影响纹理上传和绘制。
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
        if (memByteBuffer != null) {
            long address = 0L;
            if (getPointer != null) {
                Object pointer = Reflect.call(getPointer, pixels);
                if (pointer instanceof Number) {
                    address = ((Number) pointer).longValue();
                }
            } else if (pointerField != null) {
                try {
                    address = pointerField.getLong(pixels);
                } catch (Throwable ignored) {
                    // 继续走会替换 NativeImage 的回退，不碰无效地址。
                }
            }
            if (address != 0L) {
                Object buffer = Reflect.call(memByteBuffer, null, address, count * 4);
                if (buffer instanceof java.nio.ByteBuffer) {
                    java.nio.ByteBuffer view = ((java.nio.ByteBuffer) buffer)
                            .order(java.nio.ByteOrder.nativeOrder());
                    view.asIntBuffer().put(pixelScratch, 0, count);
                    return true;
                }
            }
        }
        // applyToPixels 在 1.21.1 会创建新 NativeImage；必须替换进 DynamicTexture。
        // 直接修改返回数组同样是错的：getPixelsArray/makePixelArray 返回的也是副本。
        if (applyPixels != null && setPixels != null) {
            final int[] source = pixelScratch;
            final int[] cursor = {0};
            java.util.function.IntUnaryOperator writer = value ->
                    cursor[0] < count ? source[cursor[0]++] : value;
            Object result = Reflect.call(applyPixels, pixels, writer);
            if (result != null && cursor[0] == count) {
                if (result != pixels) {
                    Reflect.call(setPixels, target.texture, result);
                }
                return true;
            }
        }
        // 最后退到逐像素 setPixel（1M 次反射，慢但正确）。
        if (setPixel != null) {
            int width = target.width;
            for (int i = 0; i < count; i++) {
                Reflect.call(setPixel, pixels, i % width, i / width, pixelScratch[i]);
            }
            return true;
        }
        return false;
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
            if (disableScissor != null) {
                call(disableScissor, graphics);
            } else if (enableScissor != null) {
                if (width <= 0 || height <= 0) {
                    return;
                }
                // 1.20–1.21 只有 enableScissor：用整个绘制区复位裁剪，
                // 否则裁剪状态会残留到游戏自己的绘制上（表现为整片黑）。
                call(enableScissor, graphics, 0, 0, width, height);
            }
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
        Object candidate = fontSource != null ? fontSource.get() : fieldValue(instance, "font");
        if (candidate == null) {
            if (!fontMissingLogged) {
                fontMissingLogged = true;
                System.out.println("[nocturne] extractor backend: Minecraft has no readable 'font' field;"
                        + " the overlay will draw without text");
            }
            return;
        }
        font = candidate;
        if (mapping != null) {
            // Font.width(String)：Fabric 运行期是 method_1727，硬编码 "width" 会度量失败，
            // 组件按 0 宽度布局，标签与数值就会叠在一起。
            Method mappedWidth = byCandidates(candidate.getClass(), mapping,
                    dev.nocturne.client.mapping.ClassType.FONT, "width", String.class);
            if (mappedWidth != null) {
                fontWidth = mappedWidth;
            }
        }
        fontWidth = fontWidth != null ? fontWidth : method(candidate.getClass(), "width", String.class);
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
                        + "): " + t + "; declaring=" + method.getDeclaringClass().getName()
                        + "; target=" + (target == null ? "null" : target.getClass().getName()));
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
