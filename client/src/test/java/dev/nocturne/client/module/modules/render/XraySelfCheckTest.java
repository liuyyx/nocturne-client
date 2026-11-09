package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.TestSupport;
import dev.nocturne.client.bootstrap.GameplayHook;
import dev.nocturne.client.event.MoveEvent;
import dev.nocturne.client.value.BooleanValue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归：Xray 的「可见方块 = 0」自检阈值，以及自检命中后的处理方式。
 *
 * <p>真机事故：剔除把整个世界剔没了（方块名/映射对不上时），世界全黑。自检必须在样本足够、
 * 且一个方块都没放行时触发；样本不足的窗口（刚启用、切维度）不得误关。
 *
 * <p>审计补充：命中后旧实现直接 {@code setEnabled(false)}，静默改写用户开关。现在改成内部停摆标志，
 * 用户开关保持开启，只停剔除与矿石框。
 */
class XraySelfCheckTest {

    @AfterEach
    void tearDown() {
        TestSupport.resetClient();
    }

    @Test
    void triggersOnlyWhenEnoughSamplesAndNothingVisible() {
        assertTrue(XrayModule.cullingExhausted(2000, 0));
        assertTrue(XrayModule.cullingExhausted(5000, 0));
    }

    @Test
    void doesNotTriggerWithTooFewSamples() {
        assertFalse(XrayModule.cullingExhausted(0, 0));
        assertFalse(XrayModule.cullingExhausted(1999, 0));
    }

    @Test
    void doesNotTriggerWhenSomethingIsVisible() {
        assertFalse(XrayModule.cullingExhausted(5000, 1));
    }

    /**
     * 自检命中只停内部剔除：用户开关保持 true，且之后 shouldRender 直接放行（不再碰桥）。
     */
    @Test
    void selfCheckDisablesCullingWithoutFlippingUserToggle() throws Exception {
        GameplayHook.markWired("xrayBlock");
        XrayModule module = new XrayModule();
        module.setEnabled(true);
        try {
            field(module, "cullingWindowStartedAt").setLong(module,
                    System.currentTimeMillis() - 4000L);
            ((AtomicInteger) field(module, "cullingDecisions").get(module)).set(2500);
            ((AtomicInteger) field(module, "cullingVisible").get(module)).set(0);

            module.onTick();

            assertTrue(module.isEnabled(), "自检不得静默改写用户开关");
            // cullingDisabled 生效时 shouldRender 直接放行，不会触碰 null bridge。
            assertTrue(module.shouldRender(null, null), "内部停摆后必须放行全部方块");
        } finally {
            module.setEnabled(false);
        }
    }

    /**
     * OreDeobf 不得再改 MoveEvent：曾经把 x/z 清零，真机表现是“开 Xray 后人物走不动”。
     * 探测/发包只在 onTick 限速执行。
     */
    @Test
    void oreDeobfDoesNotRewriteMovement() throws Exception {
        NocturneClient client = NocturneClient.boot(null);
        XrayModule module = new XrayModule();
        ((BooleanValue) field(module, "oreDeobf").get(module)).set(Boolean.TRUE);
        module.setEnabled(true);
        try {
            MoveEvent event = new MoveEvent(1.5, 64.0, -2.5);
            client.events().post(event);
            assertEquals(1.5, event.x, 1e-9, "Xray 不得改写位移 x");
            assertEquals(64.0, event.y, 1e-9, "Xray 不得改写位移 y");
            assertEquals(-2.5, event.z, 1e-9, "Xray 不得改写位移 z");
        } finally {
            module.setEnabled(false);
        }
    }

    private static Field field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
