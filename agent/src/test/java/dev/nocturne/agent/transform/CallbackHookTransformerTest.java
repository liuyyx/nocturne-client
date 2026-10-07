package dev.nocturne.agent.transform;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link CallbackHookTransformer} 的行为测试。
 *
 * <p>不只断言「字节码里多了一次调用」，而是把打补丁后的类**真正定义出来并调用**，看钩子是否拿到了
 * 正确的那个对象：代际 B/C 的绘制上下文只能从形参取，槽位算错（静态方法用了 1、实例方法用了 0）
 * 时 JVM 会直接抛 VerifyError 或把 {@code this} 交出去，必须由测试钉住。
 */
class CallbackHookTransformerTest {

    /** 钩子落点：记录被交出来的对象。 */
    public static final class Sink {
        /** 最近一次被交出来的对象。 */
        public static Object last;
        /** 调用次数。 */
        public static int calls;
        /** 事件序列：钉住"钩子在方法开头还是末尾被调用"。 */
        public static final java.util.List<String> trace = new java.util.ArrayList<>();

        /** 替身方法自己的方法体（在钩子之前或之后执行，取决于注入位置）。 */
        public static void body() {
            trace.add("body");
        }

        /** 钩子方法本体（签名必须与转换器插入的调用一致）。 */
        public static void accept(Object value) {
            last = value;
            calls++;
            trace.add("hook");
        }

        /** 清空记录。 */
        static void reset() {
            last = null;
            calls = 0;
            trace.clear();
        }
    }

    /** 钩子类的内部名（斜杠形式）。 */
    private static final String HOOK_OWNER = CallbackHookTransformerTest.class.getName().replace('.', '/') + "$Sink";

    /** 每个用例前清空钩子记录。 */
    @BeforeEach
    void resetSink() {
        Sink.reset();
    }

