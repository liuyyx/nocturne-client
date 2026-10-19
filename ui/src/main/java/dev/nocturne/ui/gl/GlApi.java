package dev.nocturne.ui.gl;

import dev.nocturne.client.game.Reflect;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/**
 * 对 LWJGL 固定管线 {@code GL11} 的反射绑定。
 *
 * <p>编译期无法链接 LWJGL（注入器运行在操作者的 JVM 上，而非游戏进程内），
 * 因此每个调用都通过针对游戏类加载器解析出的方法句柄转发。
 *
 * <p>只绑定 UI 所需的立即模式（immediate mode）子集；GL 枚举以内联常量给出——
 * 它们属于 OpenGL ABI，永不改变。
 *
 * <p>在架构中的位置：{@link GlRenderer} 依赖本类完成 1.8.9 / LWJGL2 时代的绘制；
 * 它与 {@link ModernGlApi} 并列，构成跨越两代管线的底层 GL 抽象，使 UI 层无需在编译期依赖 LWJGL。
 */
public final class GlApi {

    /** glBegin 图元模式：独立线段。 */
    public static final int GL_LINES = 1;
    /** glBegin 图元模式：首尾相连的闭合线段环。 */
    public static final int GL_LINE_LOOP = 2;
    /** glBegin 图元模式：三角扇，用于圆角等扇形几何。 */
    public static final int GL_TRIANGLE_FAN = 6;
    /** glBegin 图元模式：四边形（固定管线专有，核心 profile 已移除）。 */
    public static final int GL_QUADS = 7;
    /** glEnable/glDisable 的混合（alpha blending）开关。 */
    public static final int GL_BLEND = 3042;
    /** 混合因子：源颜色。 */
    public static final int GL_SRC_ALPHA = 770;
    /** 混合因子：1 - 源 alpha。 */
    public static final int GL_ONE_MINUS_SRC_ALPHA = 771;
    /** 线段抗锯齿开关。 */
    public static final int GL_LINE_SMOOTH = 2848;
    /** 2D 纹理目标 / 纹理开关。 */
    public static final int GL_TEXTURE_2D = 3553;
    /** 背面剔除开关。 */
    public static final int GL_CULL_FACE = 2884;
    /** {@code glMatrixMode} 的目标：投影矩阵。 */
    public static final int GL_PROJECTION = 5889;
    /** {@code glMatrixMode} 的目标：模型视图矩阵。 */
    public static final int GL_MODELVIEW = 5888;
    /** 深度测试开关；绘制 UI 时必须关闭，否则后画的元素会被先画的遮挡。 */
    public static final int GL_DEPTH_TEST = 2929;
    /** {@code glGetIntegerv} 的查询目标：当前视口，结果为 {@code {x, y, width, height}}。 */
    public static final int GL_VIEWPORT = 2978;
    /** {@code glPushAttrib}/{@code glPopAttrib} 的掩码：全部属性位（固定管线专属）。 */
    public static final int GL_ALL_ATTRIB_BITS = 0x000FFFFF;
    /** 裁剪测试开关，配合 {@code glScissor} 实现矩形裁剪。 */
    public static final int GL_SCISSOR_TEST = 3089;
    /** {@code glTexParameteri} 的过滤参数：缩小过滤。 */
    public static final int GL_TEXTURE_MIN_FILTER = 10241;
    /** {@code glTexParameteri} 的过滤参数：放大过滤。 */
    public static final int GL_TEXTURE_MAG_FILTER = 10240;
    /** 过滤模式：最近邻；UI 字形与图标都按像素尺寸栅格，线性过滤只会糊掉。 */
    public static final int GL_NEAREST = 9728;
    /** 纹理环绕参数：S 轴。 */
    public static final int GL_TEXTURE_WRAP_S = 10242;
    /** 纹理环绕参数：T 轴。 */
    public static final int GL_TEXTURE_WRAP_T = 10243;
    /** 环绕模式：夹取到边缘；图集边缘采样越界会串到相邻字形。 */
    public static final int GL_CLAMP_TO_EDGE = 33071;
    /** 纹理像素通道布局：RGBA。 */
    public static final int GL_RGBA = 6408;
    /** 像素分量类型：每通道 8 位无符号。 */
    public static final int GL_UNSIGNED_BYTE = 5121;
    /**
     * {@code glGetIntegerv} 的参数：当前绑定的 2D 纹理句柄（{@code GL_TEXTURE_BINDING_2D}）。
     *
     * <p>用它来「帧末把真实绑定恢复成游戏以为的那个」：游戏 {@code GlStateManager} 缓存着
     * 自己的绑定 id，我们直接 {@code glBindTexture} 会跳过缓存 → 游戏之后不再重绑，
     * 于是采样到我们的图集（1.8.9 实测：整个背景变白）。
     */
    public static final int GL_TEXTURE_BINDING_2D = 0x8069;

    /** {@code glPixelStorei} 的参数：解包时的行对齐字节数。 */
    public static final int GL_UNPACK_ALIGNMENT = 3317;
    /**
     * {@code glPixelStorei} 的参数：解包时每行读取的像素数（3314）。上传前必须归零。
     *
     * <p>与 {@code ModernGlApi} 同一个坑：游戏上传图集时会把它设成图集宽度，驱动随后按那个
     * 宽度读我们的小缓冲会读越界（1.21.11 真机实测 {@code nvoglv64} ACCESS_VIOLATION）。
     */
    public static final int GL_UNPACK_ROW_LENGTH = 3314;
    /** {@code glPixelStorei} 的参数：解包时跳过的行数（3315），同样必须归零。 */
    public static final int GL_UNPACK_SKIP_ROWS = 3315;
    /** {@code glPixelStorei} 的参数：解包时跳过的像素数（3316），同样必须归零。 */
    public static final int GL_UNPACK_SKIP_PIXELS = 3316;

