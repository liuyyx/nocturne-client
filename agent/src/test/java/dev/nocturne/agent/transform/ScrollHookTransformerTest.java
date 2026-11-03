package dev.nocturne.agent.transform;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link ScrollHookTransformer} 的行为测试。
 *
 * <p>钉的是槽位：{@code MouseHandler#onScroll(JDD)V} 是实例方法，纵向增量在<b>槽位 5</b>（0=this、
 * 1-2=long、3-4=第一个 double）；槽位算错时 JVM 要么抛 VerifyError（读了不存在的槽），要么把
 * {@code xoffset} 当成 {@code yoffset} 交出去——后者没有任何异常，表现只是「滚轮方向反了」或
 * 「横滚触发了竖滚」，线上极难看出来。
 */
class ScrollHookTransformerTest {

    /** 钩子落点：记录被交出来的增量。 */
    public static final class Sink {
        /** 最近一次被交出来的增量。 */
        public static double last;
        /** 调用次数。 */
        public static int calls;

        /** 钩子方法本体（签名必须与转换器插入的调用一致）。 */
        public static void accept(double value) {
            last = value;
            calls++;
        }

        /** 清空记录。 */
        static void reset() {
            last = 0d;
            calls = 0;
        }
    }

    /** 钩子类的内部名（斜杠形式）。 */
    private static final String HOOK_OWNER =
            ScrollHookTransformerTest.class.getName().replace('.', '/') + "$Sink";

    /** 目标类内部名。 */
    private static final String SUBJECT = "dev/nocturne/agent/transform/ScrollSubject";

    /** {@code MouseHandler#onScroll} 的描述符。 */
    private static final String DESCRIPTOR = "(JDD)V";

    /** 每个用例前清空钩子记录。 */
    @BeforeEach
    void resetSink() {
        Sink.reset();
    }

    /**
     * 生成一个最小替身类：一个构造器 + 一个空体目标方法。
     *
     * @param methodName  目标方法名
     * @param descriptor  目标方法描述符
     * @param accessFlags 目标方法访问标志
     * @return 类字节码（Java 8）
     */
    private static byte[] sampleClass(String methodName, String descriptor, int accessFlags) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, SUBJECT, null, "java/lang/Object", null);

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        MethodVisitor method = writer.visitMethod(accessFlags, methodName, descriptor, null, null);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** 把给定字节码定义为 {@link #SUBJECT} 并返回类对象。 */
    private static Class<?> define(byte[] bytecode) throws ClassNotFoundException {
        return new ClassLoader(ScrollHookTransformerTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                return defineClass(name, bytecode, 0, bytecode.length);
            }
        }.loadClass(SUBJECT.replace('/', '.'));
    }

    /** 实例方法：第三个形参在槽位 5，钩子必须拿到 yoffset 而不是 xoffset。 */
    @Test
    void handsOverTheVerticalOffsetOfAnInstanceMethod() throws Exception {
        ScrollHookTransformer transformer = new ScrollHookTransformer(SUBJECT, "onScroll", DESCRIPTOR,
                HOOK_OWNER, "accept", 2);

        byte[] patched = transformer.transform(null, SUBJECT, null, null,
                sampleClass("onScroll", DESCRIPTOR, Opcodes.ACC_PUBLIC));
        assertNotNull(patched, "target method must be patched");

        Object subject = define(patched).getConstructor().newInstance();
        subject.getClass().getMethod("onScroll", long.class, double.class, double.class)
                .invoke(subject, 0L, 9.0d, -1.25d);

        assertEquals(1, Sink.calls);
        assertEquals(-1.25d, Sink.last, 1e-9, "must hand over yoffset, not xoffset");
    }

    /** 静态方法整体前移一个槽位：仍必须拿到第三个形参。 */
    @Test
    void handsOverTheVerticalOffsetOfAStaticMethod() throws Exception {
        ScrollHookTransformer transformer = new ScrollHookTransformer(SUBJECT, "onScroll", DESCRIPTOR,
                HOOK_OWNER, "accept", 2);

        byte[] patched = transformer.transform(null, SUBJECT, null, null,
                sampleClass("onScroll", DESCRIPTOR, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC));
        assertNotNull(patched, "static target method must be patched");

        Class<?> defined = define(patched);
        defined.getMethod("onScroll", long.class, double.class, double.class)
                .invoke(null, 0L, 9.0d, 3.5d);

        assertEquals(1, Sink.calls);
        assertEquals(3.5d, Sink.last, 1e-9);
    }

    /** 只织一次：多帧调用不会重复累加同一次滚动。 */
    @Test
    void invokesTheHookExactlyOncePerCall() throws Exception {
        ScrollHookTransformer transformer = new ScrollHookTransformer(SUBJECT, "onScroll", DESCRIPTOR,
                HOOK_OWNER, "accept", 2);

        byte[] patched = transformer.transform(null, SUBJECT, null, null,
                sampleClass("onScroll", DESCRIPTOR, Opcodes.ACC_PUBLIC));
        Object subject = define(patched).getConstructor().newInstance();
        java.lang.reflect.Method call = subject.getClass()
                .getMethod("onScroll", long.class, double.class, double.class);
        call.invoke(subject, 0L, 0.0d, 1.0d);
        call.invoke(subject, 0L, 0.0d, 1.0d);

        assertEquals(2, Sink.calls, "one hook call per target invocation");
    }

    /** 形参下标指向非 double（例如 long）必须在构造期就拒绝：织进去只会 VerifyError。 */
    @Test
    void rejectsNonDoubleArgumentIndex() {
        assertThrows(IllegalArgumentException.class, () -> new ScrollHookTransformer(SUBJECT,
                "onScroll", DESCRIPTOR, HOOK_OWNER, "accept", 0));
        assertThrows(IllegalArgumentException.class, () -> new ScrollHookTransformer(SUBJECT,
                "onScroll", DESCRIPTOR, HOOK_OWNER, "accept", 3));
    }

    /** 类名对不上时必须原样放行，绝不返回改过的字节码。 */
    @Test
    void ignoresUnrelatedClasses() {
        ScrollHookTransformer transformer = new ScrollHookTransformer(SUBJECT, "onScroll", DESCRIPTOR,
                HOOK_OWNER, "accept", 2);

        assertNull(transformer.transform(null, "some/other/Class", null, null,
                sampleClass("onScroll", DESCRIPTOR, Opcodes.ACC_PUBLIC)));
    }

    /** 方法名对不上时同样放行。 */
    @Test
    void ignoresUnrelatedMethods() {
        ScrollHookTransformer transformer = new ScrollHookTransformer(SUBJECT, "onScroll", DESCRIPTOR,
                HOOK_OWNER, "accept", 2);

        assertNull(transformer.transform(null, SUBJECT, null, null,
                sampleClass("onMove", DESCRIPTOR, Opcodes.ACC_PUBLIC)));
    }
}
