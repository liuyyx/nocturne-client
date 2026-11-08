package dev.nocturne.agent;

import dev.nocturne.client.runtime.NocturneRuntime;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 叠加层安装去重：同一次点击可能触发两次注入（CLI 一次、GUI 再一次；Fabric 还会把 agent jar
 * 重新定义一份）。两边各注册一个 {@link OverlayBootstrap} 就有两套 listener 抢着安装、重复绘制。
 *
 * <p>令牌必须在 bootstrap 层的 FrameDispatcher 里（跨加载器副本共享）；agent 侧的静态字段在
 * Fabric 下会被重新定义成另一份，挡不住第二个副本。
 */
class OverlayInstallDedupTest {

    @Test
    void twoInstallsRegisterOnlyOneListener() {
        // 清空上一次测试残留的监听器与令牌（等价于一次完整的自销毁）。
        NocturneRuntime.shutdown();
        int before = NocturneRuntime.listenerCount();

        OverlayBootstrap.install(null, null, NocturneAgent.VK_RIGHT_SHIFT, false);
        int afterFirst = NocturneRuntime.listenerCount();
        OverlayBootstrap.install(null, null, NocturneAgent.VK_RIGHT_SHIFT, false);
        int afterSecond = NocturneRuntime.listenerCount();

        assertEquals(before + 1, afterFirst, "the first install must register its listener");
        assertEquals(afterFirst, afterSecond,
                "a second injection must not register a second overlay listener");
    }
}