    /** 已解析的 {@code glColor4f}，设置当前顶点颜色。 */
    private final Method color4f;
    /** 已解析的 {@code glBegin}，开始一个立即模式图元批次。 */
    private final Method begin;
    /** 已解析的 {@code glEnd}，结束当前批次。 */
    private final Method end;
    /** 已解析的 {@code glVertex2f}，提交一个二维顶点。 */
    private final Method vertex2f;
    /** 已解析的 {@code glEnable}，开启某项 GL 能力。 */
    private final Method enable;
    /** 已解析的 {@code glDisable}，关闭某项 GL 能力。 */
    private final Method disable;
    /** 已解析的 {@code glBlendFunc}，设置混合因子。 */
    private final Method blendFunc;
    /** 已解析的 {@code glPushMatrix}，压入当前变换矩阵栈。 */
    private final Method pushMatrix;
    /** 已解析的 {@code glPopMatrix}，弹出变换矩阵栈。 */
    private final Method popMatrix;
    /** 已解析的 {@code glPushAttrib}，压入 GL 属性位；核心 profile 下不存在，为 {@code null}。 */
    private final Method pushAttrib;
    /** 已解析的 {@code glPopAttrib}，弹出 GL 属性位。 */
    private final Method popAttrib;
    /** 已解析的 {@code glTranslatef}，平移当前矩阵。 */
    private final Method translatef;
    /** 已解析的 {@code glScalef}，缩放当前矩阵。 */
    private final Method scalef;
    /** 已解析的 {@code glLineWidth}，设置线段宽度。 */
    private final Method lineWidth;
    /** 已解析的 {@code glTexCoord2f}，提交二维纹理坐标。 */
    private final Method texCoord2f;
    /** 已解析的 {@code glBindTexture}，绑定纹理对象。 */
    private final Method bindTexture;
    /** 已解析的 {@code glMatrixMode}，切换当前操作的矩阵栈。 */
    private final Method matrixMode;
    /** 已解析的 {@code glLoadIdentity}，把当前矩阵重置为单位矩阵。 */
    private final Method loadIdentity;
    /** 已解析的 {@code glOrtho}，建立正交投影。 */
    private final Method ortho;
    /** 已解析的 {@code glGetIntegerv}，查询 GL 状态（LWJGL3 形态：{@code (int, int[])}）。 */
    private final Method getIntegerv;
    /**
     * 已解析的 {@code glGetInteger(int, IntBuffer)}，查询 GL 状态（LWJGL2 形态）。
     *
     * <p>LWJGL2 的 GL11 <b>没有</b> {@code glGetIntegerv(int, int[])}：只接受 {@code IntBuffer}。
     * 若只绑定数组形态，1.8.9 上视口永远读不出来（全 0），{@link GlRenderer#beginFrame()} 会据此
     * 建立 {@code glOrtho(0,0,0,0)}——该调用在 GL 里是非法的（GL_INVALID_VALUE，矩阵不变），
     * GUI 会永久停在裁剪空间之外而完全不可见。
     */
    private final Method getIntegervBuffer;
    /**
     * {@link #getIntegervBuffer} 用的直接缓冲：GL 要求直接缓冲 + 本地字节序，复用避免每帧分配。
     *
     * <p>容量必须是 <b>16 个 int</b> 而不是恰好 4 个：LWJGL2 的 {@code glGetInteger(int, IntBuffer)}
     * 会用 {@code BufferChecks.checkBuffer(params, 16)} 校验余量，容量不足直接抛
     * {@code IllegalArgumentException}（被 {@link Reflect#call} 吞掉后表现为「视口永远是 0x0」）。
     */
    private final IntBuffer viewportBuffer;
    /** 已解析的 {@code glScissor}，设置裁剪矩形。 */
    private final Method scissor;

    /** 已解析的 {@code glGenTextures(int[])}（LWJGL3 形态）；LWJGL2 只有缓冲形态。 */
    private final Method genTexturesArray;
    /** 已解析的 {@code glGenTextures(int, IntBuffer)} / {@code glGenTextures(IntBuffer)}（LWJGL2 形态）。 */
    private final Method genTexturesBuffer;
    /** 已解析的 {@code glDeleteTextures(int[])}（LWJGL3 形态）。 */
    private final Method deleteTexturesArray;
    /** 已解析的 {@code glDeleteTextures(int, IntBuffer)} / {@code glDeleteTextures(IntBuffer)}。 */
    private final Method deleteTexturesBuffer;
    /** 已解析的 {@code glTexImage2D}（两代签名一致）。 */
    private final Method texImage2D;
    /** 已解析的 {@code glTexParameteri}。 */
    private final Method texParameteri;
    /** {@code glDisableClientState}：关闭客户态数组（立即模式绘制前必须关掉）。 */
    private Method disableClientStateMethod;

    /**
     * 关闭所有客户态数组（顶点/法线/颜色/纹理坐标）。
     *
     * <p>立即模式（{@code glBegin/glVertex2f}）在对应客户态数组仍启用时<b>整段被忽略且不报错</b>。
     * 游戏各版本的渲染路径是否留下这些状态并不一致（例如 1.8.9 的方块渲染路径会留下
     * {@code GL_VERTEX_ARRAY}），所以每帧开始时统一关掉，绘制代码就不必关心版本差异。
     */
    public void disableClientArrays() {
        if (disableClientStateMethod == null) {
            return;
        }
        Reflect.call(disableClientStateMethod, null, GL_VERTEX_ARRAY);
        Reflect.call(disableClientStateMethod, null, GL_NORMAL_ARRAY);
        Reflect.call(disableClientStateMethod, null, GL_COLOR_ARRAY);
        Reflect.call(disableClientStateMethod, null, GL_TEXTURE_COORD_ARRAY);
    }


    /** 客户态数组：顶点。开启时立即模式（{@code glBegin/glVertex2f}）会被忽略且不报错。 */
    public static final int GL_VERTEX_ARRAY = 0x8074;
    /** 客户态数组：法线。 */
    public static final int GL_NORMAL_ARRAY = 0x8075;
    /** 客户态数组：颜色。开启时立即模式的 {@code glColor4f} 不生效。 */
    public static final int GL_COLOR_ARRAY = 0x8076;
    /** 客户态数组：纹理坐标。 */
    public static final int GL_TEXTURE_COORD_ARRAY = 0x8078;








    /** 已解析的 {@code glPixelStorei}。 */
    private final Method pixelStorei;

    /** 纹理句柄读写用的复用直接缓冲（GL 要求直接缓冲 + 本地字节序）。 */
    private final IntBuffer textureBuffer;
    /** 像素上传用的复用直接缓冲；不足时按需扩容，禁止每帧分配。 */
    private ByteBuffer pixelBuffer;

    /** 游戏的 GlStateManager 桥；为 {@code null} 时状态类调用直接走 GL11。 */
    private final StateManager state;

