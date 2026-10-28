package dev.nocturne.agent.mixin;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
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
        // agent 形态只为 Screen 显式配置一次；HUD 由已有的转换器注册。
        // mod 形态没有配置，由两个独立 Mixin 分别驱动菜单与游戏内画面。
        return targetClass == null || !mixinClassName.endsWith(".HudRenderMixin");
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
        if (mixinClassName.endsWith(".MouseInputMixin")) {
            blockInput(node, MOUSE_INPUT_METHODS);
            return;
        }
        if (mixinClassName.endsWith(".KeyboardInputMixin")) {
            blockInput(node, KEYBOARD_INPUT_METHODS);
            return;
        }
        String cls = targetClass;
        String method = targetMethod;
        String descriptor = targetDescriptor;
        String owner = hookOwner == null ? "dev/nocturne/agent/GuiDrawHook" : hookOwner;
        String hook = hookMethod == null ? "onScreenDraw" : hookMethod;
        if (cls == null || method == null || descriptor == null) {
            if (mixinClassName.endsWith(".HudRenderMixin")) {
                autoApplyHud(node, owner);
            } else {
                autoApply(node, owner, hook);
            }
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
        "method_25394", "method_37058", "render", "method_47413"
    };

    /** MouseHandler：onPress、grabMouse、turnPlayer、onScroll。onMove 必须保留。 */
    private static final String[] MOUSE_INPUT_METHODS = {
        "method_1601", "method_1612", "method_1606", "method_1598",
        "onPress", "grabMouse", "turnPlayer", "onScroll"
    };

    /** KeyboardHandler：界面打开时不再把按键/字符派发给游戏。 */
    private static final String[] KEYBOARD_INPUT_METHODS = {
        "method_1466", "method_1472", "keyPress", "charTyped"
    };

    /**
     * 在输入入口开头插入「界面占用输入时直接返回」。
     *
     * <p>与 agent 形态的 {@code InputBlockTransformer} 等价；Fabric mod 没有 Instrumentation，
     * 这是阻止游戏在界面打开时重新捕获鼠标、并把同一次点击打进原生界面的唯一路径。
     */
    private static void blockInput(ClassNode node, String[] methods) {
        if (node == null) {
            return;
        }
        int patched = 0;
        for (MethodNode candidate : node.methods) {
            if ((candidate.access & Opcodes.ACC_STATIC) != 0
                    || !"V".equals(returnType(candidate.desc))) {
                continue;
            }
            boolean targetMethod = false;
            for (String name : methods) {
                if (name.equals(candidate.name)) {
                    targetMethod = true;
                    break;
                }
            }
            if (!targetMethod) {
                continue;
            }
            InsnList entry = new InsnList();
            if ("(JDD)V".equals(candidate.desc)
                    && ("method_1598".equals(candidate.name) || "onScroll".equals(candidate.name))) {
                // 先取原始 yoffset，再按占用短路：自绘 GUI 收到同一次滚动，物品栏不再收到。
                entry.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.DLOAD, 5));
                entry.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "dev/nocturne/client/runtime/ScrollState", "accumulate", "(D)V", false));
            }
            if ("(JIIII)V".equals(candidate.desc)
                    && ("method_1466".equals(candidate.name) || "keyPress".equals(candidate.name))) {
                // mod 形态没有 bootstrap bridge：Knot 中的游戏类与 GuiOverlay 共用这份 InputBlock。
                // 记录必须排在短路前面，否则界面打开后 Esc / 开关键无法再通过事件关闭界面。
                entry.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ILOAD, 3));
                entry.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ILOAD, 5));
                entry.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "dev/nocturne/client/runtime/InputBlock", "recordKey", "(II)V", false));
            }
            org.objectweb.asm.tree.LabelNode allow = new org.objectweb.asm.tree.LabelNode();
            entry.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "dev/nocturne/client/runtime/InputBlock", "shouldBlock", "()Z", false));
            entry.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.IFEQ, allow));
            entry.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
            entry.add(allow);
            candidate.instructions.insert(entry);
            candidate.maxStack = Math.max(candidate.maxStack, 2);
            patched++;
        }
        System.out.println("[nocturne] mixin input guard: " + node.name + " methods=" + patched);
    }

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
     * 在 GameRenderer 完成原生 HUD 绘制及深度清理后织入。
     *
     * <p>不能直接挂在 Gui.render 返回点：ImmediatelyFast 会把整个 Gui.render 包进 HUD 合批，
     * 游戏又会在它返回后清理世界深度。Fabric 在 Gui.render 捕获参数，在这里延迟绘制；
     * 不读取 GameRenderer 的局部槽号（其他 Mixin 会改变槽位布局）。
     */
    private static void autoApplyHud(ClassNode node, String owner) {
        if (node == null) {
            return;
        }
        if ("net/minecraft/class_329".equals(node.name)) {
            autoApplyHudCapture(node, owner);
            return;
        }
        if (!"net/minecraft/class_757".equals(node.name)) {
            return;
        }
        for (MethodNode candidate : node.methods) {
            if (!"method_3192".equals(candidate.name) && !"render".equals(candidate.name)) {
                continue;
            }
            org.objectweb.asm.Type[] args = org.objectweb.asm.Type.getArgumentTypes(candidate.desc);
            if (args.length != 2 || args[0].getSort() != org.objectweb.asm.Type.OBJECT
                    || args[1].getSort() != org.objectweb.asm.Type.BOOLEAN
                    || !"V".equals(returnType(candidate.desc))) {
                continue;
            }
            boolean graphicsAllocated = false;
            for (AbstractInsnNode instruction = candidate.instructions.getFirst(); instruction != null;
                    instruction = instruction.getNext()) {
                if (instruction.getOpcode() == Opcodes.NEW
                        && isGuiGraphics(((TypeInsnNode) instruction).desc)) {
                    graphicsAllocated = true;
                    continue;
                }
                if (!graphicsAllocated || instruction.getOpcode() != Opcodes.INVOKESTATIC) {
                    continue;
                }
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (!"com/mojang/blaze3d/systems/RenderSystem".equals(call.owner)
                        || !"clear".equals(call.name) || !"(IZ)V".equals(call.desc)) {
                    continue;
                }
                candidate.instructions.insert(instruction, new MethodInsnNode(Opcodes.INVOKESTATIC,
                        owner, "onHudDrawDeferred", "()V", false));
                candidate.maxStack = Math.max(candidate.maxStack, 1);
                System.out.println("[nocturne] mixin woven (post-hud): " + node.name + "."
                        + candidate.name + candidate.desc + " -> " + owner + ".onHudDrawDeferred");
                return;
            }
        }
        System.out.println("[nocturne] mixin: no post-HUD depth clear in " + node.name);
    }

    /** 在 Gui.render 返回前捕获首个引用参数；方法参数槽不会被调用方 Mixin 重排。 */
    private static void autoApplyHudCapture(ClassNode node, String owner) {
        for (MethodNode candidate : node.methods) {
            if (!"method_1753".equals(candidate.name) && !"render".equals(candidate.name)) {
                continue;
            }
            org.objectweb.asm.Type[] args = org.objectweb.asm.Type.getArgumentTypes(candidate.desc);
            if (args.length != 2 || !"V".equals(returnType(candidate.desc))
                    || args[0].getSort() != org.objectweb.asm.Type.OBJECT
                    || !isGuiGraphics(args[0].getInternalName())) {
                continue;
            }
            int slot = firstReferenceSlot(candidate.access, candidate.desc);
            if (slot < 0) {
                return;
            }
            for (AbstractInsnNode instruction = candidate.instructions.getFirst(); instruction != null;
                    instruction = instruction.getNext()) {
                if (instruction.getOpcode() != Opcodes.RETURN) {
                    continue;
                }
                InsnList hook = new InsnList();
                hook.add(new VarInsnNode(Opcodes.ALOAD, slot));
                hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, "onHudDrawCapture",
                        "(Ljava/lang/Object;)V", false));
                candidate.instructions.insertBefore(instruction, hook);
            }
            candidate.maxStack = Math.max(candidate.maxStack, 2);
            System.out.println("[nocturne] mixin woven (hud-capture): " + node.name + "."
                    + candidate.name + candidate.desc + " -> " + owner + ".onHudDrawCapture");
            return;
        }
        System.out.println("[nocturne] mixin: no Gui.render(GuiGraphics, *) in " + node.name);
    }

    private static boolean isGuiGraphics(String internalName) {
        return "net/minecraft/class_332".equals(internalName)
                || "net/minecraft/client/gui/GuiGraphics".equals(internalName);
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
