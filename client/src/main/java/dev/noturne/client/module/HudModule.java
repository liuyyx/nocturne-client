package dev.noturne.client.module;

import dev.noturne.client.NoturneClient;
import dev.noturne.client.hud.HudSink;

import java.util.function.Supplier;

/**
 * 仅用于 HUD 显示的模块：启用时发布一行文字，禁用时移除该行。
 *
 * <p>它不参与 {@link Module#onTick()}：文本由 HUD 每帧拉取，因此本类只管理注册与注销。
 *
 * <p>注册时序：HUD 汇（{@link HudSink}）可能晚于模块启用才安装，也可能在使用中被整体替换。
 * 为此本类记录“当前行挂在哪个汇上”，并在汇变化时把行从旧汇迁移到新汇（见
 * {@link #onHudSinkChanged(HudSink, HudSink)}），因此“汇为 null 时启用”不会丢行，
 * “汇被替换”也不会留下无法移除的孤儿行。
 */
public abstract class HudModule extends Module {

    /** 当前挂着本模块文本行的汇；未注册（汇缺失或已注销）时为 {@code null}。 */
    private HudSink registeredSink;

    /** HUD 接收器使用的稳定 id，必须在各模块间唯一。 */
    protected abstract String hudId();

    /**
     * 返回本模块要显示的文本供给器。
     *
     * <p>调用时机：每次注册（{@link #onEnable()} 或 HUD 汇安装/更换时补登记）调用<b>一次</b>；
     * 返回的 {@link Supplier} 随后由 HUD 每帧拉取。因此实现必须返回“每次 {@code get()} 都给出
     * 实时值”的供给器，不得返回一次性文本快照，否则 HUD 会永远停留在注册瞬间的值。
     * 本方法调用频次很低，实现可在构造期创建并缓存同一个供给器实例。
     */
    protected abstract Supplier<String> hudText();

    /**
     * 启用时向 HUD 接收器注册本模块的文本行；汇尚未安装时留待
     * {@link #onHudSinkChanged(HudSink, HudSink)} 补登记。
     */
    @Override
    protected final synchronized void onEnable() {
        register();
    }

    /** 禁用时向 HUD 接收器注销本模块的文本行；必须与注册使用同一 id。 */
    @Override
    protected final synchronized void onDisable() {
        unregister();
    }

    /**
     * 汇安装/替换/清除时把本模块的行迁移到新汇。
     *
     * <p>{@code previous == current}（同一汇重复安装）由客户端提前短路，这里只处理真正变化的情形。
     */
    @Override
    public final synchronized void onHudSinkChanged(HudSink previous, HudSink current) {
        if (registeredSink != null) {
            // 行已挂在某个汇上：先从记录中的汇移除，避免替换后旧行成为无法移除的孤儿
            registeredSink.remove(hudId());
            registeredSink = null;
        } else if (previous != null && previous != current) {
            // 记录与实际不一致（例如外部直接调用过 sink.remove）时的防御性清理
            previous.remove(hudId());
        }
        if (current != null && isEnabled()) {
            // 已启用但此前因汇缺失而未注册：补登记；或把行重新挂到新汇上
            register();
        }
    }

    /** 向当前汇注册本行；汇为 {@code null} 时保持待注册状态，待汇安装后补登记。 */
    private void register() {
        HudSink sink = sink();
        if (sink == null) {
            return;
        }
        sink.add(hudId(), hudText());
        registeredSink = sink;
    }

    /** 从记录中的汇注销本行；从未注册时无副作用。 */
    private void unregister() {
        if (registeredSink != null) {
            registeredSink.remove(hudId());
            registeredSink = null;
        }
    }

    /**
     * 获取当前客户端实例的 HUD 接收器；客户端尚未构造时返回 {@code null}。
     *
     * <p>客户端可能在模块启用之后才构造完成，故每次都重新获取而非缓存。
     */
    private static HudSink sink() {
        NoturneClient client = NoturneClient.get();
        return client == null ? null : client.hudSink();
    }
}
