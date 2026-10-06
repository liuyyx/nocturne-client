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
