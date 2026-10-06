package dev.nocturne.client.module.modules;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.Module;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

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
    /** 模式：Gamma 写亮度 / Night Vision 上夜视药水（对齐 OpenVape Fullbright 行为）。 */
    private final ModeValue mode = add(new ModeValue("Mode", "Gamma", "Gamma", "Night Vision"));
    /** Gamma 模式下亮度渐变过渡；Night Vision 模式下无意义。 */
    private final BooleanValue fade = add(new BooleanValue("Fade", false));
    /** 启用前 gamma 的数值快照；未记录（客户端未就绪/字段缺失）时为 {@code null}。 */
    private Object savedGamma;
    /** 写入失败是否已打过日志，避免每 tick 刷屏；成功一次后重新武装。 */
    private boolean writeFailureLogged;
    /** Fade 渐变当前值（Gamma 模式 + Fade 开启时用）；负数表示尚未初始化。 */
    private double fadeCurrent = -1.0;
    /** 夜视药水不可用是否已打过日志（表驱动门：缺成员版本只报一次）。 */
    private boolean nightVisionLogged;

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
        nightVisionLogged = false;
        if (isNightVision()) {
            return;
        }
        fadeCurrent = -1.0;
        captureOriginal();
        apply(gamma.get());
    }

    /** 禁用时把亮度精确还原为启用前的原值（而非硬编码 1.0），避免覆盖玩家自己的亮度设置。 */
    @Override
    protected void onDisable() {
        if (isNightVision()) {
            return;
        }
        restoreOriginal();
    }

    /**
     * 每 tick 按模式驱动：Gamma 写亮度（Fade 开启时渐变），Night Vision 续药水。
     *
     * <p>必须重复应用：游戏在载入世界时会用存档中的 options 覆盖当前值，若只在启用时
     * 设置一次，切图后亮度就会失效；药水同理（时长耗尽前续杯）。
     */
    @Override
    public void onTick() {
        if (isNightVision()) {
            applyNightVision();
            return;
        }
        double target = gamma.get();
        if (!fade.get()) {
            fadeCurrent = target;
            apply(target);
            return;
        }
        if (fadeCurrent < 0) {
            fadeCurrent = currentGamma();
            if (fadeCurrent < 0) {
                fadeCurrent = target;
            }
        }
        double step = 0.4;
        if (fadeCurrent < target) {
            fadeCurrent = Math.min(target, fadeCurrent + step);
        } else if (fadeCurrent > target) {
            fadeCurrent = Math.max(target, fadeCurrent - step);
        }
        apply(fadeCurrent);
    }

    /** @return 当前是否为 Night Vision 模式 */
    private boolean isNightVision() {
        return mode.is("Night Vision");
    }

    /**
     * 读当前 gamma 数值（Fade 起点用）；读不到返回负数，调用方回退到目标值。
     */
    private double currentGamma() {
        GameBridge bridge = bridge();
        Object options = bridge == null ? null : options(bridge);
        Object field = options == null ? null : bridge.readField(options, ClassType.OPTIONS, "gamma");
        if (field instanceof Number) {
            return ((Number) field).doubleValue();
        }
        if (field != null) {
            Object inner = readOptionValue(field);
            if (inner instanceof Number) {
                return ((Number) inner).doubleValue();
            }
        }
        return -1.0;
    }

    /**
     * Night Vision 模式：给本地玩家续夜视药水。
     *
     * <p>表驱动门：addEffect / NIGHT_VISION 任一缺成员（映射表 absent）就只打一次日志
     * 并跳过——错版本上干净禁用，不断 tick 刷屏也不抛异常。
     */
    private void applyNightVision() {
        GameBridge bridge = bridge();
        if (bridge == null) {
            return;
        }
        Object player = bridge.player();
        if (player == null) {
            return;
        }
        Object effectType = readStaticField(bridge, ClassType.MOB_EFFECT, "NIGHT_VISION");
        if (effectType == null) {
            logGateOnce("夜视药水类型缺失（版本无该成员），Night Vision 模式已禁用");
            return;
        }
        Object instance = newEffectInstance(bridge, effectType);
        if (instance == null) {
            logGateOnce("夜视药水实例构造失败，Night Vision 模式已禁用");
            return;
        }
        Object result = bridge.callMapped(player, ClassType.LIVING_ENTITY, "addEffect", instance);
        if (result == null) {
            logGateOnce("addEffect 调用失败，Night Vision 模式已禁用");
        }
    }

    /**
     * 读映射类的静态字段（如 MobEffect.NIGHT_VISION）。
     *
     * <p>表驱动门的第一道：类或字段 absent 时返回 null，调用方打一次日志并跳过。
     */
    private static Object readStaticField(GameBridge bridge, ClassType owner, String canonicalField) {
        String mappedClass = bridge.mapping().className(owner);
        if (mappedClass == null) {
            return null;
        }
        ClassLoader loader = playerLoader(bridge);
        Class<?> type;
        try {
            type = Class.forName(mappedClass, false, loader);
        } catch (Throwable t) {
            return null;
        }
        String mappedField = bridge.mapping().fieldName(owner, canonicalField);
        if (mappedField == null) {
            return null;
        }
        try {
            java.lang.reflect.Field field = type.getDeclaredField(mappedField);
            field.setAccessible(true);
            return field.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 构造药水效果实例：按 (效果类型, 时长 tick, 等级) 形参匹配构造器。
     *
     * <p>不经过映射表（表无构造器档）：类名从表里取，构造器按形参数量与首参类型匹配。
     * 1.8.9 PotionEffect(int, int, int)，现代 MobEffectInstance(Holder,int,int)。
     */
    private static Object newEffectInstance(GameBridge bridge, Object effectType) {
        String mappedClass = bridge.mapping().className(ClassType.MOB_EFFECT_INSTANCE);
        if (mappedClass == null) {
            return null;
        }
        ClassLoader loader = playerLoader(bridge);
        Class<?> type;
        try {
            type = Class.forName(mappedClass, false, loader);
        } catch (Throwable t) {
            return null;
        }
        for (java.lang.reflect.Constructor<?> ctor : type.getDeclaredConstructors()) {
            Class<?>[] params = ctor.getParameterTypes();
            if (params.length != 3 || !params[0].isInstance(effectType)) {
                continue;
            }
            if (!params[1].isPrimitive() || !params[2].isPrimitive()) {
                continue;
            }
            try {
                ctor.setAccessible(true);
                return ctor.newInstance(effectType, coerceInt(params[1], 5220), coerceInt(params[2], 0));
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    /** 按形参类型把 int 常量装箱（int/long/short/byte 均可）。 */
    private static Object coerceInt(Class<?> param, int value) {
        if (param == long.class) {
            return Long.valueOf(value);
        }
        if (param == short.class) {
            return Short.valueOf((short) value);
        }
        if (param == byte.class) {
            return Byte.valueOf((byte) value);
        }
        return Integer.valueOf(value);
    }

    /** 取玩家实例的类加载器（游戏类加载器）；取不到退回本类加载器。 */
    private static ClassLoader playerLoader(GameBridge bridge) {
        Object player = bridge.player();
        if (player != null && player.getClass().getClassLoader() != null) {
            return player.getClass().getClassLoader();
        }
        return FullBrightModule.class.getClassLoader();
    }

    /** 表驱动门日志：缺成员版本只报一次。 */
    private void logGateOnce(String reason) {
        if (nightVisionLogged) {
            return;
        }
        nightVisionLogged = true;
        System.out.println("[nocturne] FullBright: " + reason);
    }
    /**
     * 读取 {@code Minecraft.options} 并写入其 {@code gamma} 字段，按字段实际运行时类型分派。
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

    /** 把 gamma 还原为启用前的快照；快照缺失时保守不动，绝不把玩家亮度覆盖成 FALLBACK。 */
    private void restoreOriginal() {
        Object snapshot = savedGamma;
        if (snapshot == null) {
            // (1) 启用时根本没读到原值 — 无法盲目还原到 1.0，那会覆盖玩家自定的亮度；
            // (2) 已在别处被恢复过 → snapshot 已清空，再次调用是无害的重入
            return;
        }
        savedGamma = null;
        double target = ((Number) snapshot).doubleValue();

        GameBridge bridge = bridge();
        Object options = bridge == null ? null : options(bridge);
        Object field = options == null ? null : bridge.readField(options, ClassType.OPTIONS, "gamma");
        if (field == null) {
            // 客户端已不可用：快照已清空避免下次启用携带陈旧值
            return;
        }
        if (writeGamma(bridge, options, field, target)) {
            writeFailureLogged = false;
        } else {
            logWriteFailure("gamma 还原失败，玩家亮度可能停留在启用时的值");
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
        System.out.println("[nocturne] FullBright: gamma 写入失败：" + reason);
    }

    /** 取当前客户端的游戏桥接；客户端尚未构造时返回 {@code null}。 */
    private static GameBridge bridge() {
        NocturneClient client = NocturneClient.get();
        return client == null ? null : client.gameBridge();
    }
}
