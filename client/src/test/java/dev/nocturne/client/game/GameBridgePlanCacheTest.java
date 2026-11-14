package dev.nocturne.client.game;

import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.mapping.IdentityMapping;
import dev.nocturne.client.mapping.Mapping;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link GameBridge} 调用计划缓存（M-73）的单元测试。
 *
 * <p>扫描类模块（Hole ESP / Xray）每 tick 对同一规范方法发起成千上万次反射调用；计划缓存命中后
 * 直接走「校验实参 → 调用」。这里用普通 Java 类钉住缓存对调用语义零改变：同键不同实参形状
 * 必须靠调用前校验回落全量解析，不能把首次解析出的重载钉死给后续调用。
 */
class GameBridgePlanCacheTest {

    /** 带多个同名重载的宿主，与 {@link GameBridgeTest.Overloaded} 同构。 */
    static final class Overloaded {
        @SuppressWarnings("unused")
        String pick() {
            return "none";
        }

        @SuppressWarnings("unused")
        String pick(int value) {
            return "int:" + value;
        }

        @SuppressWarnings("unused")
        String pick(String value) {
            return "str:" + value;
        }
    }

    /** 给 {@code pick} 配零参描述符的映射：验证描述符路径同样进计划缓存且语义不变。 */
    static final class ZeroArityDescriptorMapping implements Mapping {
        @Override
        public String describe() {
            return "zero-arity-descriptor";
        }

        @Override
        public String className(ClassType type) {
            return type.canonicalName();
        }

        @Override
        public String methodName(ClassType owner, String canonicalName, String descriptor) {
            return canonicalName;
        }

        @Override
        public String fieldName(ClassType owner, String canonicalName) {
            return canonicalName;
        }

        @Override
        public String methodDescriptor(ClassType owner, String canonicalName) {
            return "pick".equals(canonicalName) ? "()Ljava/lang/String;" : null;
        }

        @Override
        public boolean isIdentity() {
            return false;
        }
    }

    /**
     * 恒等映射下同一调用反复执行：结果稳定；同 arity 不同实参类型交替出现时，
     * 缓存里的重载必须被校验拦下、每次按实参重新解析。
     */
    @Test
    void repeatedCallsKeepPerArgumentResolution() {
        GameBridge bridge = new GameBridge(null, new IdentityMapping());
        Overloaded target = new Overloaded();

        assertEquals("int:5", bridge.callMapped(target, ClassType.LOCAL_PLAYER, "pick", 5));
        assertEquals("str:x", bridge.callMapped(target, ClassType.LOCAL_PLAYER, "pick", "x"));
        assertEquals("int:7", bridge.callMapped(target, ClassType.LOCAL_PLAYER, "pick", 7));
        assertEquals("none", bridge.callMapped(target, ClassType.LOCAL_PLAYER, "pick"));
        assertEquals("str:y", bridge.callMapped(target, ClassType.LOCAL_PLAYER, "pick", "y"));
    }

    /** 描述符路径的调用同样结果稳定（缓存命中不改变返回值）。 */
    @Test
    void descriptorDrivenCallsStayStableAcrossCacheHits() {
        GameBridge bridge = new GameBridge(null, new ZeroArityDescriptorMapping());
        Overloaded target = new Overloaded();
        assertEquals("none", bridge.callMapped(target, ClassType.LOCAL_PLAYER, "pick"));
        assertEquals("none", bridge.callMapped(target, ClassType.LOCAL_PLAYER, "pick"));
    }

    /**
     * 描述符 arity 与实参不符时降级为按实参推断的契约保持不变；
     * 无对应重载的实参形状仍返回 null（与无缓存时同一语义）。
     */
    @Test
    void incompatibleArgumentsKeepExistingFallbackSemantics() {
        GameBridge bridge = new GameBridge(null, new ZeroArityDescriptorMapping());
        Overloaded target = new Overloaded();
        assertEquals("str:x", bridge.callMapped(target, ClassType.LOCAL_PLAYER, "pick", "x"));
        assertNull(bridge.callMapped(target, ClassType.LOCAL_PLAYER, "missing"));
    }
}
