package dev.nocturne.client.module;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.event.KeyEvent;
import dev.nocturne.client.runtime.InputBlock;
import dev.nocturne.client.value.KeyValue;

/**
 * 模块热键：所有版本共用的唯一入口。
 *
 * <p>按键事件已经是 AWT VK 规范空间（GLFW/SDL 的差异由 {@code KeyMap} 吃掉），因此这里
 * 不需要任何版本分支。界面打开时 {@link InputBlock#shouldBlock()} 为 true，绑定过程不会把
 * 正在录入的键当成模块开关。
 */
public final class ModuleKeybinds {

    private ModuleKeybinds() {
    }

    /** 在客户端事件总线上安装监听。重复调用会得到多个监听，故只由 {@code install()} 调用一次。 */
    public static void install(final NocturneClient client) {
        // 没有键盘事件源的版本（1.8.9–1.12 的 KeyboardHandler 在映射表里 absent）由轮询回退
        // 把 LWJGL2 的按键边沿变成 KeyEvent；事件源可用时该监听器自己静默退出。
        dev.nocturne.client.runtime.NocturneRuntime.addListener(
                new dev.nocturne.client.runtime.KeyPollListener(client));
        client.events().subscribe(KeyEvent.class, new java.util.function.Consumer<KeyEvent>() {
            @Override
            public void accept(KeyEvent event) {
                if (!event.pressed || event.cancelled || client.isPanicked()) {
                    return;
                }
                // GUI 打开时按键属于界面（包括键位绑定行），不得触发模块。
                if (InputBlock.shouldBlock()) {
                    return;
                }
                for (Module module : client.modules().all()) {
                    KeyValue bind = module.bind();
                    if (!bind.bound() || bind.get().intValue() != event.keyCode) {
                        continue;
                    }
                    try {
                        module.toggle();
                    } catch (Throwable failure) {
                        System.out.println("[nocturne] module '" + module.name()
                                + "' keybind toggle failed: " + failure);
                    }
                    // 被模块消费的键不再交给游戏（例如绑到 R 时不应同时换弹）。
                    event.cancelled = true;
                    if (!dev.nocturne.client.module.modules.misc.ClientSettingsModule.multiKeybinding()) {
                        return;
                    }
                }
            }
        });
    }
}
