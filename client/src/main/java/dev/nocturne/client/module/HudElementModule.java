package dev.nocturne.client.module;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.hud.HudElement;
import dev.nocturne.client.hud.HudElementSink;

/** 启用期向 UI 发布一个复合 HUD 元素的模块基类；汇安装或替换时自动迁移。 */
public abstract class HudElementModule extends Module {
    private final String name;
    private HudElementSink registeredSink;

    protected HudElementModule(String name) {
        this.name = name;
    }

    @Override
    public final String name() {
        return name;
    }

    @Override
    public final Category category() {
        return Category.RENDER;
    }

    /** 元素在模块构造期创建一次，此后返回同一实例。 */
    protected abstract HudElement element();

    @Override
    protected final synchronized void onEnable() {
        register();
        try {
            onHudStart();
        } catch (RuntimeException failure) {
            unregister();
            throw failure;
        } catch (Error failure) {
            unregister();
            throw failure;
        }
    }

    @Override
    protected final synchronized void onDisable() {
        onHudStop();
        unregister();
    }

    /** 元素已注册后调用；事件订阅在此安装。 */
    protected void onHudStart() {
    }

    /** 元素注销前调用；事件订阅在此退订。 */
    protected void onHudStop() {
    }

    @Override
    public final synchronized void onHudElementSinkChanged(HudElementSink previous,
                                                            HudElementSink current) {
        if (registeredSink != null) {
            registeredSink.removeElement(element().id());
            registeredSink = null;
        } else if (previous != null && previous != current) {
            previous.removeElement(element().id());
        }
        if (current != null && isEnabled()) {
            register();
        }
    }

    private void register() {
        HudElementSink sink = sink();
        if (sink == null) {
            return;
        }
        sink.addElement(element());
        registeredSink = sink;
    }

    private void unregister() {
        if (registeredSink != null) {
            registeredSink.removeElement(element().id());
            registeredSink = null;
        }
    }

    private static HudElementSink sink() {
        NocturneClient client = NocturneClient.get();
        return client == null ? null : client.hudElementSink();
    }
}
