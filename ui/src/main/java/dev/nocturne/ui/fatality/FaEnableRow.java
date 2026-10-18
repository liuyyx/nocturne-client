package dev.nocturne.ui.fatality;

import dev.nocturne.client.module.Module;

import java.util.function.Consumer;

/**
 * 模块自己的第一行："Enabled" 勾选框。
 *
 * <p>上游在这一行还挂了按键绑定与"隐藏"眼睛两个小控件；本项目没有模块级按键绑定
 * （{@code Module} 无 key 字段），也没有数组列表的 Hide 开关，因此只保留主开关。
 */
final class FaEnableRow extends FaCheckRow {

    FaEnableRow(final Module module) {
        super(null, "Enabled", module::isEnabled, new Consumer<Boolean>() {
            @Override
            public void accept(Boolean enabled) {
                if (module.isEnabled() != enabled) {
                    module.toggle();
                }
            }
        });
    }
}
