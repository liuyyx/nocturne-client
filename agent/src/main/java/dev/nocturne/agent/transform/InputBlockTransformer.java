package dev.nocturne.agent.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 在目标方法开头插入「占用输入时短路」：
 *
 * <pre>
 *   invokestatic  &lt;hookOwner&gt;.&lt;hookMethod&gt;()Z
 *   ifeq          continue
 *   &lt;短路路径：直接 return / 把形参置 false / 先清零字段再 return&gt;
 *   continue: ...
 * </pre>
 *
 * <p>与 {@link CallbackHookTransformer} 的分工：那个只把形参交出去、**不改变控制流**（所以能一直用
 * {@code COMPUTE_MAXS}）；这个要**吃掉**游戏自己的输入处理。我们的界面不是 vanilla {@code Screen}，
 * 不短路的话同一次点击会同时打在后面的原生界面上（点模块顺带按到「回到游戏」），按键同理。
 *
 * <p><b>栈帧</b>：插入分支必须给出分支目标的栈帧（Java 7+ 强制）。这里不换用
 * {@code COMPUTE_FRAMES}：那会重算整方法的帧、依赖类层次解析，在隔离类加载器里容易出岔子。分支
 * 目标就在方法入口，局部变量恰好是形参（长/双占两个槽但帧里只列一项），因此按描述符**手写一个
 * 满帧**即可，既不碰类层次也不用重算。
 *
 * <p><b>返回值</b>：{@code void} 直接 {@code return}；有返回值时按类型压入「已处理」的默认值——
 * 布尔返回 {@code true}（"这次输入我吃掉了"），其它基本类型返回零值、引用类型返回 {@code null}。
 * 只看返回值不看语义的话，布尔返回 {@code false} 会被调用方当成"没处理"，反而触发别的分支
 * （例如点击穿透到"点外面关界面"）。
 *
 * <p><b>幂等</b>：只检查**目标方法体内**是否已经有同一条钩子调用。曾经用过类级 {@code TransformGuard}，
 * 结果是同一个类里先注册的 {@code grabMouse} 让后面的 {@code turnPlayer}/{@code onScroll} 全部静默
 * 不织入（26.3 真机相机与滚轮漏拦的根因）。
 *
 * <p>类名/方法名/描述符三者不精确命中时一律返回 {@code null}（JVM 沿用原字节码）。
 */
public final class InputBlockTransformer implements ClassFileTransformer {

    /** ASM API 版本；需与依赖的 ASM 一致。 */
    private static final int ASM_API = Opcodes.ASM9;
    /** 钩子方法的描述符：无参、返回布尔。 */
    private static final String HOOK_DESCRIPTOR = "()Z";
    /** 滚轮增量汇（只存在于 bootstrap，跨加载器唯一）。 */
    private static final String SCROLL_OWNER = "dev/nocturne/bootstrap/KeySink";
    /** 累加一次滚轮增量。 */
    private static final String SCROLL_METHOD = "accumulateScroll";

    /** 内部错误是否已打印过（限流）。 */
    private final AtomicBoolean errorLogged = new AtomicBoolean(false);

    /** 目标类内部名（斜杠形式）。 */
    private final String targetClassInternalName;
    /** 目标方法名。 */
    private final String targetMethod;
    /** 目标方法描述符。 */
    private final String targetDescriptor;
    /** 钩子类内部名（斜杠形式）。 */
    private final String hookOwner;
    /** 钩子方法名。 */
    private final String hookMethod;
    /** 短路前是否把首个数值形参（整型/双精度）转发进滚轮累加器。 */
    private final boolean forwardWheel;
    /** 短路时是否把首个布尔形参强制写成 {@code false} 并继续执行（而不是直接返回）。 */
    private final boolean forceFalseParam;
    /** 短路返回前是否清零实例的整型字段（1.8.9/1.12.2 的 {@code MouseHelper} 增量缓存）。 */
    private final boolean zeroIntFields;
    /** 是否真的插桩成功（诊断用）。 */
    private final AtomicBoolean patched = new AtomicBoolean(false);
    /** transform 调用计数（仅 debug）。 */
    private int transformCalls;

