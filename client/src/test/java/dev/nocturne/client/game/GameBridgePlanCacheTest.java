package dev.nocturne.client.game;

import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.mapping.IdentityMapping;
import dev.nocturne.client.mapping.Mapping;
import dev.nocturne.client.mapping.MethodCandidate;
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
    static class ZeroArityDescriptorMapping implements Mapping {
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

    /** 首个描述符调用抛异常，第二个候选才可用的映射。 */
    static class FailingCandidateMapping extends ZeroArityDescriptorMapping {
        @Override
        public java.util.List<MethodCandidate> methodCandidates(ClassType owner, String canonicalName) {
            if (!"pick".equals(canonicalName)) {
                return super.methodCandidates(owner, canonicalName);
            }
            return java.util.Arrays.asList(
                    new MethodCandidate("pick", "(Ljava/lang/String;)Ljava/lang/String;"),
                    new MethodCandidate("pick", "(Ljava/lang/Object;)Ljava/lang/String;"));
        }
    }

    /** 只提供一个会失败的接口描述符，实际可用重载需按实参推断。 */
    static final class CharSequenceDescriptorMapping extends ZeroArityDescriptorMapping {
        @Override
        public String methodDescriptor(ClassType owner, String canonicalName) {
            return "pick".equals(canonicalName)
                    ? "(Ljava/lang/CharSequence;)Ljava/lang/String;"
                    : null;
        }
    }

    static final class FailingOverloaded {
        int stringCalls;
        int objectCalls;

        @SuppressWarnings("unused")
        String pick(String value) {
            stringCalls++;
            throw new IllegalStateException("expected test failure");
        }

        @SuppressWarnings("unused")
        String pick(Object value) {
            objectCalls++;
            return "object:" + value;
        }
    }

    static final class InferredRetry {
        int interfaceCalls;
        int stringCalls;

        @SuppressWarnings("unused")
        String pick(CharSequence value) {
            interfaceCalls++;
            throw new IllegalStateException("expected test failure");
        }

        @SuppressWarnings("unused")
        String pick(String value) {
            stringCalls++;
            return "string:" + value;
        }
    }

    static final class NullResult {
        int calls;

        @SuppressWarnings("unused")
        String pick() {
            calls++;
            return null;
        }
    }

    static final class CountingMapping extends ZeroArityDescriptorMapping {
        int candidateQueries;

        @Override
        public java.util.List<MethodCandidate> methodCandidates(ClassType owner, String canonicalName) {
            candidateQueries++;
            return super.methodCandidates(owner, canonicalName);
        }
    }

    static final class StaticMapping extends FailingCandidateMapping {
        @Override
        public String className(ClassType type) {
            return StaticOverloaded.class.getName();
        }
    }

    static final class StaticOverloaded {
        static int stringCalls;
        static int objectCalls;

        @SuppressWarnings("unused")
        static String pick(String value) {
            stringCalls++;
            throw new IllegalStateException("expected test failure");
        }

        @SuppressWarnings("unused")
        static String pick(Object value) {
            objectCalls++;
            return "static:" + value;
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

    @Test
    void failedInvocationDoesNotPinTheFirstCandidate() {
        GameBridge bridge = new GameBridge(null, new FailingCandidateMapping());
        FailingOverloaded target = new FailingOverloaded();

        assertEquals("object:x", bridge.callMapped(target, ClassType.LOCAL_PLAYER, "pick", "x"));
        assertEquals("object:y", bridge.callMapped(target, ClassType.LOCAL_PLAYER, "pick", "y"));
        assertEquals(1, target.stringCalls, "失败候选不得进入后续调用计划");
        assertEquals(2, target.objectCalls);
    }

    @Test
    void failedStaticInvocationDoesNotPinTheFirstCandidate() {
        StaticOverloaded.stringCalls = 0;
        StaticOverloaded.objectCalls = 0;
        GameBridge bridge = new GameBridge(null, new StaticMapping());

        assertEquals("static:x", bridge.callStaticMapped(ClassType.LOCAL_PLAYER, "pick", "x"));
        assertEquals("static:y", bridge.callStaticMapped(ClassType.LOCAL_PLAYER, "pick", "y"));
        assertEquals(1, StaticOverloaded.stringCalls);
        assertEquals(2, StaticOverloaded.objectCalls);
    }

    @Test
    void failedDescriptorOverloadRetriesWithInferredParameters() {
        GameBridge bridge = new GameBridge(null, new CharSequenceDescriptorMapping());
        InferredRetry target = new InferredRetry();

        assertEquals("string:x", bridge.callMapped(target, ClassType.LOCAL_PLAYER, "pick", "x"));
        assertEquals("string:y", bridge.callMapped(target, ClassType.LOCAL_PLAYER, "pick", "y"));
        assertEquals(1, target.interfaceCalls);
        assertEquals(2, target.stringCalls);
    }

    @Test
    void successfulNullResultIsStillCached() {
        CountingMapping mapping = new CountingMapping();
        GameBridge bridge = new GameBridge(null, mapping);
        NullResult target = new NullResult();

        assertNull(bridge.callMapped(target, ClassType.LOCAL_PLAYER, "pick"));
        assertNull(bridge.callMapped(target, ClassType.LOCAL_PLAYER, "pick"));
        assertEquals(2, target.calls, "缓存计划仍要正常执行目标方法");
        assertEquals(1, mapping.candidateQueries, "合法 null 返回值不得被误判为失败并重新解析");
    }
}