    /**
     * 保存已解析的方法句柄。
     *
     * <p>仅供 {@link #bind(Class)} 内部调用；句柄可能为 {@code null}，具体包装方法据此决定是否发起调用。
     */
    private GlApi(Method color4f, Method begin, Method end, Method vertex2f, Method enable,
                  Method disable, Method blendFunc, Method pushMatrix, Method popMatrix,
                  Method pushAttrib, Method popAttrib,
                  Method translatef, Method scalef, Method lineWidth, Method texCoord2f,
                  Method bindTexture, Method matrixMode, Method loadIdentity, Method ortho,
                  Method getIntegerv, Method getIntegervBuffer, Method scissor,
                  Method genTexturesArray, Method genTexturesBuffer, Method deleteTexturesArray,
                  Method deleteTexturesBuffer, Method texImage2D, Method texParameteri,
                  Method pixelStorei,
                  StateManager state) {
        this.color4f = color4f;
        this.begin = begin;
        this.end = end;
        this.vertex2f = vertex2f;
        this.enable = enable;
        this.disable = disable;
        this.blendFunc = blendFunc;
        this.pushMatrix = pushMatrix;
        this.popMatrix = popMatrix;
        this.pushAttrib = pushAttrib;
        this.popAttrib = popAttrib;
        this.translatef = translatef;
        this.scalef = scalef;
        this.lineWidth = lineWidth;
        this.texCoord2f = texCoord2f;
        this.bindTexture = bindTexture;
        this.matrixMode = matrixMode;
        this.loadIdentity = loadIdentity;
        this.ortho = ortho;
        this.getIntegerv = getIntegerv;
        this.getIntegervBuffer = getIntegervBuffer;
        this.viewportBuffer = ByteBuffer.allocateDirect(16 * Integer.BYTES)
                .order(ByteOrder.nativeOrder())
                .asIntBuffer();
        this.scissor = scissor;
        this.genTexturesArray = genTexturesArray;
        this.genTexturesBuffer = genTexturesBuffer;
        this.deleteTexturesArray = deleteTexturesArray;
        this.deleteTexturesBuffer = deleteTexturesBuffer;
        this.texImage2D = texImage2D;
        this.texParameteri = texParameteri;
        this.pixelStorei = pixelStorei;
        this.textureBuffer = ByteBuffer.allocateDirect(16 * Integer.BYTES)
                .order(ByteOrder.nativeOrder())
                .asIntBuffer();
        this.state = state;
    }

    /**
     * 游戏的 {@code GlStateManager} 桥（1.8.9 是 {@code bfl}）。
     *
     * <p><b>为什么状态类调用必须走它</b>：这个类给每个 GL 状态位维护了一份"我认为现在是开还是关"的
     * 缓存，并在设置时**跳过"已经是这个值"的调用**。我们若直接 {@code glDisable(GL_TEXTURE_2D)}，
     * 真实状态关掉了、它的缓存还说开着，于是游戏字体渲染器以为自己已经开过纹理、不再 glEnable，
     * 字形就画不出来——而鼠标一动触发的重绘恰好把缓存刷对，症状就成了
     * **"字要鼠标移上去才显示"**。
     *
     * <p>方法名按 [原版混淆名, SRG 名, 语义名] 依次解析：原版 1.8.9 用第一个、Forge 用第二个、
     * 未混淆构建用第三个。名字来自 `vanilla189/joined.srg` + `forge189/methods.csv`。
     * 解析不到（其它版本、或核心 profile）时本桥为 {@code null}，状态调用原样走 GL11。
     */
    static final class StateManager {

        private final Method enableTexture2D;
        private final Method disableTexture2D;
        private final Method enableBlend;
        private final Method disableBlend;
        private final Method enableDepth;
        private final Method disableDepth;
        private final Method enableCull;
        private final Method disableCull;
        private final Method blendFunc;
        private final Method bindTexture;
        private final Method color;
        private final Method pushMatrix;
        private final Method popMatrix;

        private StateManager(Method enableTexture2D, Method disableTexture2D, Method enableBlend,
                             Method disableBlend, Method enableDepth, Method disableDepth,
                             Method enableCull, Method disableCull, Method blendFunc,
                             Method bindTexture, Method color,
                             Method pushMatrix, Method popMatrix) {
            this.enableTexture2D = enableTexture2D;
            this.disableTexture2D = disableTexture2D;
            this.enableBlend = enableBlend;
            this.disableBlend = disableBlend;
            this.enableDepth = enableDepth;
            this.disableDepth = disableDepth;
            this.enableCull = enableCull;
            this.disableCull = disableCull;
            this.blendFunc = blendFunc;
            this.bindTexture = bindTexture;
            this.color = color;
            this.pushMatrix = pushMatrix;
            this.popMatrix = popMatrix;
        }

        /**
         * 在给定类加载器里解析状态桥。
         *
         * @param loader 游戏类加载器
         * @return 状态桥；找不到该类（或方法全缺）时返回 {@code null}
         */
        static StateManager resolve(ClassLoader loader) {
            Class<?> type = null;
            String[] classNames = {
                    "net.minecraft.client.renderer.GlStateManager",  // Forge deobf / 未混淆
                    "bfl",                                            // 原版 1.8.9 混淆名
            };
            for (String name : classNames) {
                type = Reflect.loadWithoutInit(name, loader);
                if (type != null) {
                    break;
                }
            }
            if (type == null) {
                return null;
            }
            StateManager state = new StateManager(
                    find(type, "w", "func_179098_w", "enableTexture2D"),
                    find(type, "x", "func_179090_x", "disableTexture2D"),
                    find(type, "l", "func_179147_l", "enableBlend"),
                    find(type, "k", "func_179084_k", "disableBlend"),
                    find(type, "j", "func_179126_j", "enableDepth"),
                    find(type, "i", "func_179097_i", "disableDepth"),
                    // 1.8.9 原版混淆名是 o；空串会让候选解析直接落空（Forge/SRG 下靠 func_179089_o 命中，
                    // 原版混淆运行时则整条 cull 缓存同步失效）。
                    find(type, "o", "func_179089_o", "enableCull"),
                    find(type, "p", "func_179129_p", "disableCull"),
                    findIntPair(type, "b", "func_179112_b", "blendFunc", "tryBlendFuncSeparate"),
                    // 纹理绑定：1.8.9 原版混淆名 i(int) = func_179144_i。反向对照：i() (无参) 是
                    // disableDepth，所以必须按 (int) 精确匹配，绝不能按名字取。
                    findInt(type, "i", "func_179144_i", "bindTexture", "_bindTexture"),
                    // 当前颜色：1.8.9 原版混淆名 c(float,float,float,float) = func_179131_c。
                    // bfl 为颜色保留了 colorState 缓存，我们只发裸 glColor4f 会让它变成假值：
                    // 游戏的 FontRenderer 走 GlStateManager.color，下一次同色请求被跳过，
                    // 真实颜色停在我们这里——文字就会串色（"缺行/颜色不对"类症状的根因）。
                    findFloats4(type, "c", "func_179131_c", "color", "color4f"),
                    find(type, "E", "func_179094_E", "pushMatrix"),
                    find(type, "F", "func_179121_F", "popMatrix"));
            return state.enableTexture2D == null && state.disableTexture2D == null
                    && state.enableBlend == null && state.blendFunc == null ? null : state;
        }

