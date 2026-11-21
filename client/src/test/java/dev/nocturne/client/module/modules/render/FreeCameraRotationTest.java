package dev.nocturne.client.module.modules.render;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 自由相机角度差必须走最短路径，跨 ±180° 不得突然旋转一整圈。 */
class FreeCameraRotationTest {
    @Test
    void angleDeltaWrapsAcrossDegreesBoundary() throws Exception {
        Method method = FreeCameraModule.class.getDeclaredMethod("wrapDegrees", float.class);
        method.setAccessible(true);
        assertEquals(2.0f, (Float) method.invoke(null, -358.0f), 0.0001f);
        assertEquals(-2.0f, (Float) method.invoke(null, 358.0f), 0.0001f);
        assertEquals(10.0f, (Float) method.invoke(null, 10.0f), 0.0001f);
    }
}
