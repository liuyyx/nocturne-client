package dev.nocturne.ui.fatality;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.module.Module;
import dev.nocturne.client.value.GroupValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.MultiBoolValue;
import dev.nocturne.client.value.Value;
import org.junit.jupiter.api.Test;

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
