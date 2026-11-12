package dev.nocturne.client.bootstrap;

import dev.nocturne.bootstrap.GameplaySink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 相机头尾配对必须支持嵌套、null 占位与异常后继续消费。 */
class GameplayHookCameraTest {
    @BeforeEach
    @AfterEach
    void reset() {
        GameplayHook.install(null);
        GameplaySink.resetForTests();
        for (int i = 0; i < 4; i++) {
            GameplayHook.cameraEnd();
        }
        GameplayHook.resetFailureLogsForTests();
    }

    @Test
    void nestedCallbacksConsumeTheCapturedCameraInReverseOrder() {
        final List<Object> received = new ArrayList<Object>();
        GameplayHook.install(new GameplayHook.Sink() {
            @Override
            public void cameraAligned(Object camera) {
                received.add(camera);
            }
        });
        Object outer = new Object();
        Object inner = new Object();

        GameplayHook.cameraBegin(outer);
        GameplayHook.cameraBegin(null);
        GameplayHook.cameraBegin(inner);
        GameplayHook.cameraEnd();
        GameplayHook.cameraEnd();
        GameplayHook.cameraEnd();
        GameplayHook.cameraEnd();

        assertEquals(Arrays.asList(inner, outer), received);
    }

    @Test
    void failingCallbackDoesNotLeaveItsCameraForTheNextReturn() {
        final Object outer = new Object();
        final Object inner = new Object();
        final List<Object> received = new ArrayList<Object>();
        GameplayHook.install(new GameplayHook.Sink() {
            @Override
            public void cameraAligned(Object camera) {
                if (camera == inner) {
                    throw new IllegalStateException("camera callback failure");
                }
                received.add(camera);
            }
        });
        GameplayHook.cameraBegin(outer);
        GameplayHook.cameraBegin(inner);
        GameplayHook.cameraEnd();
        GameplayHook.cameraEnd();
        GameplayHook.cameraEnd();

        assertEquals(Arrays.asList(outer), received);
    }

    @Test
    void panicDiscardsCapturedCamerasAndPreventsFurtherCallbacks() {
        final List<Object> received = new ArrayList<Object>();
        GameplayHook.install(new GameplayHook.Sink() {
            @Override
            public void cameraAligned(Object camera) {
                received.add(camera);
            }
        });
        GameplayHook.cameraBegin(new Object());
        GameplayHook.kill();
        GameplayHook.cameraEnd();
        GameplayHook.cameraBegin(new Object());
        GameplayHook.cameraEnd();

        assertEquals(0, received.size());
    }
}
