package dev.nocturne.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GuiDrawHook 两条不变量：①class 文件不得引用 agent/映射类（bootstrap 副本看不到它们）；
 * ②释放绘制上下文必须是"一个 null 实参"调用（旧 varargs 折叠成 0 参 → 每帧 IllegalArgumentException）。
 */
class GuiDrawHookRefactorTest {

    public static final class FakeRuntime {
        static final List<String> calls = new ArrayList<String>();

        public static void setDrawContext(Object graphics) {
            calls.add("set:" + (graphics == null ? "null" : graphics.getClass().getSimpleName()));
        }

        public static void onDrawFrame() {
            calls.add("frame");
        }
    }

    @BeforeEach
    void reset() {
        FakeRuntime.calls.clear();
        GuiDrawHook.setRuntimeTypeForTests(FakeRuntime.class);
    }

    @AfterEach
    void clearSeam() {
        GuiDrawHook.setRuntimeTypeForTests(null);
    }

    @Test
    void classFileHasNoAgentOrMappingReferences() throws Exception {
        String classBytes = readClassAsText(GuiDrawHook.class);
        assertFalse(classBytes.contains("dev/nocturne/agent/NocturneAgent"),
                "GuiDrawHook must not reference NocturneAgent (bootstrap copy cannot see it)");
        assertFalse(classBytes.contains("dev/nocturne/client/mapping/"),
                "GuiDrawHook must not reference the mapping implementation");
    }

    @Test
    void driveSetsAndReleasesTheDrawContextExactly() throws Exception {
        GuiDrawHook.onHudDraw(null);
        assertEquals(java.util.Arrays.asList("set:null", "frame", "set:null"), FakeRuntime.calls,
                "release must invoke setDrawContext with a single null argument");
    }

    @Test
    void screenEntryDrivesEveryFrame() {
        GuiDrawHook.onScreenDraw(null);
        GuiDrawHook.onScreenDraw(null);
        assertEquals(6, FakeRuntime.calls.size(), "each frame must set/frame/release: "
                + FakeRuntime.calls);
    }

    @Test
    void testSeamIsPackagePrivate() throws Exception {
        Method seam = GuiDrawHook.class.getDeclaredMethod("setRuntimeTypeForTests", Class.class);
        assertTrue(java.lang.reflect.Modifier.isStatic(seam.getModifiers()));
        assertFalse(java.lang.reflect.Modifier.isPublic(seam.getModifiers()));
    }

    private static String readClassAsText(Class<?> type) throws Exception {
        String resource = type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing class resource: " + resource);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.ISO_8859_1);
        }
    }
}
