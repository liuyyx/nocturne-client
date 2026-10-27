package dev.nocturne.ui.gl;

import dev.nocturne.client.game.Reflect;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.mapping.Mapping;
import dev.nocturne.client.mapping.MethodCandidate;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * 将模型提交到游戏自身的库存预览路径；不创建 framebuffer，不借用 LWJGL GL 绑定。
 * 1.20–1.21 的 GuiGraphics 和 26.x 的 GuiGraphicsExtractor 都在游戏绘制钩子内有效。
 */
final class InventoryPlayerPreview {
    /** 1.20.2–26.3：绘制区四角、缩放、y 偏移、鼠标两轴。 */
    private static final int REGION_ARGS = 10;
    /** 1.20–1.20.1：锚点、缩放、鼠标两轴。 */
    private static final int ANCHOR_ARGS = 7;
    private final Method method;
    private final int arity;
    private boolean failureLogged;

    /** 只加载游戏类加载器可见的类型，绝不从代理自身的类加载器找游戏类。 */
    static InventoryPlayerPreview bind(Mapping mapping, ClassLoader loader, Class<?> graphicsType) {
        if (mapping == null || loader == null || graphicsType == null) {
            return null;
        }
        for (String name : mapping.classNameCandidates(ClassType.INVENTORY_SCREEN)) {
            Class<?> screen = Reflect.loadWithoutInit(name, loader);
            if (screen != null) {
                InventoryPlayerPreview preview = new InventoryPlayerPreview(mapping, screen, graphicsType);
                if (preview.method != null) {
                    return preview;
                }
            }
        }
        return null;
    }

    /** 测试可传入具备与库存预览同签名的类，验证选重载和调用参数。 */
    InventoryPlayerPreview(Mapping mapping, Class<?> screen, Class<?> graphicsType) {
        Method found = find(mapping, screen, graphicsType, "extractEntityInInventoryFollowsMouse", REGION_ARGS);
        if (found == null) {
            found = find(mapping, screen, graphicsType, "renderEntityInInventoryFollowsMouse", REGION_ARGS);
        }
        if (found == null) {
            found = find(mapping, screen, graphicsType, "renderEntityInInventoryFollowsMouse", ANCHOR_ARGS);
        }
        method = found;
        arity = found == null ? 0 : found.getParameterTypes().length;
    }

    private static Method find(Mapping mapping, Class<?> screen, Class<?> graphicsType,
                               String canonical, int argumentCount) {
        if (mapping == null || screen == null || graphicsType == null) {
            return null;
        }
        for (MethodCandidate candidate : mapping.methodCandidates(ClassType.INVENTORY_SCREEN, canonical)) {
            for (Method method : screen.getDeclaredMethods()) {
                Class<?>[] args = method.getParameterTypes();
                if (!method.getName().equals(candidate.name()) || !Modifier.isStatic(method.getModifiers())
                        || method.getReturnType() != void.class || args.length != argumentCount
                        || args[0] != graphicsType || args[argumentCount - 1].isPrimitive()) {
                    continue;
                }
                boolean shape = argumentCount == REGION_ARGS
                        ? args[1] == int.class && args[2] == int.class && args[3] == int.class
                            && args[4] == int.class && args[5] == int.class && args[6] == float.class
                            && args[7] == float.class && args[8] == float.class
                        : args[1] == int.class && args[2] == int.class && args[3] == int.class
                            && args[4] == float.class && args[5] == float.class;
                if (shape) {
                    try {
                        method.setAccessible(true);
                    } catch (Throwable ignored) {
                        // public 的游戏入口不需要额外访问权限。
                    }
                    return method;
                }
            }
        }
        return null;
    }

    boolean draw(Object graphics, Object player, float left, float top, float right, float bottom,
                 float mouseX, float mouseY) {
        if (graphics == null || player == null || method == null || right - left < 16.0f
                || bottom - top < 24.0f || !method.getParameterTypes()[arity - 1].isInstance(player)) {
            return false;
        }
        int x1 = Math.round(left), y1 = Math.round(top);
        int x2 = Math.round(right), y2 = Math.round(bottom);
        int size = Math.max(1, Math.round(Math.min((x2 - x1) * 0.52f, (y2 - y1) * 0.43f)));
        try {
            if (arity == REGION_ARGS) {
                // vanilla 库存界面同一口径：区域中心站位，0.0625 为实体显示高度的偏移。
                method.invoke(null, graphics, x1, y1, x2, y2, size, 0.0625f,
                        mouseX, mouseY, player);
            } else {
                // 老版入口按脚下锚点定位；相对鼠标偏移控制头部转向。
                int center = Math.round((left + right) * 0.5f);
                int feet = y2 - Math.max(2, size / 5);
                method.invoke(null, graphics, center, feet, size,
                        center - mouseX, (feet - size) - mouseY, player);
            }
            return true;
        } catch (Throwable failure) {
            if (!failureLogged) {
                failureLogged = true;
                System.out.println("[nocturne] native player preview unavailable: " + failure);
            }
            return false;
        }
    }
}
