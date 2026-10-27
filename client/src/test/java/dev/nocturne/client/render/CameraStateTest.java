package dev.nocturne.client.render;

import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.mapping.Mapping;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link CameraState#fovOf} 的契约：视场角在两种形态下都要能读出来。
 *
 * <p>为什么单钉这一条：1.8.9–1.16.4 的 {@code Options#fov} 是 {@code int}，1.16.5 起是
 * {@code OptionInstance<Integer>}。后者被当成数字读会拿到 {@code null}，于是整帧覆盖物不画，
 * 而日志里只有一句"cannot read Options#fov"——26.3 实测踩过，且症状（世界里什么都没有）
 * 与"投影算错"难以区分。
 */
class CameraStateTest {

    /** 模拟运行期重映射：规范 get 是 mappedGet，get 本身是诱饵。 */
    public static final class FakeOption {
        private final Object value;

        FakeOption(Object value) {
            this.value = value;
        }

        public Object get() {
            return Integer.valueOf(5);
        }

        public Object mappedGet() {
            return value;
        }
    }

    private static final Mapping OPTION_MAPPING = new Mapping() {
        @Override
        public String describe() {
            return "test remapping";
        }

        @Override
        public String className(ClassType type) {
            return type.canonicalName();
        }

        @Override
        public String methodName(ClassType owner, String canonicalName, String descriptor) {
            return owner == ClassType.OPTION_INSTANCE && "get".equals(canonicalName)
                    ? "mappedGet" : canonicalName;
        }

        @Override
        public String fieldName(ClassType owner, String canonicalName) {
            return canonicalName;
        }

        @Override
        public boolean isIdentity() {
            return false;
        }
    };

    private static final GameBridge BRIDGE = new GameBridge(null, OPTION_MAPPING);

    /** 没有 {@code get()} 的对象：必须判为读不到，而不是抛异常或当成 0。 */
    public static final class NoGetter {
    }

    @Test
    void readsPlainNumbers() {
        assertEquals(70d, CameraState.number(Integer.valueOf(70)), 0.001);
        assertEquals(70.5d, CameraState.number(Double.valueOf(70.5)), 0.001);
        assertEquals(70d, CameraState.fovOf(BRIDGE, Integer.valueOf(70)), 0.001);
        assertNull(CameraState.number(null));
        assertNull(CameraState.number("70"));
    }

    @Test
    void readsOptionInstanceThroughMappedGet() {
        assertEquals(95d, CameraState.fovOf(BRIDGE, new FakeOption(Integer.valueOf(95))), 0.001);
        assertEquals(80d, CameraState.fovOf(BRIDGE, new FakeOption(Double.valueOf(80d))), 0.001);
    }

    @Test
    void returnsNullWhenTheValueIsNotUsable() {
        assertNull(CameraState.fovOf(BRIDGE, null));
        assertNull(CameraState.fovOf(BRIDGE, new NoGetter()));
        assertNull(CameraState.fovOf(BRIDGE, new FakeOption(null)), "OptionInstance without a value");
        assertNull(CameraState.fovOf(BRIDGE, new FakeOption("70")), "non-numeric value");
    }
}
