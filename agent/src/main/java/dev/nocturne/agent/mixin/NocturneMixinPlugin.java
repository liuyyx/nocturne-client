package dev.nocturne.agent.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Mixin 配置插件：在目标类被 Mixin 处理完之后，直接把钩子调用插进指定方法。
 *
 * <p><b>为什么用 {@code postApply} 而不是 {@code @Inject}</b>：{@code @Inject} 的注入方法必须
 * 精确声明目标方法的全部形参类型，而 {GuiGraphics} 一类在不同 Minecraft 版本里的名字完全不同
 * （1.21.1 是 {@code class_332}、26.x 是 {@code GuiGraphicsExtractor}）。{@code postApply}
 * 拿到的是 ASM {@code ClassNode}，只要「类名 + 方法名 + 描述符」三者对上就能插，与版本无关。
 *
 * <p><b>为什么这是 Fabric 下唯一可靠的注入通道</b>：{@code KnotClassLoader} 用 {@code defineClass}
 * 直接定义游戏类，JVM 的 {@code retransformClasses} 不会回调后注册的 transformer，
 * {@code redefineClasses} 又会因为 Mixin 已改写类头（追加接口）而结构校验失败。Mixin 本身就是
 * {@code KnotClassLoader} 的合作伙伴，是唯一能在类定义前拿到字节码的路径。
 *
 * <p>配置由 agent 侧通过 {@link #configure} 注入：插件类由游戏的 {@code KnotClassLoader} 加载，
 * 与 agent 侧的 {@code NocturneAgent} 不是同一个类加载器，静态字段也不会共享，因此必须显式传递。
 */
public final class NocturneMixinPlugin implements IMixinConfigPlugin {

    /** 目标类内部名（斜杠形式）。 */
    private static volatile String targetClass;

    /** 目标方法名。 */
    private static volatile String targetMethod;

    /** 目标方法描述符。 */
    private static volatile String targetDescriptor;

    /** 钩子类内部名（斜杠形式）。 */
    private static volatile String hookOwner;

    /** 钩子方法名（形如 {@code (Ljava/lang/Object;)V}，首参为被注入方法的首个引用形参）。 */
    private static volatile String hookMethod;

    /** 是否已织入（用于日志去重）。 */
    private static volatile boolean woven;

    /**
     * 配置插件的目标与钩子。
     *
     * <p>必须在 {@code MixinEnvironment.addConfiguration} 之前调用，否则插件生效时目标为空。
     *
     * @param targetClass      目标类内部名
     * @param targetMethod     目标方法名
     * @param targetDescriptor 目标方法描述符
     * @param hookOwner        钩子类内部名
     * @param hookMethod       钩子方法名
     */
    public static void configure(String targetClass, String targetMethod, String targetDescriptor,
                                 String hookOwner, String hookMethod) {
        NocturneMixinPlugin.targetClass = targetClass;
        NocturneMixinPlugin.targetMethod = targetMethod;
        NocturneMixinPlugin.targetDescriptor = targetDescriptor;
        NocturneMixinPlugin.hookOwner = hookOwner;
        NocturneMixinPlugin.hookMethod = hookMethod;
        NocturneMixinPlugin.woven = false;
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // 无条件的理由是：{@code @Mixin(targets = ...)} 已经限定了目标类，本方法只需回答
        // 「这个 mixin 要不要参与」。而 {@code targetClass} 静态字段只在 agent 形态下由
        // {@code installMixinChannel} 注入——mod 形态下没有人调用它（配置由 fabric.mod.json
        // 交给 Mixin），若在这里依赖它就永远是 null → mixin 静默不生效（实测）。
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClassNode, String mixinClassName,
                         IMixinInfo mixinInfo) {
    }

    /**
     * Mixin 应用完成后直接改写目标方法。
     *
     * <p>插入位置在方法开头：先让叠加层有机会绘制，再让游戏画自己的内容——这样界面不会被
     * 目标方法后续的绘制盖住。钩子只接收首个引用形参（GuiGraphics / PoseStack 一类），
     * 其余形参不需要。
     *
     * @param targetClassName 目标类名（点号形式）
     * @param node            目标类的 ASM 节点
     * @param mixinClassName  mixin 类名
     * @param mixinInfo       mixin 元信息
     */
    @Override
    public void postApply(String targetClassName, ClassNode node, String mixinClassName,
                          IMixinInfo mixinInfo) {
        String cls = targetClass;
        String method = targetMethod;
        String descriptor = targetDescriptor;
        String owner = hookOwner == null ? "dev/nocturne/agent/GuiDrawHook" : hookOwner;
        String hook = hookMethod == null ? "onScreenDraw" : hookMethod;
        if (cls == null || method == null || descriptor == null) {
            // mod 形态：没有 agent 侧配置，按「形如 (GuiGraphics-like, ...)V 的绘制方法」自动挑选。
            autoApply(node, owner, hook);
            return;
        }
        if (node == null || !cls.equals(node.name)) {
            return;
        }
        for (MethodNode candidate : node.methods) {
            if (!method.equals(candidate.name) || !descriptor.equals(candidate.desc)) {
                continue;
            }
            InsnList insn = new InsnList();
            insn.add(new VarInsnNode(Opcodes.ALOAD, firstReferenceSlot(candidate.access, descriptor)));
            insn.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, hook, "(Ljava/lang/Object;)V", false));
            candidate.instructions.insert(insn);
            // maxStack 可能因为多压了一个引用而不够；+2 覆盖返回值与临时槽位。
            candidate.maxStack = Math.max(candidate.maxStack, 2);
            if (!woven) {
                woven = true;
                System.out.println("[nocturne] mixin woven: " + cls + "." + method + descriptor
                        + " -> " + owner + "." + hook);
            }
            return;
        }
    }

    /**
     * mod 形态下的自动挑选：在目标类里找「返回 void、首个形参是引用类型、参数个数≥2」的方法。
     *
     * <p>Screen 上符合这个形状的绘制入口只有 {@code render(GuiGraphics, int, int, float)}；
     * {@code renderBackground} 等没有这个形状。找不到时什么都不做（mixin 变成纯空壳）。
     *
     * @param node  目标类节点
     * @param owner 钩子类内部名
     * @param hook  钩子方法名
     */
    /** 已知的绘制入口方法名，按优先级排列（intermediary / 官方名）。 */
    private static final String[] DRAW_METHODS = {
        "method_25394", "render", "method_47413"
    };

    private static void autoApply(ClassNode node, String owner, String hook) {
        if (node == null) {
            return;
        }
        // 先按已知方法名精确挑：同名的绘制方法在 Screen 上有多个（renderBackground 等），
        // 「返回 void + 首参引用」这种形状不足以唯一确定主绘制入口（实测挑到了
        // method_47413，它不是每帧都被调用的那个）。
        for (String wanted : DRAW_METHODS) {
            for (MethodNode candidate : node.methods) {
                if (!wanted.equals(candidate.name)) {
                    continue;
                }
                if (inject(node, candidate, owner, hook)) {
                    return;
                }
            }
        }
        for (MethodNode candidate : node.methods) {
            if (!"V".equals(returnType(candidate.desc))) {
                continue;
            }
            org.objectweb.asm.Type[] arguments;
            try {
                arguments = org.objectweb.asm.Type.getArgumentTypes(candidate.desc);
            } catch (Throwable t) {
                continue;
            }
            if (arguments.length < 2) {
                continue;
            }
            if (arguments[0].getSort() != org.objectweb.asm.Type.OBJECT
                    && arguments[0].getSort() != org.objectweb.asm.Type.ARRAY) {
                continue;
            }
            if (inject(node, candidate, owner, hook)) {
                return;
            }
        }
        if (!woven) {
            woven = true;
            System.out.println("[nocturne] mixin: no draw entry found in " + node.name);
        }
    }

    /**
     * 往指定方法开头插入钩子调用。
     *
     * @param node      目标类节点（仅用于日志）
     * @param candidate 目标方法
     * @param owner     钩子类内部名
     * @param hook      钩子方法名
     * @return 插入成功返回 {@code true}
     */
    private static boolean inject(ClassNode node, MethodNode candidate, String owner, String hook) {
        int slot = firstReferenceSlot(candidate.access, candidate.desc);
        if (slot < 0) {
            return false;
        }
        InsnList insn = new InsnList();
        insn.add(new VarInsnNode(Opcodes.ALOAD, slot));
        insn.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, hook,
                "(Ljava/lang/Object;)V", false));
        candidate.instructions.insert(insn);
        candidate.maxStack = Math.max(candidate.maxStack, 2);
        if (!woven) {
            woven = true;
            System.out.println("[nocturne] mixin woven (auto): " + node.name + "."
                    + candidate.name + candidate.desc + " -> " + owner + "." + hook);
        }
        return true;
    }

    /**
     * 从方法描述符取返回类型描述符。
     *
     * @param descriptor 方法描述符
     * @return 返回类型描述符（如 {@code V}）
     */
    private static String returnType(String descriptor) {
        try {
            return org.objectweb.asm.Type.getReturnType(descriptor).getDescriptor();
        } catch (Throwable t) {
            return "V";
        }
    }

    /**
     * 计算方法首个引用形参在局部变量表里的槽位。
     *
     * @param access     方法访问标志（决定是否含 {@code this}）
     * @param descriptor 方法描述符
     * @return 槽位；没有引用形参时返回 -1
     */
    private static int firstReferenceSlot(int access, String descriptor) {
        org.objectweb.asm.Type[] arguments;
        try {
            arguments = org.objectweb.asm.Type.getArgumentTypes(descriptor);
        } catch (Throwable t) {
            return -1;
        }
        int slot = (access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        for (org.objectweb.asm.Type argument : arguments) {
            if (argument.getSort() == org.objectweb.asm.Type.OBJECT
                    || argument.getSort() == org.objectweb.asm.Type.ARRAY) {
                return slot;
            }
            slot += argument.getSize();
        }
        return -1;
    }
}
