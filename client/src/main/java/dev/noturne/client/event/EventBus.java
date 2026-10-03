package dev.noturne.client.event;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Minimal type-keyed publish/subscribe bus.
 *
 * <p>Events are plain objects; a subscriber receives every event whose runtime class matches the
 * class it subscribed to. Dispatch is synchronous and in subscription order, which keeps game-tick
 * ordering deterministic.
 */
public final class EventBus {

    private final Map<Class<?>, List<Consumer<?>>> listeners =
            new ConcurrentHashMap<Class<?>, List<Consumer<?>>>();

    public <T> void subscribe(Class<T> type, Consumer<T> listener) {
        List<Consumer<?>> list = listeners.get(type);
        if (list == null) {
            List<Consumer<?>> created = new CopyOnWriteArrayList<Consumer<?>>();
            List<Consumer<?>> existing = listeners.putIfAbsent(type, created);
            list = existing != null ? existing : created;
        }
        list.add(listener);
    }

    public <T> void unsubscribe(Class<T> type, Consumer<T> listener) {
        List<Consumer<?>> list = listeners.get(type);
        if (list != null) {
            list.remove(listener);
        }
    }

    /** Delivers {@code event} to every matching subscriber and returns it for chaining. */
    @SuppressWarnings("unchecked")
    public <T> T post(T event) {
        List<Consumer<?>> list = listeners.get(event.getClass());
        if (list != null) {
            for (Consumer<?> listener : list) {
                ((Consumer<T>) listener).accept(event);
            }
        }
        return event;
    }
}
