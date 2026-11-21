package dev.nocturne.client.runtime;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.TestSupport;
import dev.nocturne.client.event.EventBus;
import dev.nocturne.client.event.RenderEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/** 上下文驱动后端不能同时从帧交换与绘制回调分发输入、绘制和 RenderEvent。 */
class NocturneRuntimeFrameSourceTest {
    private String previousDrawHook;

    @BeforeEach
    void setUp() {
        previousDrawHook = System.getProperty("nocturne.drawhook");
        System.clearProperty("nocturne.drawhook");
        TestSupport.resetClient();
        NocturneRuntime.shutdown();
        FrameDispatcher.resetForTests();
    }

    @AfterEach
    void tearDown() {
        NocturneRuntime.shutdown();
        TestSupport.resetClient();
        FrameDispatcher.resetForTests();
        if (previousDrawHook == null) {
            System.clearProperty("nocturne.drawhook");
        } else {
            System.setProperty("nocturne.drawhook", previousDrawHook);
        }
    }

    @Test
    void contextBackendDrivesOnlyInsideDrawCallback() {
        NocturneRuntime.setContextDriven(true);
        NocturneClient client = NocturneClient.boot(null);
        int[] frames = {0};
        int[] events = {0};
        FrameListener listener = () -> frames[0]++;
        NocturneRuntime.addListener(listener);
        EventBus.Subscription subscription = client.events().subscribe(RenderEvent.class,
                event -> events[0]++);
        try {
            NocturneRuntime.onFrame();
            assertEquals(0, frames[0], "无绘制上下文的帧交换点不能触发监听器");
            assertEquals(0, events[0]);
            NocturneRuntime.onDrawFrame();
            NocturneRuntime.onFrame();
            NocturneRuntime.onDrawFrame();
            assertEquals(2, frames[0], "两个绘制回调只能产生两次分发");
            assertEquals(2, events[0], "RenderEvent 不得被帧交换入口重复广播");
        } finally {
            client.events().unsubscribe(subscription);
            NocturneRuntime.removeListener(listener);
        }
    }

    @Test
    void standaloneGlBackendKeepsFrameSwapAsTheSingleDriver() {
        NocturneRuntime.setContextDriven(false);
        int[] frames = {0};
        FrameListener listener = () -> frames[0]++;
        NocturneRuntime.addListener(listener);
        try {
            NocturneRuntime.onFrame();
            NocturneRuntime.onDrawFrame();
            NocturneRuntime.onFrame();
            NocturneRuntime.onDrawFrame();
            assertEquals(2, frames[0]);
        } finally {
            NocturneRuntime.removeListener(listener);
        }
    }

    @Test
    void isolatedRuntimeCopiesShareBothSourceAndContextState() throws Exception {
        Class<?> frameRuntime = new RuntimeCopyLoader().loadClass(NocturneRuntime.class.getName());
        Class<?> drawRuntime = new RuntimeCopyLoader().loadClass(NocturneRuntime.class.getName());
        int[] frames = {0};
        Runnable listener = () -> frames[0]++;
        FrameDispatcher.add(listener);
        try {
            drawRuntime.getMethod("setContextDriven", boolean.class).invoke(null, true);
            frameRuntime.getMethod("onFrame").invoke(null);
            assertEquals(0, frames[0], "其它 Runtime 副本也必须看见上下文依赖");
            drawRuntime.getMethod("onDrawFrame").invoke(null);
            assertEquals(1, frames[0]);

            drawRuntime.getMethod("setContextDriven", boolean.class).invoke(null, false);
            frameRuntime.getMethod("onFrame").invoke(null);
            drawRuntime.getMethod("onDrawFrame").invoke(null);
            assertEquals(2, frames[0], "其它 Runtime 副本也必须看见已生效的帧交换来源");
        } finally {
            FrameDispatcher.remove(listener);
        }
    }

    @Test
    void renderEventsAreReusedPerDriverThread() throws Exception {
        NocturneClient client = NocturneClient.boot(null);
        java.util.List<RenderEvent> seen = java.util.Collections.synchronizedList(
                new java.util.ArrayList<RenderEvent>());
        EventBus.Subscription subscription = client.events().subscribe(RenderEvent.class, seen::add);
        try {
            NocturneRuntime.postRenderEvent();
            NocturneRuntime.postRenderEvent();
            AtomicReference<RenderEvent> other = new AtomicReference<RenderEvent>();
            Thread thread = new Thread(() -> {
                NocturneRuntime.postRenderEvent();
                other.set(seen.get(seen.size() - 1));
            }, "render-event-second-source");
            thread.start();
            thread.join(1000L);

            assertEquals(3, seen.size());
            assertSame(seen.get(0), seen.get(1), "同一驱动线程应复用事件，避免逐帧分配");
            assertNotSame(seen.get(0), other.get(), "另一驱动线程不得共享可变事件实例");
        } finally {
            client.events().unsubscribe(subscription);
        }
    }

    /** 只隔离 Runtime；两个副本委托到同一个中立分发器，复现 Fabric attach 的边界。 */
    private static final class RuntimeCopyLoader extends ClassLoader {
        RuntimeCopyLoader() {
            super(NocturneRuntimeFrameSourceTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.equals(NocturneRuntime.class.getName())
                    && !name.startsWith(NocturneRuntime.class.getName() + "$")) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> type = findLoadedClass(name);
                if (type == null) {
                    try (InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                        if (in == null) {
                            throw new ClassNotFoundException(name);
                        }
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        byte[] buffer = new byte[4096];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                        }
                        byte[] bytes = out.toByteArray();
                        type = defineClass(name, bytes, 0, bytes.length);
                    } catch (java.io.IOException e) {
                        throw new ClassNotFoundException(name, e);
                    }
                }
                if (resolve) {
                    resolveClass(type);
                }
                return type;
            }
        }
    }
}
