package dev.noturne.client.module.modules;

import dev.noturne.client.NoturneClient;
import dev.noturne.client.game.GameBridge;
import dev.noturne.client.mapping.ClassType;
import dev.noturne.client.module.Category;
import dev.noturne.client.module.Module;
import dev.noturne.client.value.NumberValue;

import java.lang.reflect.Method;

/**
 * 启用期间提升游戏亮度设置，禁用时还原为启用前的原值。
 *
 * <p>通过映射写入真实的 {@code Options.gamma}，效果与玩家把亮度滑块拖到顶端完全相同，
 * 只是被脚本化并可回退。
 *
 * <p>跨版本类型分派：{@code gamma} 的<b>运行时类型</b>在版本间不同——1.8.9 是 {@code float}
 * 字段，1.21/26.x 是 {@code OptionInstance} 对象。本模块先读出字段当前值，再按其类型选择写入
 * 方式（数值型直接反射写 Float；对象型改写对象内部的数值），避免向对象型字段写 Float 导致
 * {@code IllegalArgumentException} 被反射层吞掉、模块静默失效。
 */
public final class FullBrightModule extends Module {

    /** 未能记录启用前原值时，禁用回退写入的亮度（等同于 1.8.9 原生默认 gamma）。 */
    private static final double FALLBACK_GAMMA = 1.0;

    /** 用户可调的亮度设置项，取值范围 1.0–15.0，步进 0.5。 */
    private final NumberValue gamma = add(new NumberValue("Gamma", 10.0, 1.0, 15.0, 0.5));

    /** 启用前 gamma 的数值快照；未记录（客户端未就绪/字段缺失）时为 {@code null}。 */
    private Object savedGamma;
    /** 写入失败是否已打过日志，避免每 tick 刷屏；成功一次后重新武装。 */
    private boolean writeFailureLogged;

    /** 模块名，注册表内唯一，也是 GUI 中的显示名。 */
    @Override
    public String name() {
        return "FullBright";
    }

    /** 归入“渲染”分组。 */
    @Override
    public Category category() {
        return Category.RENDER;
    }

    /** 启用瞬间记录原值并立即写入一次亮度，避免首个 tick 出现闪烁。 */
    @Override
    protected void onEnable() {
        captureOriginal();
        apply(gamma.get());
    }

    /** 禁用时把亮度精确还原为启用前的原值（而非硬编码 1.0），避免覆盖玩家自己的亮度设置。 */
    @Override
    protected void onDisable() {
        restoreOriginal();
    }

    /**
     * 每 tick 重新写入亮度。
     *
     * <p>必须重复应用：游戏在载入世界时会用存档中的 options 覆盖当前值，若只在启用时
     * 设置一次，切图后亮度就会失效。
     */
    @Override
    public void onTick() {
        apply(gamma.get());
    }

    /**
     * 读取 {@code Minecraft.options} 并写入其 {@code gamma} 字段，按字段实际运行时类型分派。
     *
     * <p>逐级 null 检查覆盖了客户端未启动、未进入世界、options 尚未初始化、映射字段缺失四种情况；
     * 此时跳过，下一 tick 会重试。
     */
    private void apply(double value) {
        GameBridge bridge = bridge();
        if (bridge == null) {
            return;
        }
        Object options = options(bridge);
        if (options == null) {
            return;
        }
        Object field = bridge.readField(options, ClassType.OPTIONS, "gamma");
        if (field == null) {
            return;
        }
        if (writeGamma(bridge, options, field, value)) {
            writeFailureLogged = false;
        } else {
            logWriteFailure("gamma 字段类型为 " + field.getClass().getName()
                    + "，但未找到可用的写入入口（字段名/类型可能与映射表不符）");
        }
    }

    /**
     * 记录启用前 gamma 的数值快照，供 {@link #onDisable()} 精确回滚。
     *
     * <p>数值型字段直接记录其 Number 值；{@code OptionInstance} 型字段记录对象内部的当前数值
     * （经其无参 {@code get()}）。两者都读不到时记为 {@code null}，禁用时退回
     * {@link #FALLBACK_GAMMA}。
     */
    private void captureOriginal() {
        GameBridge bridge = bridge();
        Object options = bridge == null ? null : options(bridge);
        Object field = options == null ? null : bridge.readField(options, ClassType.OPTIONS, "gamma");
        if (field instanceof Number) {
            savedGamma = field;
        } else if (field != null) {
            Object inner = readOptionValue(field);
            savedGamma = inner instanceof Number ? inner : null;
        } else {
            savedGamma = null;
        }
    }

