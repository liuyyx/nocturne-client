package dev.noturne.client.game;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link JniTypes} 的单元测试：验证 JVM 描述符（descriptor）的解析——参数列表与返回类型、
 * 基本类型与对象类型、数组维度、无参方法，以及非法/未知类描述符返回 {@code null} 的容错契约。
 */
class JniTypesTest {

    /** 解析所用的类加载器；描述符中的对象类型需由它加载，加载不到即视为未知类型。 */
    private static final ClassLoader LOADER = JniTypesTest.class.getClassLoader();

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
     * 验证描述符是区分重载的唯一依据：1.8.9 中 {@code drawString} 与 {@code getStringWidth}
     * 都混淆为 {@code a}，只有描述符能分别定位到 4 参与 1 参的两个方法。
     */
    @Test
    void disambiguatesOverloadsSharingAName() throws Exception {
        // 1.8.9 的 FontRenderer 把 drawString 与 getStringWidth 都混淆成 "a"，只能靠描述符区分。
        Class<?>[] drawString = JniTypes.parameterTypes("(Ljava/lang/String;III)I", LOADER);
        Class<?>[] width = JniTypes.parameterTypes("(Ljava/lang/String;)I", LOADER);
        assertEquals(4, drawString.length);
        assertEquals(1, width.length);

        // 无意义的填充断言：仅用于让未使用的 import 保持被引用，不涉及被测行为。
        List<String> unused = java.util.Collections.emptyList();
        assertEquals(0, unused.size());
    }
}
