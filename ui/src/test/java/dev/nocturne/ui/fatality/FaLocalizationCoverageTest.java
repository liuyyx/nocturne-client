package dev.nocturne.ui.fatality;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.module.Module;
import dev.nocturne.client.value.GroupValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.MultiBoolValue;
import dev.nocturne.client.value.Value;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 所有真实注册模块的显示文本都必须有中文词条，避免只翻译演示模块。 */
class FaLocalizationCoverageTest {

    @Test
    void everyRegisteredModuleSettingAndOptionHasChineseText() {
        FaStrings.setChinese(true);
        Set<String> missing = new TreeSet<String>();
        for (Module module : NocturneClient.boot(null).modules().all()) {
            check(module.name(), missing);
            for (Value<?> value : module.values()) {
                checkValue(value, missing);
            }
        }
        assertTrue(missing.isEmpty(), "缺少中文词条 (" + missing.size() + "): " + missing);
    }

    /**
     * 恢复 JVM 级静态单例：{@code boot(null)} 会把实例装进 {@code NocturneClient.instance}，
     * 其中默认启用的 "Better Scoreboard" 实现了 WorldOverlay，于是同 JVM 后续的
     * {@code GuiOverlayInputTest} 里 {@code hasWorldOverlays()} 为真，空 HUD 也会开绘制帧。
     * 这里只在测试侧清空，不改变生产默认模块。
     */
    @AfterAll
    static void resetClientSingleton() throws Exception {
        Field instance = NocturneClient.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    private static void checkValue(Value<?> value, Set<String> missing) {
        check(value.name(), missing);
        if (value instanceof ModeValue) {
            for (String option : ((ModeValue) value).options()) {
                check(option, missing);
            }
        }
        if (value instanceof MultiBoolValue) {
            for (String option : ((MultiBoolValue) value).options()) {
                check(option, missing);
            }
        }
        if (value instanceof GroupValue) {
            for (Value<?> child : ((GroupValue) value).children()) {
                checkValue(child, missing);
            }
        }
    }

    private static void check(String english, Set<String> missing) {
        if (english == null || english.isEmpty() || !english.matches(".*[A-Za-z].*")) {
            return;
        }
        if (english.equals(FaStrings.t(english))) {
            missing.add(english);
        }
    }
}