    /**
     * 普通短路：占用输入时直接返回。
     *
     * @param targetClass  目标类（点号或斜杠形式，构造器内归一化）
     * @param method       目标方法名
     * @param descriptor   目标方法描述符（返回类型必须是 {@code V}）
     * @param hookOwner    钩子类内部名（斜杠形式）
     * @param hookMethod   钩子方法名（无参、返回 {@code boolean}）
     */
    public InputBlockTransformer(String targetClass, String method, String descriptor,
                                 String hookOwner, String hookMethod) {
        this(targetClass, method, descriptor, hookOwner, hookMethod, false, false, false);
    }

    /**
     * 全参数构造。
     *
     * @param targetClass    目标类（点号或斜杠形式）
     * @param method         目标方法名
     * @param descriptor     目标方法描述符
     * @param hookOwner      钩子类内部名
     * @param hookMethod     钩子方法名
     * @param forwardWheel   短路前把首个数值形参转发进滚轮累加器
     * @param forceFalseParam 短路时把首个布尔形参置 false 后继续执行，而不是直接返回
     * @param zeroIntFields  短路返回前清零实例的全部整型字段（{@code MouseHelper} 的增量缓存）
     */
    public InputBlockTransformer(String targetClass, String method, String descriptor,
                                 String hookOwner, String hookMethod, boolean forwardWheel,
                                 boolean forceFalseParam, boolean zeroIntFields) {
        this.targetClassInternalName = targetClass.replace('.', '/');
        this.targetMethod = method;
        this.targetDescriptor = descriptor;
        this.hookOwner = hookOwner;
        this.hookMethod = hookMethod;
        this.forwardWheel = forwardWheel;
        this.forceFalseParam = forceFalseParam;
        this.zeroIntFields = zeroIntFields;
    }