    /** 把 gamma 还原为启用前的快照；快照缺失时退回 {@link #FALLBACK_GAMMA}。 */
    private void restoreOriginal() {
        Object snapshot = savedGamma;
        savedGamma = null;
        double target = snapshot instanceof Number ? ((Number) snapshot).doubleValue() : FALLBACK_GAMMA;

        GameBridge bridge = bridge();
        Object options = bridge == null ? null : options(bridge);
        Object field = options == null ? null : bridge.readField(options, ClassType.OPTIONS, "gamma");
        if (field == null) {
            // 客户端已不可用：无需也无法还原，快照已清空避免下次启用携带陈旧值
            return;
        }
        if (writeGamma(bridge, options, field, target)) {
            writeFailureLogged = false;
        } else {
            logWriteFailure("禁用时无法把 gamma 还原为快照值" + target);
        }
    }

    /**
     * 按 gamma 字段当前值的实际类型写入亮度。
     *
     * @param fieldValue 从 options 读出的 gamma 字段值（Number 或 OptionInstance 对象）
     * @return 写入是否真正生效
     */
    private static boolean writeGamma(GameBridge bridge, Object options, Object fieldValue, double value) {
        if (fieldValue instanceof Number) {
            // 数值型字段（1.8.9 的 float）：与字段类型匹配的直接写入
            return bridge.writeField(options, ClassType.OPTIONS, "gamma", Float.valueOf((float) value));
        }
        // 对象型字段（1.13+/26.x 的 OptionInstance）：改写对象内部的数值，字段本身不动
        return writeOption(fieldValue, value);
    }

    /** 读取 {@code Minecraft.options}；客户端未启动或未解析成功时返回 {@code null}。 */
    private static Object options(GameBridge bridge) {
        Object minecraft = bridge.minecraft();
        return minecraft == null ? null : bridge.readField(minecraft, ClassType.MINECRAFT, "options");
    }

    /**
     * 读取 {@code OptionInstance} 型选项内部的当前数值：调用其无参 {@code get()}。
     *
     * @return 数值快照；方法缺失或返回值不是数值时为 {@code null}
     */
    private static Object readOptionValue(Object option) {
        Object value = callForValue(findMethod(option.getClass(), "get"), option);
        return value instanceof Number ? value : null;
    }

    /**
     * 改写 {@code OptionInstance} 型选项内部的数值。
     *
     * <p>优先使用绕过取值校验器的 {@code force_double(double)}；不存在时回退通用 {@code set(T)}
     * （泛型擦除后形参为 Object，实参用 Double）。现代版本的亮度滑块校验域是 0..1，而本模块的
     * 取值区间是 1..15——直接写数值会被钳到滑块上限（即最大亮度），这正是本模块想要的语义；
     * 能走 {@code force_double} 时则可原样写入。
     *
     * @return 是否成功调用到任一入口
     */
    private static boolean writeOption(Object option, double value) {
        Class<?> type = option.getClass();
        Method force = findMethod(type, "force_double", double.class);
        if (force == null) {
            force = findMethod(type, "forceDouble", double.class);
        }
        if (call(force, option, Double.valueOf(value))) {
            return true;
        }
        Method set = findMethod(type, "set", Object.class);
        if (set == null) {
            set = findMethod(type, "set", Double.class);
        }
        return call(set, option, Double.valueOf(value));
    }

    /** 沿类继承链按名称与形参列表查找方法并置为可访问；未找到返回 {@code null}。 */
    private static Method findMethod(Class<?> type, String name, Class<?>... parameters) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod(name, parameters);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // 选项实现可能把方法放在父类上，继续上溯
            }
        }
        return null;
    }

    /** 调用单参方法；方法缺失或调用抛异常返回 false。 */
    private static boolean call(Method method, Object target, Object argument) {
        if (method == null) {
            return false;
        }
        try {
            method.invoke(target, argument);
            return true;
        } catch (Throwable error) {
            // 具体原因由调用点的一次性诊断日志统一给出，避免每 tick 刷屏
            return false;
        }
    }

    /** 调用无参方法并返回其结果；方法缺失或调用失败返回 {@code null}。 */
    private static Object callForValue(Method method, Object target) {
        if (method == null) {
            return null;
        }
        try {
            return method.invoke(target);
        } catch (Throwable error) {
            return null;
        }
    }

    /** 打印一次性的“gamma 写入失败”诊断，避免静默失效；成功一次后重新武装。 */
    private void logWriteFailure(String reason) {
        if (writeFailureLogged) {
            return;
        }
        writeFailureLogged = true;
        System.out.println("[noturne] FullBright: gamma 写入失败：" + reason);
    }

    /** 取当前客户端的游戏桥接；客户端尚未构造时返回 {@code null}。 */
    private static GameBridge bridge() {
        NoturneClient client = NoturneClient.get();
        return client == null ? null : client.gameBridge();
    }
}
