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

    @Override
    protected final void onEnable() {
        try {
            onStart();
        } catch (RuntimeException failure) {
            unsubscribeAll();
            throw failure;
        } catch (Error failure) {
            unsubscribeAll();
            throw failure;
        }
    }

    @Override
    protected final void onDisable() {
        try {
            onStop();
        } finally {
            unsubscribeAll();
        }
    }

    private void unsubscribeAll() {
        NocturneClient client = NocturneClient.get();
        if (client != null) {
            for (EventBus.Subscription subscription : subscriptions) {
                client.events().unsubscribe(subscription);
            }
        }
        subscriptions.clear();
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
        subscriptions.add(client.events().subscribe(type, listener));
    }

    protected final <T> void listen(Class<T> type, EventBus.Priority priority, Consumer<T> listener) {
        NocturneClient client = NocturneClient.get();
        if (client == null) {
            return;
        }
        subscriptions.add(client.events().subscribe(type, priority, listener));
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
