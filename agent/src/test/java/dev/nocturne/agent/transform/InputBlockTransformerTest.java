package dev.nocturne.agent.transform;

import dev.nocturne.client.runtime.InputBlock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link InputBlockTransformer} 的行为测试。
 *
 * <p>钉住的是「占用输入时游戏入口真的被短路」：把打补丁后的类**真正定义出来并调用**，因为这里插入的
 * 是**分支**（Java 7+ 强制栈帧，插错就是 {@code VerifyError}），只断言"字节码里多了条指令"没有意义。
 *
 * <p>还要钉住"不占用时必须放行"：短路条件写反的症状是游戏彻底收不到输入，比原 bug 更严重。
 */
class InputBlockTransformerTest {

    /** 被短路的方法体落点：记下自己被执行过。 */
    public static final class Sink {
        /** 方法体被执行过几次。 */
        static int bodyCalls;

        /** 替身方法的方法体。 */
        public static void body() {
            bodyCalls++;
        }
    }

    /** 假的目标类名（内部名）。 */
    private static final String TARGET = "dev/nocturne/agent/transform/InputSubject";
    /** 钩子类内部名：直接用真实的 {@link InputBlock}，测试与生产走同一份判定。 */
    private static final String HOOK_OWNER = InputBlock.class.getName().replace('.', '/');

    @BeforeEach
    void reset() {
        Sink.bodyCalls = 0;
        InputBlock.setBlocked(false);
    }

    /**
     * 生成一个最小替身类：构造器 + 一个目标方法（方法体只有对 {@link Sink#body()} 的调用）。
     *
     * <p>类版本取 Java 8（≥ 50）：有分支的方法必须有栈帧，转换器漏写帧时这里会直接 {@code VerifyError}。
     *
     * @param methodName 目标方法名
     * @param descriptor 目标方法描述符
     * @return 类字节码
     */
    private static byte[] sampleClass(String methodName, String descriptor) {
        return sampleClass(methodName, descriptor, null);
    }

    /**
     * 生成替身类；{@code booleanResult} 非空时，目标方法返回该常量（用于观察短路时的返回值）。
     *
     * @param methodName    目标方法名
     * @param descriptor    目标方法描述符
     * @param booleanResult {@code null} 表示方法体只调用 {@link Sink#body()}；否则返回该布尔
     * @return 类字节码
     */
    private static byte[] sampleClass(String methodName, String descriptor, Boolean booleanResult) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, TARGET, null, "java/lang/Object", null);

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, methodName, descriptor, null, null);
        method.visitCode();
        method.visitMethodInsn(Opcodes.INVOKESTATIC, Sink.class.getName().replace('.', '/'),
                "body", "()V", false);
        Label end = new Label();
        method.visitLabel(end);
        if (booleanResult == null) {
            method.visitInsn(Opcodes.RETURN);
        } else {
            method.visitInsn(booleanResult ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
            method.visitInsn(Opcodes.IRETURN);
        }
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** 把字节码定义为 {@link #TARGET} 并返回类对象。 */
    private static Class<?> define(byte[] bytecode) {
        return new ClassLoader(InputBlockTransformerTest.class.getClassLoader()) {
            Class<?> define() {
                return defineClass(TARGET.replace('/', '.'), bytecode, 0, bytecode.length);
            }
        }.define();
    }

    private static byte[] transform(byte[] original, String method, String descriptor) {
        return new InputBlockTransformer(TARGET, method, descriptor, HOOK_OWNER, "shouldBlock")
                .transform(InputBlockTransformerTest.class.getClassLoader(), TARGET, null, null, original);
    }

    @Test
    void blocksTheTargetMethodWhileInputIsBlocked() throws Exception {
        Class<?> transformed = define(transform(sampleClass("hit", "()V"), "hit", "()V"));
        Method hit = transformed.getMethod("hit");

        hit.invoke(transformed.getDeclaredConstructor().newInstance());
        assertEquals(1, Sink.bodyCalls, "not blocked: the original body must run");

        InputBlock.setBlocked(true);
        hit.invoke(transformed.getDeclaredConstructor().newInstance());
        assertEquals(1, Sink.bodyCalls, "blocked: the original body must not run");
    }

    /** 形参含 long/引用/基本类型时，插入的满帧必须与描述符一致（否则定义类时就 VerifyError）。 */
    @Test
    void handlesMethodsWithArguments() throws Exception {
        String descriptor = "(JLjava/lang/String;I)V";
        byte[] patched = transform(sampleClass("onEvent", descriptor), "onEvent", descriptor);
        assertNotNull(patched);
        Class<?> transformed = define(patched);
        Method method = transformed.getMethod("onEvent", long.class, String.class, int.class);
        Object instance = transformed.getDeclaredConstructor().newInstance();

        method.invoke(instance, 1L, "x", 2);
        assertEquals(1, Sink.bodyCalls);

        InputBlock.setBlocked(true);
        method.invoke(instance, 1L, "x", 2);
        assertEquals(1, Sink.bodyCalls);
    }

    /** 描述符不匹配（同名不同签名）时不得改字节码：插错了会破坏别的重载。 */
    @Test
    void leavesOtherDescriptorsAlone() {
        byte[] original = sampleClass("hit", "()V");
        assertNull(transform(original, "hit", "(I)V"), "descriptor mismatch must pass through");
        assertNull(transform(original, "other", "()V"), "name mismatch must pass through");
    }

    /**
     * 有返回值的方法（典型是 {@code mouseClicked} 这种布尔入口）：短路时必须返回
     * "已处理"（{@code true}），否则调用方会当成没处理、走到别的分支（点击穿透的另一种形态）。
     */
    @Test
    void returnsHandledForBooleanMethods() throws Exception {
        byte[] patched = transform(sampleClass("click", "()Z", Boolean.FALSE), "click", "()Z");
        assertNotNull(patched);
        Class<?> transformed = define(patched);
        Method click = transformed.getMethod("click");
        Object instance = transformed.getDeclaredConstructor().newInstance();

        assertEquals(Boolean.FALSE, click.invoke(instance), "not blocked: original result");
        assertEquals(1, Sink.bodyCalls);

        InputBlock.setBlocked(true);
        assertEquals(Boolean.TRUE, click.invoke(instance), "blocked: must report handled");
        assertEquals(1, Sink.bodyCalls, "blocked: body must not run");
    }
}
