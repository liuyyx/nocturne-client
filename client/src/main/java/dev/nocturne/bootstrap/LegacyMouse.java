package dev.nocturne.bootstrap;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 旧世代鼠标增量缓存清理器：只存在于 bootstrap 搜索路径（由 agent 的 bridge jar 提供）。
 *
 * <p>为什么需要它：1.8.9/1.12.2 的 {@code MouseHelper#mouseXYChange()V} 把原始增量写进两个
 * {@code int} 字段，之后由渲染线程读取。我们的界面打开时整条方法被短路，字段就停在上一帧的值；
 * 关闭界面恢复输入后，渲染线程会先把这份**陈旧增量**再吃一次（画面突然跳一下）。
 *
 * <p>为什么不在转换器里写死字段名：字段名随版本/命名空间变化（1.8.9 是 {@code a/b}，Forge 是
 * SRG 名），而 {@code MouseHelper} 只有两个实例整型字段（正是 deltaX/deltaY），按类型收集即可，
 * 不需要任何映射条目，也不会猜错名字。
 *
 * <p>只依赖 JDK；不得 import 任何项目类。
 */
public final class LegacyMouse {

    /** 运行期类 → 其实例整型字段（只收集一次）。 */
    private static final ConcurrentHashMap<Class<?>, Field[]> INT_FIELDS =
            new ConcurrentHashMap<Class<?>, Field[]>();

    private LegacyMouse() {
    }

    /**
     * 清零目标对象的全部实例整型字段。
     *
     * @param helper {@code MouseHelper} 实例；{@code null} 时无操作
     */
    public static void clearDeltas(Object helper) {
        if (helper == null) {
            return;
        }
        try {
            Class<?> type = helper.getClass();
            Field[] fields = INT_FIELDS.get(type);
            if (fields == null) {
                fields = collect(type);
                INT_FIELDS.put(type, fields);
            }
            for (Field field : fields) {
                field.setInt(helper, 0);
            }
        } catch (Throwable ignored) {
            // 尽力而为：清理失败不能让游戏方法抛异常。
        }
    }

    /**
     * 收集实例整型字段。
     *
     * @param type 目标类
     * @return 可写字段数组
     */
    private static Field[] collect(Class<?> type) {
        java.util.List<Field> found = new java.util.ArrayList<Field>(2);
        for (Field field : type.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) || field.getType() != int.class) {
                continue;
            }
            try {
                field.setAccessible(true);
                found.add(field);
            } catch (Throwable ignored) {
                // 无法访问的字段跳过（模块系统限制时只清能清的）
            }
        }
        return found.toArray(new Field[0]);
    }
}