    /** @return 目标方法是否已被打上补丁（供注册方判断"织入了但没有目标"这类情况）。 */
    public boolean patched() {
        return patched.get();
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (System.getProperty("nocturne.ui.debug") != null && transformCalls < 3) {
            transformCalls++;
            System.out.println("[nocturne] input block transform #" + transformCalls + " class="
                    + className + " target=" + targetClassInternalName + " method=" + targetMethod
                    + " desc=" + targetDescriptor);
        }
        if (className == null || !className.equals(targetClassInternalName)) {
            return null;
        }
        try {
            if (containsHookInMethod(classfileBuffer)) {
                // 已经织过：标记 patched（调用方据此认为钩子有效），但不再返回新字节。
                patched.set(true);
                return null;
            }
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);

            ClassVisitor visitor = new ClassVisitor(ASM_API, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    MethodVisitor delegate =
                            super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (!targetMethod.equals(name) || !targetDescriptor.equals(descriptor)) {
                        return delegate;
                    }
                    return new MethodVisitor(ASM_API, delegate) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            inject(access);
                            patched.set(true);
                        }

                        /** 插入短路逻辑并补上分支目标的满帧。 */
                        private void inject(int access) {
                            // 键盘入口 (J I I I I)：把 key/action 记进 InputBlock，供叠加层事件驱动地
                            // 读取。1.21.1 实测 glfwGetKey 在合成按键下恒为 0，只有游戏自己的回调可靠。
                            if ("(JIIII)V".equals(targetDescriptor)) {
                                super.visitVarInsn(Opcodes.ILOAD, 3);
                                super.visitVarInsn(Opcodes.ILOAD, 5);
                                // 必须指向只存在于 bootstrap 的 KeySink：hookOwner（InputBlock）
                                // 在 agent jar 里还有一份，Knot 会加载它，静态字段互不可见。
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "dev/nocturne/bootstrap/KeySink", "recordKey", "(II)V", false);
                            }
                            if (forwardWheel) {
                                forwardWheelArgument(access);
                            }
                            Label continueLabel = new Label();
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, hookOwner, hookMethod,
                                    HOOK_DESCRIPTOR, false);
                            super.visitJumpInsn(Opcodes.IFEQ, continueLabel);
                            if (forceFalseParam) {
                                forceFirstBooleanFalse(access);
                            } else {
                                zeroFields(access);
                                pushHandledReturn();
                            }
                            super.visitLabel(continueLabel);
                            Object[] locals = localsOf(access);
                            super.visitFrame(Opcodes.F_NEW, locals.length, locals, 0, null);
                        }

                        /**
                         * 把滚轮方向/增量转发进 bootstrap 的累加器：旧世代（1.8.9/1.12.2）没有
                         * 可轮询的滚轮接口，界面只能靠这个入口拿到增量。
                         *
                         * @param access 目标方法访问标志
                         */
                        private void forwardWheelArgument(int access) {
                            Type[] arguments = Type.getArgumentTypes(targetDescriptor);
                            if (arguments.length == 0) {
                                return;
                            }
                            int slot = (access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
                            Type first = arguments[0];
                            if (first.getSort() == Type.INT
                                    || first.getSort() == Type.BOOLEAN
                                    || first.getSort() == Type.BYTE
                                    || first.getSort() == Type.SHORT
                                    || first.getSort() == Type.CHAR) {
                                super.visitVarInsn(Opcodes.ILOAD, slot);
                                super.visitInsn(Opcodes.I2D);
                            } else if (first.getSort() == Type.DOUBLE) {
                                super.visitVarInsn(Opcodes.DLOAD, slot);
                            } else {
                                return;
                            }
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, SCROLL_OWNER, SCROLL_METHOD,
                                    "(D)V", false);
                        }

                        /**
                         * 键盘绑定的短路方式：把 {@code setKeyBindState(key, pressed)} 的
                         * {@code pressed} 强制写成 false，而不是整条返回。
                         *
                         * <p>不能直接返回：按键的"松开"事件也会走这里，返回意味着最后一次 true
                         * 永远留在绑定里（玩家开界面时按着 W，关掉界面后一直往前走）。置 false
                         * 让游戏自己的状态机把按键放开。
                         *
                         * @param access 目标方法访问标志
                         */
                        private void forceFirstBooleanFalse(int access) {
                            Type[] arguments = Type.getArgumentTypes(targetDescriptor);
                            int slot = (access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
                            int booleanSlot = -1;
                            for (Type argument : arguments) {
                                if (argument.getSort() == Type.BOOLEAN) {
                                    booleanSlot = slot;
                                    break;
                                }
                                slot += argument.getSize();
                            }
                            if (booleanSlot < 0) {
                                // 没有布尔形参：退化为普通短路。
                                zeroFields(access);
                                pushHandledReturn();
                                return;
                            }
                            super.visitInsn(Opcodes.ICONST_0);
                            super.visitVarInsn(Opcodes.ISTORE, booleanSlot);
                        }

                        /**
                         * 短路返回前清零实例字段：旧世代 {@code MouseHelper#deltaX/deltaY} 在
                         * 被短路后不会刷新，恢复时会把上一帧的增量再吃一次。
                         */
                        private void zeroFields(int access) {
                            if (!zeroIntFields || (access & Opcodes.ACC_STATIC) != 0) {
                                return;
                            }
                            // 走 bootstrap 唯一的 LegacyMouse：字段名按运行期类反射收集，
                            // 不把 1.8.9/1.12.2 的混淆字段名写死在转换器里。
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "dev/nocturne/bootstrap/LegacyMouse", "clearDeltas",
                                    "(Ljava/lang/Object;)V", false);
                        }

                        /** 压入「已处理」返回值并返回：布尔用 {@code true}，其它按类型给零值。 */
                        private void pushHandledReturn() {
                            switch (Type.getReturnType(targetDescriptor).getSort()) {
                                case Type.VOID:
                                    super.visitInsn(Opcodes.RETURN);
                                    return;
                                case Type.BOOLEAN:
                                    super.visitInsn(Opcodes.ICONST_1);
                                    super.visitInsn(Opcodes.IRETURN);
                                    return;
                                case Type.BYTE:
                                case Type.CHAR:
                                case Type.SHORT:
                                case Type.INT:
                                    super.visitInsn(Opcodes.ICONST_0);
                                    super.visitInsn(Opcodes.IRETURN);
                                    return;
                                case Type.LONG:
                                    super.visitInsn(Opcodes.LCONST_0);
                                    super.visitInsn(Opcodes.LRETURN);
                                    return;
                                case Type.FLOAT:
                                    super.visitInsn(Opcodes.FCONST_0);
                                    super.visitInsn(Opcodes.FRETURN);
                                    return;
                                case Type.DOUBLE:
                                    super.visitInsn(Opcodes.DCONST_0);
                                    super.visitInsn(Opcodes.DRETURN);
                                    return;
                                default:
                                    super.visitInsn(Opcodes.ACONST_NULL);
                                    super.visitInsn(Opcodes.ARETURN);
                            }
                        }

                        /**
                         * 方法入口处的局部变量表：实例方法首项是 {@code this}（目标类本身），
                         * 其余为形参。
                         *
                         * @param access 目标方法的访问标志
                         * @return 满帧用的局部变量项（长/双只占一项）
                         */
                        private Object[] localsOf(int access) {
                            Type[] arguments = Type.getArgumentTypes(targetDescriptor);
                            boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
                            Object[] locals = new Object[arguments.length + (isStatic ? 0 : 1)];
                            int at = 0;
                            if (!isStatic) {
                                locals[at++] = targetClassInternalName;
                            }
                            for (Type argument : arguments) {
                                locals[at++] = frameEntry(argument);
                            }
                            return locals;
                        }

                        /**
                         * 形参在帧里的表示：引用类型用内部名/数组描述符，基本类型用 ASM 的常量。
                         *
                         * @param type 形参类型
                         * @return 帧项
                         */
                        private Object frameEntry(Type type) {
                            switch (type.getSort()) {
                                case Type.BOOLEAN:
                                case Type.CHAR:
                                case Type.BYTE:
                                case Type.SHORT:
                                case Type.INT:
                                    return Opcodes.INTEGER;
                                case Type.FLOAT:
                                    return Opcodes.FLOAT;
                                case Type.LONG:
                                    return Opcodes.LONG;
                                case Type.DOUBLE:
                                    return Opcodes.DOUBLE;
                                case Type.ARRAY:
                                    return type.getDescriptor();
                                default:
                                    return type.getInternalName();
                            }
                        }
                    };
                }
            };
            reader.accept(visitor, ClassReader.EXPAND_FRAMES);
            // 一个字都没改就原样放行：返回重写过的字节码会让 JVM 做一次无意义的重定义。
            return patched.get() ? writer.toByteArray() : null;
        } catch (Throwable t) {
            if (errorLogged.compareAndSet(false, true)) {
                System.out.println("[nocturne] input block transformer failed on "
                        + targetClassInternalName + "." + targetMethod + ": " + t);
            }
            return null;
        }
    }

    /**
     * 精确到方法的幂等检查：只有目标方法体里已经有同一条钩子调用时才跳过。
     *
     * @param bytes 目标类原始字节
     * @return 目标方法已含该钩子调用时返回 {@code true}
     */
    private boolean containsHookInMethod(byte[] bytes) {
        final boolean[] found = {false};
        try {
            new ClassReader(bytes).accept(new ClassVisitor(ASM_API) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    if (!targetMethod.equals(name) || !targetDescriptor.equals(descriptor)) {
                        return null;
                    }
                    return new MethodVisitor(ASM_API) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name,
                                                    String descriptor, boolean isInterface) {
                            if (opcode == Opcodes.INVOKESTATIC && hookOwner.equals(owner)
                                    && hookMethod.equals(name) && HOOK_DESCRIPTOR.equals(descriptor)) {
                                found[0] = true;
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (Throwable ignored) {
            return false;
        }
        return found[0];
    }
}
