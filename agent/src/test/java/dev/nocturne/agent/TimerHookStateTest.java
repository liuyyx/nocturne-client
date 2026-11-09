package dev.nocturne.agent;

import dev.nocturne.client.mapping.ObfuscatedMapping;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Timer 钩子的两态判定回归。
 *
 * <p>1) premain 阶段目标类未加载时转换器只处于 {@code HOOK_REGISTERED}（等类加载），
 * 这正是常态；旧实现用 {@code registerGameplayTransformer}（只认 HOOK_WOVEN）把它当失败，
 * 能力表于是报「timer 不可用」。
 *
 * <p>2) 1.21+ 的 {@code DeltaTracker} 是接口，{@code getGameTimeDeltaTicks} 没有方法体；
 * 在这里织入永远不生效。必须显式判为不可用，把调速交给实现类的 {@code msPerTick} 字段，
 * 不能写一个假 woven。
 */
class TimerHookStateTest {

    @Test
    void registeredButNotYetLoadedCountsAsWired() {
        assertFalse(NocturneAgent.hookWired(NocturneAgent.HOOK_FAILED));
        assertTrue(NocturneAgent.hookWired(NocturneAgent.HOOK_REGISTERED),
                "waiting for class load is the normal premain state and must count as wired");
        assertTrue(NocturneAgent.hookWired(NocturneAgent.HOOK_WOVEN));
    }

    @Test
    void modernInterfaceIsDetectedFromTheMappingTable() {
        assertFalse(NocturneAgent.timerHookTargetsInterface(load("1.8.9")));
        assertFalse(NocturneAgent.timerHookTargetsInterface(load("1.12.2")));
        assertFalse(NocturneAgent.timerHookTargetsInterface(load("1.16.5")));
        assertFalse(NocturneAgent.timerHookTargetsInterface(load("1.20.1")));
        assertTrue(NocturneAgent.timerHookTargetsInterface(load("1.21.1")),
                "1.21+ DeltaTracker is an interface; the hook must be reported unavailable");
        assertTrue(NocturneAgent.timerHookTargetsInterface(load("26.3")));
    }

    private static dev.nocturne.client.mapping.Mapping load(String version) {
        return ObfuscatedMapping.load("/mappings-" + version + ".json");
    }
}
