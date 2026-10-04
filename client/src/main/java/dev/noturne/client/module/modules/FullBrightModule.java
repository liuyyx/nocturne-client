package dev.noturne.client.module.modules;

import dev.noturne.client.NoturneClient;
import dev.noturne.client.game.GameBridge;
import dev.noturne.client.mapping.ClassType;
import dev.noturne.client.module.Category;
import dev.noturne.client.module.Module;
import dev.noturne.client.value.NumberValue;

/**
 * 启用期间提升游戏亮度设置，禁用时恢复为默认值。
 *
 * <p>通过映射写入真实的 {@code Options.gamma} 字段，效果与玩家把亮度滑块拖到顶端完全
 * 相同，只是被脚本化并可回退。
 */
public final class FullBrightModule extends Module {

    /** 游戏原生的亮度（gamma）值，禁用时恢复为此值。 */
    private static final double VANILLA_GAMMA = 1.0;

    /** 用户可调的亮度设置项，取值范围 1.0–15.0，步进 0.5。 */
    private final NumberValue gamma = add(new NumberValue("Gamma", 10.0, 1.0, 15.0, 0.5));

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

    /** 启用瞬间立即写入一次亮度，避免首个 tick 出现闪烁。 */
    @Override
    protected void onEnable() {
        apply(gamma.get());
    }

    /** 禁用时把亮度还原为游戏默认值。 */
    @Override
    protected void onDisable() {
        apply(VANILLA_GAMMA);
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
     * 读取 {@code Minecraft.options} 并写入其 {@code gamma} 字段。
     *
     * <p>逐级 null 检查覆盖了客户端未启动、未进入世界、options 尚未初始化三种情况；
     * 此时静默跳过，下一 tick 会重试。
     */
    private void apply(double value) {
        GameBridge bridge = bridge();
        if (bridge == null) {
            return;
        }
        Object minecraft = bridge.minecraft();
        if (minecraft == null) {
            return;
        }
        Object options = bridge.readField(minecraft, ClassType.MINECRAFT, "options");
        if (options == null) {
            return;
        }
        bridge.writeField(options, ClassType.OPTIONS, "gamma", (float) value);
    }

    /** 取当前客户端的游戏桥接；客户端尚未构造时返回 {@code null}。 */
    private static GameBridge bridge() {
        NoturneClient client = NoturneClient.get();
        return client == null ? null : client.gameBridge();
    }
}