        /**
         * 按候选名解析 {@code (float,float,float,float)} 方法（当前颜色）。
         *
         * @param type  目标类
         * @param names 候选名（原版混淆名 / SRG 名 / 语义名）
         * @return 方法句柄；都解析不到返回 {@code null}
         */
        private static Method findFloats4(Class<?> type, String... names) {
            for (String name : names) {
                if (name.isEmpty()) {
                    continue;
                }
                Method method = Reflect.method(type, name, float.class, float.class, float.class,
                        float.class);
                if (method != null) {
                    return method;
                }
            }
            return null;
        }

        /**
         * 设置当前颜色（同步游戏的颜色缓存）；句柄缺失时返回 false。
         *
         * @param r,g,b,a 分量 0..1
         * @return 已处理返回 {@code true}
         */
        boolean color(float r, float g, float b, float a) {
            if (color == null) {
                return false;
            }
            Reflect.call(color, null, r, g, b, a);
            return true;
        }

        /** 按候选名解析 {@code (int)} 方法（纹理绑定）。 */
        private static Method findInt(Class<?> type, String... names) {
            for (String name : names) {
                if (name.isEmpty()) {
                    continue;
                }
                Method method = Reflect.method(type, name, int.class);
                if (method != null) {
                    return method;
                }
            }
            return null;
        }

        /**
         * 按候选名解析 {@code (int,int)} 方法（混合因子是唯一带参的状态设置）。
         *
         * <p>不能复用 {@link #find}：1.8.9 的 {@code GlStateManager} 里存在<b>无参</b>的 {@code b()}，
         * 按名字匹配会绑到它，调用时抛 {@code IllegalArgumentException: wrong number of arguments}
         * 并被静默吞掉——混合因子于是从未设置成功。
         */
        private static Method findIntPair(Class<?> type, String... names) {
            for (String name : names) {
                if (name.isEmpty()) {
                    continue;
                }
                Method method = Reflect.method(type, name, int.class, int.class);
                if (method != null) {
                    return method;
                }
            }
            return null;
        }

        /** 按候选名逐个解析无参方法（矩阵/开关类都是无参）。 */
        private static Method find(Class<?> type, String... names) {
            for (String name : names) {
                if (name.isEmpty()) {
                    continue;
                }
                Method method = Reflect.method(type, name);
                if (method != null) {
                    return method;
                }
            }
            return null;
        }

        /**
         * 按 GL 能力位设置开关；本桥不认识的能力位交回调用方走 GL11。
         *
         * @return 是否已处理
         */
        boolean set(int cap, boolean enabled) {
            Method target;
            if (cap == GL_TEXTURE_2D) {
                target = enabled ? enableTexture2D : disableTexture2D;
            } else if (cap == GL_BLEND) {
                target = enabled ? enableBlend : disableBlend;
            } else if (cap == GL_DEPTH_TEST) {
                target = enabled ? enableDepth : disableDepth;
            } else if (cap == GL_CULL_FACE) {
                target = enabled ? enableCull : disableCull;
            } else {
                return false;
            }
            if (target == null) {
                return false;
            }
            Reflect.call(target, null);
            return true;
        }

        /**
         * 绑定 2D 纹理；句柄缺失时返回 false。
         *
         * @param texture 纹理句柄
         * @return 已处理返回 {@code true}
         */
        boolean bind2d(int texture) {
            if (bindTexture == null) {
                return false;
            }
            Reflect.call(bindTexture, null, texture);
            return true;
        }

        /** 设置混合因子；句柄缺失时返回 false。 */
        boolean blend(int src, int dst) {
            if (blendFunc == null) {
                return false;
            }
            Reflect.call(blendFunc, null, src, dst);
            return true;
        }

    }

    /**
     * 通过 {@code loader} 加载 {@code org.lwjgl.opengl.GL11} 并绑定；加载不到时返回 {@code null}。
     *
     * @param className GL 类名（通常为 {@code "org.lwjgl.opengl.GL11"}）
     * @param loader    游戏类加载器，用于解析 LWJGL 类
     */
    public static GlApi bind(String className, ClassLoader loader) {
        return bind(Reflect.loadWithoutInit(className, loader));
    }