    /**
     * 生成一个最小替身类：一个构造器 + 一个目标方法（方法体只有 {@code return}）。
     *
     * @param methodName  目标方法名
     * @param descriptor  目标方法描述符
     * @param accessFlags 目标方法访问标志（是否 static 由它决定槽位）
     * @return 类字节码（Java 8）
     */
    private static byte[] sampleClass(String methodName, String descriptor, int accessFlags) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "dev/nocturne/agent/transform/SubjectSample", null, "java/lang/Object", null);

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

    /** 把给定字节码定义为 {@code dev.nocturne.agent.transform.SubjectSample} 并返回类对象。 */
    private static Class<?> define(byte[] bytecode) throws ClassNotFoundException {
        return new ClassLoader(CallbackHookTransformerTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                return defineClass(name, bytecode, 0, bytecode.length);
            }
        }.loadClass("dev.nocturne.agent.transform.SubjectSample");
    }

    /** 实例方法：首个形参在槽位 1（槽位 0 是 this），钩子必须拿到那个实参而不是 this。 */
    @Test
    void capturesTheFirstArgumentOfAnInstanceMethod() throws Exception {
        CallbackHookTransformer transformer = new CallbackHookTransformer(
                "dev/nocturne/agent/transform/SubjectSample", "handle", "(Ljava/lang/Object;)V", HOOK_OWNER, "accept", false);

        byte[] patched = transformer.transform(null, "dev/nocturne/agent/transform/SubjectSample", null, null,
                sampleClass("handle", "(Ljava/lang/Object;)V", Opcodes.ACC_PUBLIC));
        assertNotNull(patched, "target method must be patched");

        Object subject = define(patched).getConstructor().newInstance();
        Object argument = new Object();
        subject.getClass().getMethod("handle", Object.class).invoke(subject, argument);

        assertEquals(1, Sink.calls);
        assertEquals(argument, Sink.last, "instance method must hand over its argument, not `this`");
    }

    /** 静态方法：首个形参在槽位 0。 */
    @Test
    void capturesTheFirstArgumentOfAStaticMethod() throws Exception {
        CallbackHookTransformer transformer = new CallbackHookTransformer(
                "dev.nocturne.agent.transform.SubjectSample", "handleStatic", "(Ljava/lang/Object;)V", HOOK_OWNER, "accept", false);

        byte[] patched = transformer.transform(null, "dev/nocturne/agent/transform/SubjectSample", null, null,
                sampleClass("handleStatic", "(Ljava/lang/Object;)V", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC));
        assertNotNull(patched);

        Object argument = new Object();
        define(patched).getMethod("handleStatic", Object.class).invoke(null, argument);

        assertEquals(1, Sink.calls);
        assertEquals(argument, Sink.last, "static method must hand over argument slot 0");
    }

    /** 无参方法没有可交出去的对象：不命中，返回 null（JVM 沿用原字节码）。 */
    @Test
    void leavesParameterlessMethodsUntouched() {
        CallbackHookTransformer transformer = new CallbackHookTransformer(
                "dev/nocturne/agent/transform/SubjectSample", "tick", "()V", HOOK_OWNER, "accept", false);

        assertNull(transformer.transform(null, "dev/nocturne/agent/transform/SubjectSample", null, null,
                sampleClass("tick", "()V", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)));
    }

    /** 首个形参是 long/double 时槽位语义不同且不是我们要的对象：不命中。 */
    @Test
    void leavesPrimitiveFirstArgumentsUntouched() {
        CallbackHookTransformer transformer = new CallbackHookTransformer(
                "dev/nocturne/agent/transform/SubjectSample", "resize", "(JI)V", HOOK_OWNER, "accept", false);

        assertNull(transformer.transform(null, "dev/nocturne/agent/transform/SubjectSample", null, null,
                sampleClass("resize", "(JI)V", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)));
    }

    /** 类名/方法名/描述符任一不匹配都不得改动：返回 null。 */
    @Test
    void requiresExactClassMethodAndDescriptorMatch() {
        CallbackHookTransformer transformer = new CallbackHookTransformer(
                "dev/nocturne/agent/transform/SubjectSample", "handle", "(Ljava/lang/Object;)V", HOOK_OWNER, "accept", false);
        byte[] bytes = sampleClass("handle", "(Ljava/lang/Object;)V", Opcodes.ACC_PUBLIC);

        assertNull(transformer.transform(null, "other/Thing", null, null, bytes));
        assertNull(transformer.transform(null, "dev/nocturne/agent/transform/SubjectSample", null, null,
                sampleClass("handleOther", "(Ljava/lang/Object;)V", Opcodes.ACC_PUBLIC)));
        assertNull(transformer.transform(null, "dev/nocturne/agent/transform/SubjectSample", null, null,
                sampleClass("handle", "(Ljava/lang/String;)V", Opcodes.ACC_PUBLIC)));
    }

    /** 损坏的字节码不得抛出：返回 null 并放弃本次转换。 */
    @Test
    void toleratesGarbageInput() {
        CallbackHookTransformer transformer = new CallbackHookTransformer(
                "dev/nocturne/agent/transform/SubjectSample", "handle", "(Ljava/lang/Object;)V", HOOK_OWNER, "accept", false);

        assertNull(transformer.transform(null, "dev/nocturne/agent/transform/SubjectSample", null, null, new byte[]{1, 2, 3}));
    }

    /**
     * 末尾注入：钩子必须在目标方法**自己的方法体之后**被调用。
     *
     * <p>绘制入口（{@code Hud.extractRenderState}）自己也要往同一个绘制上下文里画 HUD，钩子若在方法
     * 开头触发，叠加层会被随后画的内容盖住——这里用带可观察副作用的替身方法钉住调用顺序。
     */
    @Test
    void injectsAtTheEndWhenAskedTo() throws Exception {
        CallbackHookTransformer transformer = new CallbackHookTransformer(
                "dev/nocturne/agent/transform/SubjectSample", "handle", "(Ljava/lang/Object;)V",
                HOOK_OWNER, "accept", true);

        byte[] patched = transformer.transform(null, "dev/nocturne/agent/transform/SubjectSample", null, null,
                bodyClass("handle", "(Ljava/lang/Object;)V", Opcodes.ACC_PUBLIC));
        assertNotNull(patched, "target method must be patched");

        Object subject = define(patched).getConstructor().newInstance();
        subject.getClass().getMethod("handle", Object.class).invoke(subject, new Object());

        assertEquals(java.util.Arrays.asList("body", "hook"), Sink.trace,
                "with atMethodEnd the hook must run after the method's own body");
    }

    /** 对照：不要求末尾注入时，钩子在方法**开头**触发（先画叠加层，随后被目标方法盖住）。 */
    @Test
    void injectsAtTheHeadByDefault() throws Exception {
        CallbackHookTransformer transformer = new CallbackHookTransformer(
                "dev/nocturne/agent/transform/SubjectSample", "handle", "(Ljava/lang/Object;)V",
                HOOK_OWNER, "accept", false);

        byte[] patched = transformer.transform(null, "dev/nocturne/agent/transform/SubjectSample", null, null,
                bodyClass("handle", "(Ljava/lang/Object;)V", Opcodes.ACC_PUBLIC));
        assertNotNull(patched, "target method must be patched");

        Object subject = define(patched).getConstructor().newInstance();
        subject.getClass().getMethod("handle", Object.class).invoke(subject, new Object());

        assertEquals(java.util.Arrays.asList("hook", "body"), Sink.trace,
                "without atMethodEnd the hook must run before the method's own body");
    }

    /**
     * 生成"方法体有可观察副作用"的替身类：目标方法先调用 {@code Sink.body()} 再返回。
     *
     * <p>与 {@link #sampleClass} 的差别只在方法体——用它才能分辨钩子插在方法体的前面还是后面。
     */
    private static byte[] bodyClass(String methodName, String descriptor, int accessFlags) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "dev/nocturne/agent/transform/SubjectSample",
                null, "java/lang/Object", null);

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        MethodVisitor method = writer.visitMethod(accessFlags, methodName, descriptor, null, null);
        method.visitCode();
        method.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK_OWNER, "body", "()V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
