package dev.nocturne.ui.gl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL30;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModernGlApi} 与 {@link ModernRenderer} 的就绪判定测试（C-03 回归）。
 *
 * <p>核心 profile 路径曾因两处签名/判定错误而「报 ready 却什么都不画」：
 * <ol>
 *   <li>{@code genBuffer} 用 {@code glGenBuffers(int, int[])} 调 LWJGL（实际只有
 *       {@code glGenBuffers(int[])}），异常被反射层吞掉，返回值恒为 0；</li>
 *   <li>VAO/VBO id 为 0 时仍判定就绪，后续 {@code bindBuffer(0)}/drawArrays 结果未定义。</li>
 * </ol>
 * 这里用测试替身 {@code org.lwjgl.opengl.GL30} 把两条路径都钉住。
 */
class ModernGlApiTest {

    @BeforeEach
    void setUp() {
        GL30.reset();
    }

    /**
     * 回归（High-4）：缓冲形态的 {@code glGetIntegerv} 必须能读出真实视口。
     *
     * <p>旧实现读完就 {@code flip()}：GL 是按原生地址写缓冲区的、Java 侧 position 一直停在 0，
     * 于是 flip() 把 limit 置成 0，remaining()==0 被当成"GL 没写入"而返回 null——视口永远退回
     * 窗口尺寸推算，混合因子恢复也成了死代码。替身补上缓冲形态后这条分支才有覆盖。
     */
    @Test
    void viewportIsReadThroughTheBufferForm() {
        ModernGlApi api = ModernGlApi.bind(getClass().getClassLoader());
        assertNotNull(api);

        int[] viewport = api.getInteger(ModernGlApi.GL_VIEWPORT, 4);

        assertNotNull(viewport, "the buffer form must return what GL wrote, not null");
        assertEquals(0, viewport[0]);
        assertEquals(0, viewport[1]);
        assertEquals(100, viewport[2], "width must come from glGetIntegerv");
        assertEquals(100, viewport[3], "height must come from glGetIntegerv");
        assertEquals(1, GL30.getIntegervBufferCalls, "the buffer form must be preferred");
    }

    /** genBuffer 必须按 LWJGL 的单参数组签名调用，并取回非零 id */
    @Test
    void genBufferUsesTheSingleArgumentSignature() {
        ModernGlApi api = ModernGlApi.bind(getClass().getClassLoader());
        assertNotNull(api, "fixture must satisfy the mandatory core-profile subset");

        assertEquals(7, api.genBuffer(), "genBuffer must return the id written by glGenBuffers");
        assertEquals(1, GL30.genBufferCalls);
        assertEquals(1, GL30.lastGenBufferArrayLength,
                "glGenBuffers must be called with exactly one array argument");
    }

    /** 缓冲对象创建成功后初始化即成功，且 ready 为真 */
    @Test
    void rendererReadyWhenResourcesAreCreated() {
        ModernGlApi api = ModernGlApi.bind(getClass().getClassLoader());
        ModernRenderer renderer = new ModernRenderer(api, null);

        assertTrue(renderer.initialise());
        assertTrue(renderer.ready());
    }

    /**
     * C-03 回归：VAO/VBO id 为 0 时 {@code initialise} 必须失败且 {@code ready} 为假。
     *
     * <p>否则渲染器会在空缓冲上每帧 drawArrays，画面异常却仍被上层当作可用后端。
     */
    @Test
    void rendererNotReadyWhenBufferIdsAreZero() {
        GL30.failIds = true;
        ModernGlApi api = ModernGlApi.bind(getClass().getClassLoader());
        ModernRenderer renderer = new ModernRenderer(api, null);

        assertFalse(renderer.initialise(), "zero VAO/VBO ids must fail initialisation");
        assertFalse(renderer.ready(), "a half-created renderer must not advertise readiness");
    }
}
