package dev.noturne.client.module;

import dev.noturne.client.NoturneClient;
import dev.noturne.client.hud.HudSink;
import dev.noturne.client.module.modules.WatermarkModule;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HUD 模块的单元测试：验证水印模块启用/禁用时正确向 {@link HudSink} 发布与撤销行、
 * UI 未安装（无汇）时不会崩溃，以及 {@link NoturneClient#boot} 默认注册水印模块。
 */
class HudModuleTest {

    /** 记录式 HUD 汇：按 id 保存行及其文本供应器，供断言查询当前挂载了哪些行。 */
    static final class RecordingSink implements HudSink {
        /** 已挂载的行，id → 文本供应器；使用 LinkedHashMap 保持插入顺序便于排查。 */
        final Map<String, Supplier<String>> lines = new LinkedHashMap<String, Supplier<String>>();

        @Override
        public void add(String id, Supplier<String> text) {
            lines.put(id, text);
        }

        @Override
        public void remove(String id) {
            lines.remove(id);
        }

        @Override
        public boolean has(String id) {
            return lines.containsKey(id);
        }
    }

    /** 验证启用模块会把水印行挂到 HUD 汇上（内容取构造参数），禁用后必须撤销该行。 */
    @Test
    void armingPublishesTheLineAndDisarmingRetractsIt() {
        RecordingSink sink = new RecordingSink();
        NoturneClient.boot(null).setHudSink(sink);

        WatermarkModule module = new WatermarkModule("test-client");
        // 启用前 HUD 汇里还没有该行。
        assertFalse(sink.has("watermark"));

        module.setEnabled(true);
        assertTrue(sink.has("watermark"));
        assertEquals("test-client", sink.lines.get("watermark").get());

        module.setEnabled(false);
        assertFalse(sink.has("watermark"), "disarming must remove the HUD line");
    }

    /** 验证 UI 尚未安装（HUD 汇为 {@code null}）时启停模块仍然安全，只是没有输出。 */
    @Test
    void armingWithoutAUiInstalledIsHarmless() {
        NoturneClient.boot(null).setHudSink(null);
        WatermarkModule module = new WatermarkModule();
        module.setEnabled(true);
        assertTrue(module.isEnabled());
        module.setEnabled(false);
    }

    /** 验证 {@link NoturneClient#boot} 默认注册水印模块，且其分类为 {@link Category#RENDER}。 */
    @Test
    void defaultClientRegistersTheWatermark() {
        NoturneClient client = NoturneClient.boot(null);
        assertTrue(client.modules().get("Watermark") != null, "Watermark must be registered on boot");
        assertEquals(Category.RENDER, client.modules().get("Watermark").category());
    }
}
