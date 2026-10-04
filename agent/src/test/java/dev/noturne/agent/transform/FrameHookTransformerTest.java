package dev.noturne.agent.transform;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link FrameHookTransformer} 的单元测试。
 *
 * <p>不依赖真实游戏类：测试用 ASM 现场生成一个只含单个静态方法的最小类作为替身，验证转换器
 * 「仅在类名、方法名与描述符三者精确命中时才插桩，其余情况一律返回 {@code null}」这一契约，
 * 同时确认它对损坏字节码保持容错。
 */

class FrameHookTransformerTest {

    /** 钩子调用方内部名，与生产代码注入的目标一致。 */
    private static final String HOOK_OWNER = "dev/noturne/client/runtime/NoturneRuntime";

    /**
     * 构造一个最小替身类：只有一个 public static 方法，方法体仅一条 {@code return}。
     *
     * @param internalName 类内部名
     * @param methodName 唯一的方法名
     * @param descriptor 方法描述符
     * @return 该类的字节码（class 版本设为 Java 8）
     */
    private static byte[] sampleClass(String internalName, String methodName, String descriptor) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, methodName, descriptor, null, null);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /**
     * 统计字节码中指向 {@code NoturneRuntime.onFrame} 的调用次数。
     *
     * @param bytecode 待检查的类字节码
     * @return 钩子调用次数；本测试的期望值为 1
     */
    private static int hookCallCount(byte[] bytecode) {
        final int[] count = {0};
        // 数组充当可变计数器，供匿名内部类回写。
        new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    // 遍历所有方法，只统计指向钩子方法的调用指令。
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                                                String descriptor, boolean isInterface) {
                        if (HOOK_OWNER.equals(owner) && "onFrame".equals(name)) {
                            count[0]++;
                        }
                    }
                };
            }
        }, 0);
        return count[0];
    }

    /** 命中类名与精确描述符时，钩子应被插入且仅插入一次。 */
    @Test
    void insertsTheHookAtTheStartOfTheTargetMethod() {
        // 用 1.8.9 的混淆名 ave/av 作为目标类与方法，贴近真实插桩场景。
        FrameHookTransformer transformer =
                new FrameHookTransformer("ave", "av", "()V", HOOK_OWNER, "onFrame");

        byte[] output = transformer.transform(null, "ave", null, null, sampleClass("ave", "av", "()V"));

        assertNotNull(output, "target method must be patched");
        assertEquals(1, hookCallCount(output));
    }

    /** 类名不匹配时不作修改，返回 {@code null}。 */
    @Test
    void leavesOtherClassesUntouched() {
        FrameHookTransformer transformer =
                new FrameHookTransformer("ave", "av", "()V", HOOK_OWNER, "onFrame");
        assertNull(transformer.transform(null, "other/Thing", null, null,
                sampleClass("other/Thing", "av", "()V")));
    }

    /** 方法名不匹配时不作修改，返回 {@code null}。 */
    @Test
    void leavesOtherMethodsUntouched() {
        FrameHookTransformer transformer =
                new FrameHookTransformer("ave", "av", "()V", HOOK_OWNER, "onFrame");
        assertNull(transformer.transform(null, "ave", null, null,
                sampleClass("ave", "somethingElse", "()V")));
    }

    /** 描述符须精确匹配：只有同名重载不算命中，不应插桩。 */
    @Test
    void requiresTheExactDescriptor() {
        FrameHookTransformer transformer =
                new FrameHookTransformer("ave", "av", "(I)V", HOOK_OWNER, "onFrame");
        assertNull(transformer.transform(null, "ave", null, null, sampleClass("ave", "av", "()V")));
    }

    /** 字节码损坏时不得抛出异常，只放弃转换并返回 {@code null}。 */
    @Test
    void toleratesGarbageInput() {
        FrameHookTransformer transformer =
                new FrameHookTransformer("ave", "av", "()V", HOOK_OWNER, "onFrame");
        assertNull(transformer.transform(null, "ave", null, null, new byte[]{1, 2, 3}));
    }
}
