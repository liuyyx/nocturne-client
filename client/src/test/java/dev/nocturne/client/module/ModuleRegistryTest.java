package dev.nocturne.client.module;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModuleRegistry} 激活闸门测试（H-34）：豁免闸门的模块在闸门关闭时仍须被 tick，
 * 且不参与挂起/恢复；普通模块则按转换点挂起、恢复。
 */
class ModuleRegistryTest {

    /** 普通模块：受激活闸门约束，记录 tick 与挂起/恢复次数 */
    static final class Gated extends Module {
        /** {@link #onTick()} 次数 */
        int ticks;
        /** {@link #onSuspend()} 次数 */
        int suspendCalls;
        /** {@link #onResume()} 次数 */
        int resumeCalls;

        @Override
        public String name() {
            return "Gated";
        }

        @Override
        public Category category() {
            return Category.MISC;
        }

        @Override
        public void onTick() {
            ticks++;
        }

        @Override
        protected void onSuspend() {
            suspendCalls++;
        }

        @Override
        protected void onResume() {
            resumeCalls++;
        }
    }

    /** 豁免闸门的模块：闸门关闭时仍须 tick，且不得被挂起 */
    static final class Exempt extends Module {
        /** {@link #onTick()} 次数 */
        int ticks;
        /** {@link #onSuspend()} 次数；必须保持 0 */
        int suspendCalls;

        @Override
        public String name() {
            return "Exempt";
        }

        @Override
        public Category category() {
            return Category.PLAYER;
        }

        @Override
        public boolean runsWhileInactive() {
            return true;
        }

        @Override
        public void onTick() {
            ticks++;
        }

        @Override
        protected void onSuspend() {
            suspendCalls++;
        }
    }

    /** H-34：闸门关闭时豁免模块照常 tick，普通模块停摆；闸门重开后普通模块恢复 */
    @Test
    void exemptModulesKeepTickingWhileInactive() {
        ModuleRegistry registry = new ModuleRegistry();
        Gated gated = new Gated();
        Exempt exempt = new Exempt();
        registry.register(gated);
        registry.register(exempt);
        registry.setActive(true);
        gated.setEnabled(true);
        exempt.setEnabled(true);

        registry.setActive(false);
        assertEquals(1, gated.suspendCalls, "non-exempt modules must be suspended on gate close");
        assertEquals(0, exempt.suspendCalls, "exempt modules must not be suspended");

        registry.tick();
        assertEquals(0, gated.ticks, "gated module must not tick while inactive");
        assertEquals(1, exempt.ticks, "exempt module must keep ticking while inactive (AutoRespawn)");

        registry.setActive(true);
        registry.tick();
        assertEquals(1, gated.ticks, "gated module must resume after the gate reopens");
        assertEquals(1, gated.resumeCalls);
        assertEquals(2, exempt.ticks);
    }

    /** 闸门状态重复设置不得重复触发挂起/恢复 */
    @Test
    void gateTransitionsAreIdempotent() {
        ModuleRegistry registry = new ModuleRegistry();
        Gated gated = new Gated();
        registry.register(gated);
        registry.setActive(true);
        gated.setEnabled(true);

        registry.setActive(false);
        registry.setActive(false);
        assertEquals(1, gated.suspendCalls);

        registry.setActive(true);
        registry.setActive(true);
        assertEquals(1, gated.resumeCalls);
        org.junit.jupiter.api.Assertions.assertTrue(registry.isActive());
    }
}