    /**
     * 绑定一个已解析的 GL 类（测试也可用替身类调用）。
     *
     * @return 可用的绑定；若缺少绘制所需的核心句柄（color/begin/end/vertex）则返回 {@code null}
     */
    public static GlApi bind(Class<?> gl) {
        if (gl == null) {
            return null;
        }
        Method color4f = Reflect.method(gl, "glColor4f", float.class, float.class, float.class, float.class);
        Method begin = Reflect.method(gl, "glBegin", int.class);
        Method end = Reflect.method(gl, "glEnd");
        Method vertex2f = Reflect.method(gl, "glVertex2f", float.class, float.class);
        if (color4f == null || begin == null || end == null || vertex2f == null) {
            return null; // 核心句柄不全，无法作为可用的 GL11
        }
        GlApi bound = new GlApi(
                color4f, begin, end, vertex2f,
                Reflect.method(gl, "glEnable", int.class),
                Reflect.method(gl, "glDisable", int.class),
                Reflect.method(gl, "glBlendFunc", int.class, int.class),
                Reflect.method(gl, "glPushMatrix"),
                Reflect.method(gl, "glPopMatrix"),
                // 属性栈是固定管线专属（GL11）；核心 profile 下解析不到，句柄为 null 时调用被跳过。
                Reflect.method(gl, "glPushAttrib", int.class),
                Reflect.method(gl, "glPopAttrib"),
                Reflect.method(gl, "glTranslatef", float.class, float.class, float.class),
                Reflect.method(gl, "glScalef", float.class, float.class, float.class),
                Reflect.method(gl, "glLineWidth", float.class),
                Reflect.method(gl, "glTexCoord2f", float.class, float.class),
                Reflect.method(gl, "glBindTexture", int.class, int.class),
                Reflect.method(gl, "glMatrixMode", int.class),
                Reflect.method(gl, "glLoadIdentity"),
                Reflect.method(gl, "glOrtho", double.class, double.class, double.class,
                        double.class, double.class, double.class),
                Reflect.method(gl, "glGetIntegerv", int.class, int[].class),
                // LWJGL2 只有 glGetInteger(int, IntBuffer)；LWJGL3 两个形态都有。先试 LWJGL2 的名字，
                // 再退到 LWJGL3 的 glGetIntegerv(int, IntBuffer) 重载。
                orElse(Reflect.method(gl, "glGetInteger", int.class, IntBuffer.class),
                        Reflect.method(gl, "glGetIntegerv", int.class, IntBuffer.class)),
                Reflect.method(gl, "glScissor", int.class, int.class, int.class, int.class),
                // 纹理：glGenTextures / glDeleteTextures 在两代 LWJGL 上签名不同——
                // LWJGL2 只有缓冲形态（glGenTextures(int, IntBuffer) / (IntBuffer)），
                // LWJGL3 用数组形态（glGenTextures(int[])），同时保留 (int, IntBuffer)。
                Reflect.method(gl, "glGenTextures", int[].class),
                orElse(Reflect.method(gl, "glGenTextures", int.class, IntBuffer.class),
                        Reflect.method(gl, "glGenTextures", IntBuffer.class)),
                Reflect.method(gl, "glDeleteTextures", int[].class),
                orElse(Reflect.method(gl, "glDeleteTextures", int.class, IntBuffer.class),
                        Reflect.method(gl, "glDeleteTextures", IntBuffer.class)),
                // glTexImage2D 两代签名一致（最后一段都是 ByteBuffer）。
                Reflect.method(gl, "glTexImage2D", int.class, int.class, int.class, int.class,
                        int.class, int.class, int.class, int.class, ByteBuffer.class),
                Reflect.method(gl, "glTexParameteri", int.class, int.class, int.class),
                Reflect.method(gl, "glPixelStorei", int.class, int.class),
                // 状态类调用优先走游戏的 GlStateManager（缓存一致），解析不到时为 null。
                StateManager.resolve(gl.getClassLoader()));
        // 立即模式的前提：客户态数组必须关得掉（关不掉时 glBegin/glVertex2f 会被静默忽略）。
        bound.disableClientStateMethod = Reflect.method(gl, "glDisableClientState", int.class);
        bound.isEnabledMethod = Reflect.method(gl, "glIsEnabled", int.class);
        return bound;
    }

    // ── 立即模式批次合并（照 eps-plus 的「累加器 + 一次提交」） ─────────────
    //
    // 逐图元 begin/end + 每图元重复开关纹理，是 UI 每帧最大的 GL 调用来源：一个面板几百个矩形
    // 就是几百次 glBegin/glEnd 与上千次状态切换。GL 允许在 glBegin/glEnd 之间改颜色与纹理坐标，
    // 所以「模式 + 纹理相同」的连续图元可以合进同一个批次：纯色矩形、同一图集的字形都能合并。
    // 任何状态切换（开关纹理、绑定纹理、裁剪、矩阵、线宽）之前必须先 flush。

    /** 当前打开的批次模式；0 表示没有打开的批次。 */
    private int batchMode;
    /** 当前批次的纹理：0 表示无纹理，否则为纹理句柄。 */
    private int batchTexture;
    /** 统计：提交的批次数（UI 一次 draw call ≈ 一个批次）。 */
    private int batchCount;
    /** 统计：提交的顶点数。 */
    private int vertexCount;

    /**
     * 确保当前有一个「模式 + 纹理」匹配的批次；不匹配时先结束旧批次。
     *
     * @param mode    图元模式（{@code GL_QUADS}/{@code GL_LINE_LOOP}/…）
     * @param texture 纹理句柄；0 表示无纹理
     */
    private void ensureBatch(int mode, int texture) {
        if (batchMode == mode && batchTexture == texture) {
            return;
        }
        flushBatch();
        if (texture == 0) {
            setCap(GL_TEXTURE_2D, false);
        } else {
            setCap(GL_TEXTURE_2D, true);
            bindTexture2D(texture);
        }
        batchTexture = texture;
        batchMode = mode;
        batchCount++;
        Reflect.call(begin, null, mode);
    }

    /**
     * 结束当前批次；没有打开的批次时为空操作。
     *
     * <p>任何会改变 GL 状态的操作（能力位、纹理、裁剪、矩阵、线宽）在调用真实 GL 之前都必须先调它。
     */
    public void flushBatch() {
        if (batchMode == 0) {
            return;
        }
        Reflect.call(end, null);
        batchMode = 0;
        batchTexture = 0;
    }

    /**
     * 开一个扇形批次（{@code GL_TRIANGLE_FAN}）。
     *
     * <p>调用方自己提交顶点，不需要 end：批次在下次状态切换或 {@link #flushBatch()} 时结束。
     *
     * @param texture 纹理句柄；0 表示无纹理
     */
    public void beginFan(int texture) {
        ensureBatch(GL_TRIANGLE_FAN, texture);
    }

    /**
     * 开一个纹理四边形批次（{@code GL_QUADS} + 指定纹理）。
     *
     * <p>同一张图集的连续字形会合进同一个批次——这是文字路径最大的调用数来源。
     *
     * @param texture 纹理句柄
     */
    public void beginTexturedQuads(int texture) {
        ensureBatch(GL_QUADS, texture);
    }

    /** @return 上次清零以来提交的 {@code {批次数, 顶点数}}，并清零（诊断用）。 */
    public int[] takeBatchCounts() {
        int[] out = {batchCount, vertexCount};
        batchCount = 0;
        vertexCount = 0;
        return out;
    }

    /**
     * 设置当前顶点颜色（对应 {@code glColor4f}）。
     *
     * @param r,g,b,a 分量，取值 0–1
     */
    public void color(float r, float g, float b, float a) {
        // 与能力位同一条原则：先同步游戏的颜色缓存，再无条件下发真实颜色。
        // 只发裸 glColor4f 会把游戏的缓存留成假值，它自己的 FontRenderer 之后同色请求会被跳过。
        if (state != null) {
            state.color(r, g, b, a);
        }
        Reflect.call(color4f, null, r, g, b, a);
    }

