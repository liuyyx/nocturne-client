package dev.nocturne.client.render;

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

    /** 模拟 {@code OptionInstance<Integer>}：取值的入口是 {@code get()}。 */
    public static final class FakeOption {
        private final Object value;

        FakeOption(Object value) {
            this.value = value;
        }

        /** 与真实 {@code OptionInstance#get()} 同名同形（无参、返回泛型值）。 */
        public Object get() {
            return value;
        }
    }

    /** 没有 {@code get()} 的对象：必须判为读不到，而不是抛异常或当成 0。 */
    public static final class NoGetter {
    }

    @Test
    void readsPlainNumbers() {
        assertEquals(70d, CameraState.number(Integer.valueOf(70)), 0.001);
        assertEquals(70.5d, CameraState.number(Double.valueOf(70.5)), 0.001);
        assertNull(CameraState.number(null));
        assertNull(CameraState.number("70"));
    }

    @Test
    void readsOptionInstanceThroughGet() {
        assertEquals(95d, CameraState.fovOf(new FakeOption(Integer.valueOf(95))), 0.001);
        // 1.16.5 起 fov 的泛型参数是 Integer，但表里也可能是其它数字类型
        assertEquals(80d, CameraState.fovOf(new FakeOption(Double.valueOf(80d))), 0.001);
    }

    @Test
    void returnsNullWhenTheValueIsNotUsable() {
        assertNull(CameraState.fovOf(null));
        assertNull(CameraState.fovOf(new NoGetter()));
        assertNull(CameraState.fovOf(new FakeOption(null)), "OptionInstance without a value");
        assertNull(CameraState.fovOf(new FakeOption("70")), "non-numeric value");
    }
}
