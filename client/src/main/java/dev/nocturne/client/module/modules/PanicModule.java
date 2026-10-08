package dev.nocturne.client.module.modules;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.Module;

/**
 * 自销毁：打开它就把客户端从"正在运行"变成"看起来没注入过"。
 *
 * <p>行为：GUI 再也打不开、开关键不再响应、所有模块停用、不再有任何每帧工作。实现见
 * {@link NocturneClient#panic()}——禁用全部模块 + 关掉模块驱动闸门 + 清空 bootstrap 层分发器
 * （列表空了之后，注入的帧调用只是一次空遍历）。
 *
 * <p><b>不可逆</b>：agent 无法从目标 JVM 里卸载自己，"恢复"只能靠重启游戏。因此本模块不提供关闭
 * 路径：打开即销毁，之后连这个开关本身也不会再出现（GUI 已经不会再画）。
 *
 * <p>归入 MISC：它不是"功能"，而是一个一次性动作。
 */
public final class PanicModule extends Module {

    /** 模块名，注册表内唯一，也是 GUI 中的显示名。 */
    @Override
    public String name() {
        return "Panic";
    }

    /** 归入杂项。 */
    @Override
    public Category category() {
        return Category.MISC;
    }

    /**
     * 打开即自销毁。
     *
     * <p>注意这里会重入本模块自己的 {@code setEnabled}：{@code panic()} 遍历禁用所有模块时，
     * 本模块的 {@code enabled} 标志还没被外层提交（仍是 {@code false}），所以那次调用会因"值未变化"
     * 直接返回——不会递归、也不会重复跑生命周期钩子。副作用是它自己的标志最终留在 {@code true}，
     * 但那时客户端已经停用，没有任何代码再读它。
     */
    @Override
    protected void onEnable() {
        NocturneClient client = NocturneClient.get();
        if (client == null) {
            return;
        }
        client.panic();
    }
}