    /**
     * 开始一个立即模式图元批次（对应 {@code glBegin}）。
     *
     * @param mode 图元模式，取本类的 {@code GL_*} 常量之一
     */
    public void begin(int mode) {
        Reflect.call(begin, null, mode);
    }

    /** 结束当前立即模式批次（对应 {@code glEnd}）。 */
    public void end() {
        Reflect.call(end, null);
    }

    /** 提交一个二维顶点（对应 {@code glVertex2f}），坐标以窗口像素为单位。 */
    public void vertex(float x, float y) {
        vertexCount++;
        Reflect.call(vertex2f, null, x, y);
    }

    /** 开启某项 GL 能力（对应 {@code glEnable}）。 */
    public void enable(int cap) {
        setCap(cap, true);
    }

    /** 关闭某项 GL 能力（对应 {@code glDisable}）。 */
    public void disable(int cap) {
        setCap(cap, false);
    }

    /**
     * 设置某项能力位：<b>先经游戏的状态桥同步它的缓存，再直接下发真实 GL 调用</b>。
     *
     * <p>为什么两次都要做：游戏的状态管理器（1.8.9 的 {@code GlStateManager}）为每个能力位维护
     * 一份"我认为现在是开还是关"的缓存，并在设置时跳过"已经是这个值"的调用。一旦缓存与实际状态
     * 脱钩（其它模组、直接 GL 调用、或游戏自身的某些路径都会造成），缓存就会让本该生效的设置变成
     * 空操作，而真实状态保持原样——表现为"绘制代码全部正确、画面却看不见"，且不报任何 GL 错误。
     *
     * <p>1.8.9 真机实测：{@code GL_CULL_FACE} 缓存认为已关、实际仍开着，我们翻转 y 轴的正交投影让
     * 四边形在屏幕空间变成顺时针，于是整个界面被背面剔除掉（{@code glClear} 不受影响，所以表现为
     * "清屏可见、几何不可见"）。两次调用后缓存与实际必然一致（顺序：先缓存后真实）。
     *
     * @param cap     能力位
     * @param enabled 目标状态
     */
    private void setCap(int cap, boolean enabled) {
        flushBatch();
        if (state != null) {
            state.set(cap, enabled);
        }
        Reflect.call(enabled ? enable : disable, null, cap);
        // 校验只在调试模式下做：生产路径不为此每帧付出多次 glIsEnabled。
        if (System.getProperty("nocturne.ui.debug") != null) {
            Boolean real = isCapEnabled(cap);
            if (real != null && real != enabled) {
                reportStateDesync(cap, enabled, real);
            }
        }
    }

    /** 已经报告过的状态失配能力位（每项只报一次，避免每帧刷屏）。 */
    private final java.util.Set<Integer> desyncReported = java.util.Collections
            .synchronizedSet(new java.util.HashSet<Integer>());

    /**
     * 报告"设置后真实状态仍与目标不符"（正常情况下不会发生；发生即为桥/驱动异常）。
     *
     * @param cap   能力位
     * @param want  目标状态
     * @param real  读回的真实状态
     */
    private void reportStateDesync(int cap, boolean want, boolean real) {
        if (!desyncReported.add(cap)) {
            return;
        }
        System.out.println("[nocturne] gl state desync: cap=0x" + Integer.toHexString(cap)
                + " want=" + want + " real=" + real);
    }

    /** 设置 alpha 混合的源/目标因子（对应 {@code glBlendFunc}）。 */
    public void blendFunc(int src, int dst) {
        flushBatch();
        // 同上：桥同步缓存，真实调用负责落地（混合因子在部分版本也被缓存）。
        if (state != null) {
            state.blend(src, dst);
        }
        Reflect.call(blendFunc, null, src, dst);
    }

    /** 压入当前模型视图矩阵（对应 {@code glPushMatrix}）。 */
    public void pushMatrix() {
        Reflect.call(pushMatrix, null);
    }

    /** 弹出模型视图矩阵栈（对应 {@code glPopMatrix}），必须与 {@link #pushMatrix()} 成对使用。 */
    public void popMatrix() {
        Reflect.call(popMatrix, null);
    }

    /**
     * 是否支持属性栈（{@code glPushAttrib}/{@code glPopAttrib}）。
     *
     * <p>只有固定管线（1.8.9 / LWJGL2）提供这两个入口；核心 profile 已移除立即模式与属性栈，
     * 那时的状态管理由调用方自行负责。
     */
    public boolean hasAttribStack() {
        return pushAttrib != null && popAttrib != null;
    }

    /**
     * 压入 GL 属性位（对应 {@code glPushAttrib}）。
     *
     * @param mask 属性位掩码，通常取 {@link #GL_ALL_ATTRIB_BITS}
     */
    public void pushAttrib(int mask) {
        Reflect.call(pushAttrib, null, mask);
    }

    /** 弹出 GL 属性位（对应 {@code glPopAttrib}），必须与 {@link #pushAttrib(int)} 成对使用。 */
    public void popAttrib() {
        Reflect.call(popAttrib, null);
    }

    /** 平移当前矩阵（对应 {@code glTranslatef}）；UI 通常只在 z 传 0。 */
    public void translate(float x, float y, float z) {
        flushBatch();
        Reflect.call(translatef, null, x, y, z);
    }

    /** 缩放当前矩阵（对应 {@code glScalef}）。 */
    public void scale(float x, float y, float z) {
        flushBatch();
        Reflect.call(scalef, null, x, y, z);
    }

    /** 设置线段宽度（对应 {@code glLineWidth}）。 */
    public void lineWidth(float width) {
        flushBatch();
        Reflect.call(lineWidth, null, width);
    }

    /** 提交二维纹理坐标（对应 {@code glTexCoord2f}）。 */
    public void texCoord(float u, float v) {
        Reflect.call(texCoord2f, null, u, v);
    }

    /** 绑定纹理对象（对应 {@code glBindTexture}）。 */
    public void bindTexture(int target, int texture) {
        Reflect.call(bindTexture, null, target, texture);
    }

    /** 切换当前操作的矩阵栈（对应 {@code glMatrixMode}），参数取 {@link #GL_PROJECTION} 或 {@link #GL_MODELVIEW}。 */
    public void matrixMode(int mode) {
        flushBatch();
        Reflect.call(matrixMode, null, mode);
    }

