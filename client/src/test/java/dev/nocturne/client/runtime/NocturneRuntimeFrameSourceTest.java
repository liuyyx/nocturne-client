package dev.nocturne.client.runtime;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.TestSupport;
import dev.nocturne.client.event.EventBus;
import dev.nocturne.client.event.RenderEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 上下文驱动后端不能同时从帧交换与绘制回调分发输入、绘制和 RenderEvent。 */
class NocturneRuntimeFrameSourceTest {
    private String previousDrawHook;
    private boolean previousFrameSeen;
    private boolean previousContextDriven;

    @BeforeEach
    void setUp() throws Exception {
        previousDrawHook = System.getProperty("nocturne.drawhook");
        System.clearProperty("nocturne.drawhook");
        previousFrameSeen = field("frameHookSeen").getBoolean(null);
        previousContextDriven = field("contextDriven").getBoolean(null);
        TestSupport.resetClient();
        NocturneRuntime.shutdown();
        FrameDispatcher.resetForTests();
        field("frameHookSeen").setBoolean(null, false);
    }

    @AfterEach
    void tearDown() throws Exception {
        NocturneRuntime.shutdown();
        TestSupport.resetClient();
        FrameDispatcher.resetForTests();
        field("frameHookSeen").setBoolean(null, previousFrameSeen);
        NocturneRuntime.setContextDriven(previousContextDriven);
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

    private static Field field(String name) throws Exception {
        Field field = NocturneRuntime.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
