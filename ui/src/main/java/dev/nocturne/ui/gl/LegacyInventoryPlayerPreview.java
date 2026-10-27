package dev.nocturne.ui.gl;

import dev.nocturne.client.game.Reflect;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.mapping.Mapping;
import dev.nocturne.client.mapping.MethodCandidate;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** 旧版库存人物绘制方法：(x, y, scale, lookX, lookY, LivingEntity)。 */
final class LegacyInventoryPlayerPreview {
    private final Method method;
    private boolean failureLogged;
    /** 复用原值槽：原生方法在异常路径没有 finally，必须由我们收回玩家朝向。 */
    private final Object[] rotations = new Object[5];

    private LegacyInventoryPlayerPreview(Method method) {
        this.method = method;
    }

    static LegacyInventoryPlayerPreview bind(Mapping mapping, ClassLoader loader) {
        if (mapping == null || loader == null) {
            return null;
        }
        for (String className : mapping.classNameCandidates(ClassType.INVENTORY_SCREEN)) {
            Class<?> screen = Reflect.loadWithoutInit(className, loader);
            if (screen == null) {
                continue;
            }
            LegacyInventoryPlayerPreview preview = forScreen(mapping, screen);
            if (preview != null) {
                return preview;
            }
        }
        return null;
    }

    static LegacyInventoryPlayerPreview forScreen(Mapping mapping, Class<?> screen) {
        Method method = find(screen, mapping, "drawEntityOnScreen");
        if (method == null) {
            method = find(screen, mapping, "renderEntityInInventory");
        }
        return method == null ? null : new LegacyInventoryPlayerPreview(method);
    }

    private static Method find(Class<?> screen, Mapping mapping, String canonical) {
        for (MethodCandidate name : mapping.methodCandidates(ClassType.INVENTORY_SCREEN, canonical)) {
            for (Method method : screen.getDeclaredMethods()) {
                Class<?>[] args = method.getParameterTypes();
                if (!name.name().equals(method.getName()) || !Modifier.isStatic(method.getModifiers())
                        || method.getReturnType() != void.class || (args.length != 6 && args.length != 7)) {
                    continue;
                }
                int offset = args.length - 6;
                if ((offset == 1 && args[0].isPrimitive())
                        || args[offset] != int.class || args[offset + 1] != int.class
                        || args[offset + 2] != int.class || args[offset + 3] != float.class
                        || args[offset + 4] != float.class || args[offset + 5].isPrimitive()) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                } catch (Throwable ignored) {
                    // public 静态库存渲染方法无需绕过 Java 访问权限。
                }
                return method;
            }
        }
        return null;
    }

    boolean draw(dev.nocturne.client.game.GameBridge bridge, Object player, int x, int y, int scale,
                 float lookX, float lookY) {
        return draw(bridge, null, player, x, y, scale, lookX, lookY);
    }

    /** @param poseStack 1.15–1.19.3 游戏当帧传入的矩阵栈；无首参的旧版入口传 {@code null}。 */
    boolean draw(dev.nocturne.client.game.GameBridge bridge, Object poseStack, Object player,
                 int x, int y, int scale, float lookX, float lookY) {
        Class<?>[] parameters = method.getParameterTypes();
        int entityIndex = parameters.length - 1;
        if (bridge == null || player == null || method == null || !parameters[entityIndex].isInstance(player)
                || (parameters.length == 7 && (poseStack == null || !parameters[0].isInstance(poseStack)))) {
            return false;
        }
        rotations[0] = bridge.readField(player, ClassType.ENTITY, "xRot");
        rotations[1] = bridge.readField(player, ClassType.ENTITY, "yRot");
        rotations[2] = bridge.readField(player, ClassType.LIVING_ENTITY, "yBodyRot");
        rotations[3] = bridge.readField(player, ClassType.LIVING_ENTITY, "yHeadRot");
        rotations[4] = bridge.readField(player, ClassType.LIVING_ENTITY, "yHeadRotO");
        for (Object rotation : rotations) {
            if (!(rotation instanceof Number)) {
                return false;
            }
        }
        try {
            if (parameters.length == 7) {
                method.invoke(null, poseStack, x, y, scale, lookX, lookY, player);
            } else {
                method.invoke(null, x, y, scale, lookX, lookY, player);
            }
            return true;
        } catch (Throwable failure) {
            if (!failureLogged) {
                failureLogged = true;
                System.out.println("[nocturne] legacy native player preview failed: " + failure);
            }
            return false;
        } finally {
            bridge.writeField(player, ClassType.ENTITY, "xRot", rotations[0]);
            bridge.writeField(player, ClassType.ENTITY, "yRot", rotations[1]);
            bridge.writeField(player, ClassType.LIVING_ENTITY, "yBodyRot", rotations[2]);
            bridge.writeField(player, ClassType.LIVING_ENTITY, "yHeadRot", rotations[3]);
            bridge.writeField(player, ClassType.LIVING_ENTITY, "yHeadRotO", rotations[4]);
        }
    }
}
