package dev.noturne.agent.transform;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        // 按描述符的返回类型补一条合法的返回指令，使替身类能覆盖非 void 目标（如 SDL 的 (J)Z）。
        char returns = descriptor.charAt(descriptor.lastIndexOf(')') + 1);
        if (returns == 'V') {
            method.visitInsn(Opcodes.RETURN);
        } else if (returns == 'J') {
            method.visitInsn(Opcodes.LCONST_0);
            method.visitInsn(Opcodes.LRETURN);
        } else if (returns == 'F') {
            method.visitInsn(Opcodes.FCONST_0);
            method.visitInsn(Opcodes.FRETURN);
        } else if (returns == 'D') {
            method.visitInsn(Opcodes.DCONST_0);
            method.visitInsn(Opcodes.DRETURN);
        } else if (returns == 'L' || returns == '[') {
            method.visitInsn(Opcodes.ACONST_NULL);
            method.visitInsn(Opcodes.ARETURN);
        } else {
            method.visitInsn(Opcodes.ICONST_0);
            method.visitInsn(Opcodes.IRETURN);
        }
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

    /**
     * 构造一个含分支的最小替身类：方法按 {@code (J)Z} 返回，强制生成 StackMapTable。
     *
     * @param internalName 类内部名
     * @param methodName 唯一的方法名
     * @return 该类的字节码（class 版本 Java 8，帧由 ASM 计算）
     */
    private static byte[] branchyBooleanClass(String internalName, String methodName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, methodName, "(J)Z", null, null);
        method.visitCode();
        // long handle >= 0 ? true : false —— 一个跳转即产生一个栈映射帧。
        method.visitVarInsn(Opcodes.LLOAD, 0);
        method.visitInsn(Opcodes.LCONST_0);
        method.visitInsn(Opcodes.LCMP);
        Label negative = new Label();
        method.visitJumpInsn(Opcodes.IFLT, negative);
        method.visitInsn(Opcodes.ICONST_1);
        method.visitInsn(Opcodes.IRETURN);
        method.visitLabel(negative);
        method.visitInsn(Opcodes.ICONST_0);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** 能在测试内定义任意字节码的类加载器，用于真正触发 JVM 校验。 */
    private static final class DefiningLoader extends ClassLoader {
        DefiningLoader() {
            super(FrameHookTransformerTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] bytecode) {
            return defineClass(name, bytecode, 0, bytecode.length);
        }
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

    /**
     * 类名写成点号全限定名时也必须命中：JVM 交给 transformer 的 {@code className} 恒为斜杠内部名，
     * 构造器必须把点号名归一化，否则钩子永远装不上（ADHOC-ClassnameForm）。
     */
    @Test
    void acceptsDottedClassNameAndNormalisesIt() {
        FrameHookTransformer transformer = new FrameHookTransformer(
                "dev.noturne.test.Dotted", "tick", "()V", HOOK_OWNER, "onFrame");

        byte[] output = transformer.transform(null, "dev/noturne/test/Dotted", null, null,
                sampleClass("dev/noturne/test/Dotted", "tick", "()V"));

        assertNotNull(output, "dotted target name must still match the slash-form className");
        assertEquals(1, hookCallCount(output));
    }

    /** 钩子所有者写成点号名时同样要能生成合法的 INVOKESTATIC 调用。 */
    @Test
    void normalisesDottedHookOwner() {
        FrameHookTransformer transformer = new FrameHookTransformer(
                "ave", "av", "()V", "dev.noturne.client.runtime.NoturneRuntime", "onFrame");

        byte[] output = transformer.transform(null, "ave", null, null, sampleClass("ave", "av", "()V"));

        assertNotNull(output, "dotted hook owner must be normalised to internal form");
        assertEquals(1, hookCallCount(output));
    }

    /**
     * L-21 回归：用<b>真实</b> LWJGL2 {@code Display.update()} 的字节码验证插桩点。
     *
     * <p>构造的极简字节码无法暴露「含分支/栈映射帧的真实方法插桩后校验失败」这类问题。
     * 若本机没有 LWJGL2 jar，用例按「假设不成立」跳过。
     */
    @Test
    void patchesTheRealLwjgl2DisplayUpdate() throws Exception {
        java.io.File jar = locateLwjgl2Jar();
        org.junit.jupiter.api.Assumptions.assumeTrue(jar != null,
                "LWJGL2 jar 不可用，跳过真实字节码校验（可用 -Dnoturne.lwjgl2.jar 指定）");

        byte[] original = readClassFromJar(jar, "org/lwjgl/opengl/Display.class");
        assertNotNull(original, "Display.class must be present in the LWJGL2 jar");

        FrameHookTransformer transformer = new FrameHookTransformer(
                "org/lwjgl/opengl/Display", "update", "()V", HOOK_OWNER, "onFrame");
        byte[] patched = transformer.transform(null, "org/lwjgl/opengl/Display", null, null, original);

        assertNotNull(patched, "the real Display.update() must be patched");
        assertEquals(1, hookCallCount(patched), "hook must be inserted exactly once");
        assertTrue(isHookTheFirstInstruction(patched, "update", "()V"),
                "hook must be the first instruction of the real update()");

        // 打补丁后的字节码必须仍可被 ASM 完整解析（原有 StackMapTable 未被破坏）
        new ClassReader(patched).accept(new ClassVisitor(Opcodes.ASM9) {
        }, 0);
    }

    /** @return 钩子的 INVOKESTATIC 是否为目标方法的第一条指令 */
    private static boolean isHookTheFirstInstruction(byte[] bytecode, String method, String descriptor) {
        final String[] first = {null};
        new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, desc, signature, exceptions);
                if (!method.equals(name) || !descriptor.equals(desc)) {
                    return delegate;
                }
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    private boolean seen;

                    private void record(String what) {
                        if (!seen) {
                            seen = true;
                            first[0] = what;
                        }
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        record("op:" + opcode);
                    }

                    @Override
                    public void visitIntInsn(int opcode, int operand) {
                        record("int:" + opcode);
                    }

                    @Override
                    public void visitVarInsn(int opcode, int var) {
                        record("var:" + opcode);
                    }

                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        record("type:" + opcode);
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                        record("field:" + opcode);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                                                String descriptor, boolean isInterface) {
                        record("call:" + owner + "." + name + descriptor);
                    }

                    @Override
                    public void visitLdcInsn(Object value) {
                        record("ldc");
                    }

                    @Override
                    public void visitJumpInsn(int opcode, org.objectweb.asm.Label label) {
                        record("jump:" + opcode);
                    }

                    @Override
                    public void visitIincInsn(int var, int increment) {
                        record("iinc");
                    }
                };
            }
        }, 0);
        return ("call:" + HOOK_OWNER + ".onFrame()V").equals(first[0]);
    }

    /** 寻找本机 LWJGL2 jar（系统属性可覆盖） */
    private static java.io.File locateLwjgl2Jar() {
        String explicit = System.getProperty("noturne.lwjgl2.jar");
        String[] candidates = {
                explicit,
                "analysis/lwjgl-2.9.4-nightly-20150209.jar",
                "../analysis/lwjgl-2.9.4-nightly-20150209.jar",
                "../../analysis/lwjgl-2.9.4-nightly-20150209.jar",
                new java.io.File(System.getProperty("user.home", ""),
                        ".minecraft/libraries/org/lwjgl/lwjgl/lwjgl/2.9.4-nightly-20150209/"
                                + "lwjgl-2.9.4-nightly-20150209.jar").getPath(),
                new java.io.File(System.getenv("APPDATA") == null ? "" : System.getenv("APPDATA"),
                        ".minecraft/libraries/org/lwjgl/lwjgl/lwjgl/2.9.4-nightly-20150209/"
                                + "lwjgl-2.9.4-nightly-20150209.jar").getPath(),
        };
        for (String candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            java.io.File file = new java.io.File(candidate);
            if (file.isFile()) {
                return file;
            }
        }
        return null;
    }

    /** 读取 jar 中的某个 class 条目字节 */
    private static byte[] readClassFromJar(java.io.File jar, String entryName) throws Exception {
        java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar);
        try {
            java.util.zip.ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                return null;
            }
            java.io.InputStream in = zip.getInputStream(entry);
            try {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                return out.toByteArray();
            } finally {
                in.close();
            }
        } finally {
            zip.close();
        }
    }

    /**
     * 26.3 的渲染栈是 LWJGL 3.4 的 SDL 绑定（没有 GLFW）：交换点
     * {@code SDLVideo.SDL_GL_SwapWindow(J)Z} 返回 boolean 而非 void，也必须能命中且被正确插桩。
     */
    @Test
    void patchesSdlSwapWindowWithBooleanReturn() {
        FrameHookTransformer transformer = new FrameHookTransformer(
                "org/lwjgl/sdl/SDLVideo", "SDL_GL_SwapWindow", "(J)Z", HOOK_OWNER, "onFrame");

        byte[] output = transformer.transform(null, "org/lwjgl/sdl/SDLVideo", null, null,
                sampleClass("org/lwjgl/sdl/SDLVideo", "SDL_GL_SwapWindow", "(J)Z"));

        assertNotNull(output, "SDL swap window must be patched");
        assertEquals(1, hookCallCount(output));
    }

    /**
     * 含分支的方法必须保留 StackMapTable：插入的调用会平移所有帧的偏移，正确做法是让帧随
     * 转换一起通过（不是 SKIP_FRAMES）。这里把转换后的类真正加载进一个类加载器，用 JVM 的校验器
     * 来证明——若帧丢失，Java 8+ 的类会在加载/链接期抛 {@code VerifyError}。这也是 LWJGL3 的
     * {@code glfwSwapBuffers}/{@code SDL_GL_SwapWindow} 能被安全插桩的前提。
     */
    @Test
    void preservesStackMapFramesAndVerifiesOnJvm() throws Exception {
        String internalName = "dev/noturne/test/Branchy";
        FrameHookTransformer transformer = new FrameHookTransformer(
                internalName, "swap", "(J)Z", HOOK_OWNER, "onFrame");

        byte[] output = transformer.transform(
                null, internalName, null, null, branchyBooleanClass(internalName, "swap"));

        assertNotNull(output, "branchy target must be patched");
        assertEquals(1, hookCallCount(output));

        // defineClass 触发 JVM 校验；缺帧会在这里或首次调用时抛 VerifyError。
        Class<?> loaded = new DefiningLoader().define("dev.noturne.test.Branchy", output);
        Method swap = loaded.getDeclaredMethod("swap", long.class);
        assertEquals(Boolean.TRUE, swap.invoke(null, 5L));
        assertEquals(Boolean.FALSE, swap.invoke(null, -1L));
    }
}
