package dev.nocturne.agent.transform;

import java.lang.instrument.ClassFileTransformer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link EmbeddedAsmLoader} 的单元测试。
 *
 * <p>重点是「优雅降级」契约：内嵌 ASM 资源在开发/测试环境里可能根本不存在（它由 dist 打包任务
 * 写入），此时 {@link EmbeddedAsmLoader#create()} 必须返回 {@code null} 而不是抛异常，让调用方
 * 跳过帧钩子、其余功能照常。资源存在时则验证转换器确实由子加载器定义（child-first），而不是落回
 * 父加载器。
 */
class EmbeddedAsmLoaderTest {

    /**
     * {@code create()} 任何情况下都不得抛出；有资源时据此创建出的转换器必须来自子加载器，
     * 且对非目标类保持 no-op。
     */
    @Test
    void createDegradesSafelyAndIsolatesTransformer() throws Exception {
        // 资源缺失是合法结果（返回 null），资源存在则返回可用加载器；两者都不得抛异常。
        EmbeddedAsmLoader loader = EmbeddedAsmLoader.create();
        if (loader == null) {
            return;
        }

        ClassFileTransformer transformer =
                loader.createTransformer("org/lwjgl/opengl/Display", "update", "()V");
        assertNotNull(transformer, "creator must build a transformer when the loader is available");
        // 非目标类必须按原样放行（返回 null），且不得触发任何异常。
        assertNull(transformer.transform(null, "other/Thing", null, null, new byte[]{1, 2, 3}));

        // child-first：转换器由内嵌加载器定义，而不是父加载器上的同名类。
        assertFalse(transformer.getClass().getClassLoader() == FrameHookTransformer.class.getClassLoader(),
                "transformer must be defined by the embedded child loader, not the parent");
    }
}
