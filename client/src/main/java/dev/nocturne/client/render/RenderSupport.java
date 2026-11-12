package dev.nocturne.client.render;

import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;

import java.awt.Color;

/** 视觉模块共用的实体遍历、分类与颜色运算；热路径不创建辅助对象。 */
public final class RenderSupport {

    /** 对实体集合的最小访问面，避免调用方使用 lambda 时引入额外依赖。 */
    public interface EntityVisitor {
        void visit(Object entity);
    }

    /** 视觉模块统一使用的实体类别。 */
    public enum Kind {
        PLAYER, MONSTER, ANIMAL, AMBIENT, OTHER
    }

    private RenderSupport() {
    }

    /** 遍历 Iterable、对象数组或单个实体；null 与不可迭代表示没有可访问实体。 */
    public static void forEachEntity(GameBridge bridge, EntityVisitor visitor) {
        if (bridge == null || visitor == null) {
            return;
        }
        Object entities = bridge.entities();
        if (entities instanceof Iterable) {
            for (Object entity : (Iterable<?>) entities) {
                if (entity != null) {
                    visitor.visit(entity);
                }
            }
        } else if (entities instanceof Object[]) {
            for (Object entity : (Object[]) entities) {
                if (entity != null) {
                    visitor.visit(entity);
                }
            }
        } else if (entities != null) {
            visitor.visit(entities);
        }
    }

    /** 按映射类型判型；找不到某个版本专属类型时自然落入下一类别。 */
    public static Kind kind(GameBridge bridge, Object entity) {
        if (bridge == null || entity == null) {
            return Kind.OTHER;
        }
        if (bridge.isInstance(entity, ClassType.PLAYER)) {
            return Kind.PLAYER;
        }
        if (bridge.isInstance(entity, ClassType.MONSTER)) {
            return Kind.MONSTER;
        }
        if (bridge.isInstance(entity, ClassType.ANIMAL)
                || bridge.isInstance(entity, ClassType.WATER_MOB)
                || bridge.isInstance(entity, ClassType.VILLAGER)) {
            return Kind.ANIMAL;
        }
        if (bridge.isInstance(entity, ClassType.AMBIENT)) {
            return Kind.AMBIENT;
        }
        return Kind.OTHER;
    }

    /** 乘 ARGB 的 alpha；factor 越界按 0..1 钳制，结果 alpha 始终落在 0..255。 */
    public static int withAlpha(int argb, float factor) {
        float clamped = Math.max(0f, Math.min(1f, factor));
        int alpha = Math.round(((argb >>> 24) & 0xFF) * clamped);
        return (argb & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
    }

    /** 逐 ARGB 通道线性插值；t 越界时钳到端点。 */
    public static int blend(int from, int to, float t) {
        float clamped = Math.max(0f, Math.min(1f, t));
        float inverse = 1f - clamped;
        int alpha = Math.round(((from >>> 24) & 0xFF) * inverse + ((to >>> 24) & 0xFF) * clamped);
        int red = Math.round(((from >>> 16) & 0xFF) * inverse + ((to >>> 16) & 0xFF) * clamped);
        int green = Math.round(((from >>> 8) & 0xFF) * inverse + ((to >>> 8) & 0xFF) * clamped);
        int blue = Math.round((from & 0xFF) * inverse + (to & 0xFF) * clamped);
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    /** HSB 转 ARGB，并使用调用方给出的 alpha，而不是 Color.HSBtoRGB 的隐式不透明 alpha。 */
    public static int hsv(int alpha, float hue, float saturation, float brightness) {
        int rgb = Color.HSBtoRGB(hue, saturation, brightness) & 0x00FFFFFF;
        return (Math.max(0, Math.min(255, alpha)) << 24) | rgb;
    }

    /** 只按对象身份判断本地玩家，避免不同加载器的 equals 实现介入。 */
    public static boolean isSelf(GameBridge bridge, Object entity) {
        return bridge != null && entity != null && entity == bridge.player();
    }
}