    /** 把当前矩阵重置为单位矩阵（对应 {@code glLoadIdentity}）。 */
    public void loadIdentity() {
        Reflect.call(loadIdentity, null);
    }

    /**
     * 建立正交投影（对应 {@code glOrtho}）。
     *
     * @param left,right,bottom,top 视景体的左右下上边界（世界坐标）
     * @param near,far              近远裁剪面距离；UI 用 {@code -1, 1} 即可，因为所有顶点都在 z=0
     */
    public void ortho(double left, double right, double bottom, double top, double near, double far) {
        Reflect.call(ortho, null, left, right, bottom, top, near, far);
    }

    /**
     * 查询当前视口。
     *
     * @return {@code {x, y, width, height}}；句柄缺失时返回 {@code null}，查询失败时各项为 0
     */
    public int[] viewport() {
        int[] out = new int[4];
        if (getIntegerv != null) {
            // LWJGL3 形态：直接写进 int[]。
            Reflect.call(getIntegerv, null, GL_VIEWPORT, out);
            if (out[2] > 0 && out[3] > 0) {
                return out;
            }
        }
        if (getIntegervBuffer != null) {
            // LWJGL2 形态：写进直接缓冲后再取回四个分量（GL 调用不会移动缓冲区位置）。
            viewportBuffer.clear();
            Reflect.call(getIntegervBuffer, null, GL_VIEWPORT, viewportBuffer);
            out[0] = viewportBuffer.get(0);
            out[1] = viewportBuffer.get(1);
            out[2] = viewportBuffer.get(2);
            out[3] = viewportBuffer.get(3);
            return out;
        }
        // 两种形态都没有：按契约返回 null（表示「无法查询」），而不是伪装成 0x0 的视口。
        return getIntegerv == null ? null : out;
    }

    /**
     * 设置裁剪矩形（对应 {@code glScissor}）。
     *
     * <p>注意参数为窗口像素且原点在窗口<b>左下角</b>，与 GUI 坐标系（原点左上）y 轴相反，
     * 换算由调用方 {@link GlRenderer} 负责。
     */
    public void scissor(int x, int y, int width, int height) {
        flushBatch();
        Reflect.call(scissor, null, x, y, width, height);
    }

    // ------------------------------------------------------------------ 纹理

    /**
     * 是否具备纹理上传所需的最小句柄集。
     *
     * <p>三项缺一都传不了纹理：生成句柄（{@code glGenTextures} 的数组或缓冲形态任一）、
     * 上传像素（{@code glTexImage2D}）、设置采样参数（{@code glTexParameteri}）。
     *
     * @return 可用返回 {@code true}
     */
    public boolean hasTextures() {
        return (genTexturesArray != null || genTexturesBuffer != null)
                && texImage2D != null && texParameteri != null;
    }

    /** @return 新建的纹理句柄；不可用或失败时为 0 */
    public int genTexture() {
        if (genTexturesArray != null) {
            int[] ids = new int[1];
            Reflect.call(genTexturesArray, null, ids);
            return ids[0];
        }
        if (genTexturesBuffer != null) {
            textureBuffer.clear();
            textureBuffer.limit(1);
            // LWJGL2 的 (int, IntBuffer) 形态带 count；只有 (IntBuffer) 重载时传 null 也不影响（按缓冲余量）。
            if (hasParameter(genTexturesBuffer, int.class)) {
                Reflect.call(genTexturesBuffer, null, 1, textureBuffer);
            } else {
                Reflect.call(genTexturesBuffer, null, textureBuffer);
            }
            return textureBuffer.get(0);
        }
        return 0;
    }

    /** 删除一个纹理句柄；{@code id <= 0} 或不可用时为空操作。 */
    public void deleteTexture(int id) {
        if (id <= 0) {
            return;
        }
        if (deleteTexturesArray != null) {
            Reflect.call(deleteTexturesArray, null, new int[]{id});
            return;
        }
        if (deleteTexturesBuffer != null) {
            textureBuffer.clear();
            textureBuffer.limit(1);
            textureBuffer.put(0, id);
            if (hasParameter(deleteTexturesBuffer, int.class)) {
                Reflect.call(deleteTexturesBuffer, null, 1, textureBuffer);
            } else {
                Reflect.call(deleteTexturesBuffer, null, textureBuffer);
            }
        }
    }

    /** 绑定二维纹理到 {@link #GL_TEXTURE_2D}。 */
    public void bindTexture2D(int id) {
        if (batchMode != 0) {
            flushBatch();
        }
        // 与能力位同一条原则：先经游戏的 GlStateManager 同步它的纹理缓存，再无条件下发真实绑定。
        // 只走裸 GL 会让游戏缓存以为"还是旧纹理"，之后它给字体图集绑定纹理时按缓存跳过，
        // 游戏文字就会拿我们的字形图集去采样——屏幕上出现成排的实心方块（1.8.9 真机实测）。
        if (state != null) {
            state.bind2d(id);
        }
        bindTexture(GL_TEXTURE_2D, id);
    }

    /**
     * 上传一张 RGBA 纹理的全量像素。
     *
     * <p>像素格式固定为非预乘 {@code 0xAARRGGBB}、行优先、左上原点——与本类的
     * {@code SRC_ALPHA / ONE_MINUS_SRC_ALPHA} 混合约定一致（见 {@link #uploadTexture}）。
     * 复用内部直接缓冲，不在热路径上分配。
     *
     * @param width  宽度（像素）
     * @param height 高度（像素）
     * @param argb   像素；长度不足时为安全起见按 0 补齐
     * @return 下发成功返回 {@code true}
     */
    public boolean uploadTexture(int width, int height, int[] argb) {
        if (texImage2D == null || width <= 0 || height <= 0 || argb == null) {
            return false;
        }
        int needed = width * height * 4;
        ByteBuffer buffer = pixelBuffer;
        if (buffer == null || buffer.capacity() < needed) {
            buffer = ByteBuffer.allocateDirect(needed).order(ByteOrder.nativeOrder());
            pixelBuffer = buffer;
        }
        buffer.clear();
        for (int i = 0; i < width * height; i++) {
            int pixel = argb[i];
            buffer.put((byte) ((pixel >> 16) & 0xFF)); // R
            buffer.put((byte) ((pixel >> 8) & 0xFF));  // G
            buffer.put((byte) (pixel & 0xFF));         // B
            buffer.put((byte) ((pixel >>> 24) & 0xFF)); // A
        }
        buffer.flip();
        if (pixelStorei != null) {
            // 行对齐：RGBA 每行字节数必然是 4 的倍数，显式设为 4 以免游戏把全局值改过。
            Reflect.call(pixelStorei, null, GL_UNPACK_ALIGNMENT, 4);
            // 行长度/跳过量必须归零：游戏上传图集时会改这些全局值，驱动按旧值读我们的小缓冲
            // 会读越界（1.21.11 真机：nvoglv64 ACCESS_VIOLATION；固定管线同一套驱动）。
            Reflect.call(pixelStorei, null, GL_UNPACK_ROW_LENGTH, 0);
            Reflect.call(pixelStorei, null, GL_UNPACK_SKIP_ROWS, 0);
            Reflect.call(pixelStorei, null, GL_UNPACK_SKIP_PIXELS, 0);
        }
        Reflect.call(texImage2D, null, GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0,
                GL_RGBA, GL_UNSIGNED_BYTE, buffer);
        return true;
    }

