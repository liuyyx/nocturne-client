package dev.nocturne.client.game;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JniTypes} 的单元测试：验证 JVM 描述符（descriptor）的解析——参数列表与返回类型、
 * 基本类型与对象类型、数组维度、无参方法，非法输入返回 {@code null} 的容错契约，
 * 以及描述符对同名重载的真实消歧能力。
 */
class JniTypesTest {

    /** 解析所用的类加载器；描述符中的对象类型需由它加载，加载不到即视为未知类型。 */
    private static final ClassLoader LOADER = JniTypesTest.class.getClassLoader();

    /** 模拟 1.8.9 中 FontRenderer 的两个同名混淆重载（{@code a}） */
    static final class FontLike {
        @SuppressWarnings("unused")
        int a(String value, int x, int y, int color) {
            return 4;
        }

        @SuppressWarnings("unused")
        int a(String value) {
            return 1;
        }
    }

    /** 验证混合描述符能按声明顺序解析出对象与基本类型（String + 三个 int）。 */
    @Test
    void parsesPrimitivesAndObjects() {
        Class<?>[] params = JniTypes.parameterTypes("(Ljava/lang/String;III)I", LOADER);
        assertArrayEquals(new Class<?>[]{String.class, int.class, int.class, int.class}, params);
    }

    /** 验证无参方法得到空数组，且混合描述符解析结果与声明顺序一致（float/boolean/long）。 */
    @Test
    void parsesNoArgAndMixedDescriptors() {
        assertArrayEquals(new Class<?>[0], JniTypes.parameterTypes("()V", LOADER));
        assertArrayEquals(new Class<?>[]{float.class, boolean.class, long.class},
                JniTypes.parameterTypes("(FZJ)D", LOADER));
    }

    /** 验证一维数组描述符（{@code [B}、{@code [Ljava/lang/String;}）解析为对应的数组类。 */
    @Test
    void parsesArrays() {
        Class<?>[] params = JniTypes.parameterTypes("([B[Ljava/lang/String;)V", LOADER);
        assertEquals(2, params.length);
        assertEquals(byte[].class, params[0]);
        assertEquals(String[].class, params[1]);
    }

    /**
     * 验证返回类型解析与参数解析共用同一套描述符规则（含 {@code void}）。
     */
    @Test
    void resolvesReturnTypes() {
        assertEquals(int.class, JniTypes.returnType("(Ljava/lang/String;III)I", LOADER));
        assertEquals(void.class, JniTypes.returnType("()V", LOADER));
        assertEquals(String.class, JniTypes.returnType("(I)Ljava/lang/String;", LOADER));
    }

    /**
     * 验证容错契约：{@code null} 输入、缺少左括号、括号不配对、类无法加载时
     * 一律返回 {@code null}，交由调用方决定降级方式，而不是抛出异常。
     */
    @Test
    void rejectsMalformedDescriptorsOrUnknownClasses() {
        assertNull(JniTypes.parameterTypes(null, LOADER));
        assertNull(JniTypes.parameterTypes("I)I", LOADER));
        assertNull(JniTypes.parameterTypes("(Ljava/lang/String", LOADER));
        assertNull(JniTypes.parameterTypes("(Ldoes/not/Exist;)V", LOADER));
    }

    /**
     * L-09 / M-74 / M-80 回归：非法描述符必须整体拒绝。
     *
     * <p>参数列表里出现 {@code V}、数组元素为 {@code void}、缺少返回类型或右括号，
     * 都必须返回 {@code null}——否则会把「合法但叫不动」的描述符交给反射。
     */
    @Test
    void rejectsVoidParametersAndTruncatedDescriptors() {
        assertNull(JniTypes.parameterTypes("(V)V", LOADER), "void parameter is illegal");
        assertNull(JniTypes.parameterTypes("([V)V", LOADER), "void arrays are illegal");
        assertNull(JniTypes.parameterTypes("(Ljava/lang/String;III)", LOADER), "missing return type");
        assertNull(JniTypes.parameterTypes("(Ljava/lang/String;III", LOADER), "missing closing paren");
        assertNull(JniTypes.parameterTypes("(Ljava/lang/String;)", LOADER), "empty return type");
        assertNull(JniTypes.returnType("()", LOADER), "missing return type");
        assertNull(JniTypes.returnType("(", LOADER), "unterminated descriptor");
    }

    /**
     * L-09 回归：描述符必须是区分重载的可靠依据，且解析结果能真正定位到不同方法。
     *
     * <p>旧用例用「空列表长度 == 0」的恒真断言充数，从未把描述符用到真实方法查找上。
     */
    @Test
    void disambiguatesOverloadsSharingAName() throws Exception {
        Class<?>[] drawString = JniTypes.parameterTypes("(Ljava/lang/String;III)I", LOADER);
        Class<?>[] width = JniTypes.parameterTypes("(Ljava/lang/String;)I", LOADER);
        assertEquals(4, drawString.length);
        assertEquals(1, width.length);

        // 描述符解析出的形参表必须能在真实类上定位到两个不同的重载
        Method draw = FontLike.class.getDeclaredMethod("a", drawString);
        Method measure = FontLike.class.getDeclaredMethod("a", width);
        FontLike target = new FontLike();
        assertEquals(4, draw.invoke(target, "x", 1, 2, 3));
        assertEquals(1, measure.invoke(target, "x"));

        assertTrue(draw.getParameterCount() > measure.getParameterCount());
    }
}
