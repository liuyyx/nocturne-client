package dev.nocturne.agent.mixin;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** HUD 后置钩子的字节码边界回归。 */
class NocturneMixinPluginTest {

    @Test
    void hudHookRunsAfterDepthClearWithoutCallerLocalSlot() {
        NocturneMixinPlugin.configure(null, null, null, null, null);
        ClassNode node = new ClassNode();
        node.name = "net/minecraft/class_757";
        MethodNode render = new MethodNode(Opcodes.ACC_PUBLIC, "method_3192",
                "(Lnet/minecraft/class_9779;Z)V", null, null);
        render.instructions.add(new TypeInsnNode(Opcodes.NEW, "net/minecraft/class_332"));
        render.instructions.add(new VarInsnNode(Opcodes.ASTORE, 11));
        render.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "com/mojang/blaze3d/systems/RenderSystem", "clear", "(IZ)V", false));
        render.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(render);

        new NocturneMixinPlugin().postApply(node.name, node,
                "dev.nocturne.agent.mixin.HudRenderMixin", null);

        AbstractInsnNode clear = null;
        for (AbstractInsnNode instruction : render.instructions) {
            if (instruction instanceof MethodInsnNode
                    && "clear".equals(((MethodInsnNode) instruction).name)) {
                clear = instruction;
                break;
            }
        }
        assertNotNull(clear, "precondition: depth clear exists");
        assertTrue(clear.getNext() instanceof MethodInsnNode,
                "deferred hook must start immediately after depth clear");
        MethodInsnNode hook = (MethodInsnNode) clear.getNext();
        assertEquals("dev/nocturne/agent/GuiDrawHook", hook.owner);
        assertEquals("onHudDrawDeferred", hook.name);
        assertEquals("()V", hook.desc, "caller local slots may be rewritten by other mixins");
        assertEquals(Opcodes.RETURN, hook.getNext().getOpcode());
    }

    @Test
    void hudCaptureUsesStableGuiRenderParameter() {
        NocturneMixinPlugin.configure(null, null, null, null, null);
        ClassNode node = new ClassNode();
        node.name = "net/minecraft/class_329";
        MethodNode render = new MethodNode(Opcodes.ACC_PUBLIC, "method_1753",
                "(Lnet/minecraft/class_332;Lnet/minecraft/class_9779;)V", null, null);
        render.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(render);

        new NocturneMixinPlugin().postApply(node.name, node,
                "dev.nocturne.agent.mixin.HudRenderMixin", null);

        VarInsnNode graphics = (VarInsnNode) render.instructions.getFirst();
        assertEquals(Opcodes.ALOAD, graphics.getOpcode());
        assertEquals(1, graphics.var);
        MethodInsnNode hook = (MethodInsnNode) graphics.getNext();
        assertEquals("onHudDrawCapture", hook.name);
        assertEquals("(Ljava/lang/Object;)V", hook.desc);
        assertEquals(Opcodes.RETURN, hook.getNext().getOpcode());
    }
}