    /** 已解析的 {@code glIsEnabled}，用于回读能力位的真实状态（调试校验用）。 */
    private Method isEnabledMethod;

    public boolean isCapEnabled(int cap) {
        if (isEnabledMethod == null) {
            return false;
        }
        return Boolean.TRUE.equals(Reflect.call(isEnabledMethod, null, cap));
    }

    public int[] intQuery(int pname, int count) {
        int[] out = new int[count];
        if (getIntegerv != null) {
            Reflect.call(getIntegerv, null, pname, out);
            return out;
        }
        if (getIntegervBuffer != null) {
            viewportBuffer.clear();
            Reflect.call(getIntegervBuffer, null, pname, viewportBuffer);
            for (int i = 0; i < count; i++) {
                out[i] = viewportBuffer.get(i);
            }
            return out;
        }
        return null;
    }

    /** @return 当前绑定的 2D 纹理；查询失败时返回 {@code -1} */
    public int boundTexture() {
        int[] out = new int[1];
        if (getIntegerv != null) {
            // LWJGL3 形态：直接写进 int[]。
            Reflect.call(getIntegerv, null, GL_TEXTURE_BINDING_2D, out);
            if (out[0] > 0) {
                return out[0];
            }
        }
        if (getIntegervBuffer != null) {
            // LWJGL2 形态：写进直接缓冲后再取回（GL 调用不会移动缓冲区位置，**不能 flip**——
            // 早先多调一次 flip 让 position 停在被清空处，读回来恒为 -1，绑定还原整个失效）。
            viewportBuffer.clear();
            Reflect.call(getIntegervBuffer, null, GL_TEXTURE_BINDING_2D, viewportBuffer);
            return viewportBuffer.get(0);
        }
        return -1;
    }

    /** 设置纹理采样参数（对应 {@code glTexParameteri}）。 */
    public void texParameteri(int target, int pname, int param) {
        Reflect.call(texParameteri, null, target, pname, param);
    }

    /** 设置像素存储模式（对应 {@code glPixelStorei}）。 */
    public void pixelStorei(int pname, int param) {
        Reflect.call(pixelStorei, null, pname, param);
    }

    /** @return 给定方法句柄是否接受指定类型的参数（用于在同义重载间分派） */
    private static boolean hasParameter(Method method, Class<?> parameter) {
        if (method == null) {
            return false;
        }
        for (Class<?> type : method.getParameterTypes()) {
            if (type == parameter) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否具备接管坐标系所需的方法（{@code glMatrixMode} / {@code glLoadIdentity} / {@code glOrtho}）。
     *
     * <p>三者缺一，渲染器就无法建立自己的正交投影，只能继承游戏当前的矩阵，画面通常落在裁剪空间之外
     * 而完全不可见——所以这个状态必须能被诊断出来，而不是静默退化。
     */
    public boolean hasMatrixControl() {
        return matrixMode != null && loadIdentity != null && ortho != null;
    }

    /** 是否支持 {@code glScissor} 裁剪（需要 {@code glScissor} 加上任一种视口查询形态）。 */
    public boolean hasScissor() {
        return scissor != null && (getIntegerv != null || getIntegervBuffer != null);
    }

    /** 选第一个非空的方法句柄，供同义重载（LWJGL2 / LWJGL3 命名差异）之间回退。 */
    private static Method orElse(Method preferred, Method fallback) {
        return preferred != null ? preferred : fallback;
    }

    /**
     * 便捷方法：用立即模式绘制一个填充矩形。
     *
     * <p>顶点顺序为「左上 → 右上 → 右下 → 左下」，与 {@link GlRenderer#beginFrame()}
     * 建立的 y 轴向下投影一致；旧实现按 y 轴朝上排列，与投影互为反面。
     *
     * <p>绘制前显式关闭 {@code GL_TEXTURE_2D}：游戏字体渲染器会在两次绘制之间把它打开，
     * 若不在每个图元批次前复位，后续所有无纹理坐标的图元都会去采样字体图集。
     */
    public void fillRect(float x, float y, float width, float height, float r, float g, float b, float a) {
        ensureBatch(GL_QUADS, 0);
        color(r, g, b, a);
        // 绕序必须与 vanilla GUI 一致：投影把 y 轴翻成向下之后，(x,y+ h)→(x+w,y+h)→(x+w,y)→(x,y)
        // 在 NDC 里是逆时针 = 默认 GL_CCW 的正面。写反的话，一旦剔除被谁打开（1.8.9 的 EntityRenderer
        // 帧末就把 GL_CULL_FACE 留成开启），整个界面会被背面剔除掉且不报错。
        vertex(x, y + height);
        vertex(x + width, y + height);
        vertex(x + width, y);
        vertex(x, y);
    }

    /**
     * 便捷方法：用 {@code GL_LINE_LOOP} 绘制一个线宽可设的描边矩形。
     *
     * <p>与 {@link #fillRect} 一样，先关闭纹理，避免描边顶点采样字体图集。
     * 线宽的还原由调用方负责（见 {@link GlRenderer#outline}）。
     */
    public void strokeRect(float x, float y, float width, float height, float lineWidth,
                           float r, float g, float b, float a) {
        this.lineWidth(lineWidth);
        ensureBatch(GL_LINE_LOOP, 0);
        color(r, g, b, a);
        vertex(x, y + height);
        vertex(x + width, y + height);
        vertex(x + width, y);
        vertex(x, y);
    }
}
