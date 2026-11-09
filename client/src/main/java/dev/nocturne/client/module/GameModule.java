package dev.nocturne.client.module;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.action.PlayerState;
import dev.nocturne.client.event.EventBus;
import dev.nocturne.client.game.GameBridge;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 游戏功能模块的薄基类：固定名称/分类，并保证启用期注册的订阅在停用时全部退订。
 * 子类在 {@link #onStart()} 中调用 {@link #listen(Class, Consumer)}，不要持有跨世界事件状态。
 */
public abstract class GameModule extends Module {
    private final String name;
    private final Category category;
    private final List<EventBus.Subscription> subscriptions = new ArrayList<EventBus.Subscription>();

    protected GameModule(String name, Category category) {
        this.name = name;
        this.category = category;
    }

    @Override
    public final String name() {
        return name;
    }

    @Override
    public final Category category() {
        return category;
    }

    /**
     * 副作用是否已安装（onStart 已成功、onStop 尚未执行）。
     *
     * <p>启用/挂起/恢复三条路径可能并发到达：死亡界面里重新启用模块时，注册表的 suspended
     * 列表仍持有它，重生后的 onResume 会再调一次 onEnable。没有这个闸门就会重复订阅，同一个
     * 回调被投递两次。所有读写都在 {@code this} 对象锁内，和 {@link Module#setEnabled} 的串行化
     * 契约一致。
     */
    private boolean running;

    @Override
    protected final void onEnable() {
        synchronized (this) {
            if (running) {
                return;
            }
            running = true;
        }
        try {
            onStart();
        } catch (RuntimeException failure) {
            synchronized (this) {
                running = false;
            }
            unsubscribeAll();
            throw failure;
        } catch (Error failure) {
            synchronized (this) {
                running = false;
            }
            unsubscribeAll();
            throw failure;
        }
    }

    @Override
    protected final void onDisable() {
        synchronized (this) {
            if (!running) {
                return;
            }
            running = false;
        }
        try {
            onStop();
        } finally {
            unsubscribeAll();
        }
    }

    private void unsubscribeAll() {
        java.util.List<EventBus.Subscription> pending;
        synchronized (this) {
            pending = new ArrayList<EventBus.Subscription>(subscriptions);
            subscriptions.clear();
        }
        NocturneClient client = NocturneClient.get();
        if (client != null) {
            for (EventBus.Subscription subscription : pending) {
                client.events().unsubscribe(subscription);
            }
        }
    }

    protected void onStart() {
    }

    protected void onStop() {
    }

    protected final <T> void listen(Class<T> type, Consumer<T> listener) {
        NocturneClient client = NocturneClient.get();
        if (client == null) {
            return;
        }
        EventBus.Subscription subscription = client.events().subscribe(type, listener);
        synchronized (this) {
            subscriptions.add(subscription);
        }
    }

    protected final <T> void listen(Class<T> type, EventBus.Priority priority, Consumer<T> listener) {
        NocturneClient client = NocturneClient.get();
        if (client == null) {
            return;
        }
        EventBus.Subscription subscription = client.events().subscribe(type, priority, listener);
        synchronized (this) {
            subscriptions.add(subscription);
        }
    }

    protected final NocturneClient client() {
        return NocturneClient.get();
    }

    protected final GameBridge bridge() {
        NocturneClient client = client();
        return client == null ? null : client.gameBridge();
    }

    protected final ActionApi actions() {
        NocturneClient client = client();
        return client == null ? null : client.actions();
    }

    protected final PlayerState state() {
        ActionApi actions = actions();
        return actions == null ? PlayerState.empty() : actions.readState();
    }
}
