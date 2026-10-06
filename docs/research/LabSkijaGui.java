import dev.nocturne.client.NocturneClient;
import dev.nocturne.ui.gl.GlApi;
import dev.nocturne.ui.gl.GuiOverlay;
import dev.nocturne.ui.gl.InputSource;
import dev.nocturne.ui.gl.SkijaBackend;
import dev.nocturne.ui.gl.UiBackend;

import java.nio.ByteBuffer;

import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.Pbuffer;
import org.lwjgl.opengl.PixelFormat;

/**
 * 真 GL 上下文 + Skija：把我们**真实的 ClickGUI** 画进帧缓冲并回读成 PNG。
 *
 * <p>验证的是整条新链路：Java 8 JVM → LWJGL2 离屏 GL → Skija（产物 jar 内自带的 dll 与类）
 * → SkijaBackend → GuiOverlay/ClickGui → 像素真的落在帧缓冲上。
 *
 * 用法：java -Djava.library.path=<lwjgl2 natives> -cp <dist.jar>;<lwjgl2>;<lab> LabSkijaGui <out.png>
 */
public class LabSkijaGui {

    /** 静态假输入：指针停在面板区域，无按键。 */
    static final class FakeInput implements InputSource {
        @Override public double mouseX() { return 64; }
        @Override public double mouseY() { return 64; }
        @Override public boolean mouseDown(int button) { return false; }
        @Override public double scrollDelta() { return 0; }
        @Override public boolean keyDown(int key) { return false; }
        @Override public void setPointerGrabbed(boolean grabbed) { }
        @Override public boolean isPointerGrabbed() { return false; }
        @Override public String describe() { return "lab-fake"; }
    }

    public static void main(String[] args) throws Exception {
        String out = args.length > 0 ? args[0] : "skija-gui.png";
        int width = 854;
        int height = 480;
        PixelFormat format = new PixelFormat().withDepthBits(24);
        Pbuffer pbuffer = new Pbuffer(width, height, format, null);
        pbuffer.makeCurrent();
        Display.create(format, pbuffer);
        GL11.glViewport(0, 0, width, height);
        GL11.glClearColor(0.08f, 0.09f, 0.12f, 1f);
        while (GL11.glGetError() != 0) { }
        System.out.println("LAB java=" + System.getProperty("java.version")
                + " GL=" + GL11.glGetString(GL11.GL_VERSION));

        GlApi gl = GlApi.bind(GL11.class);
        UiBackend backend = SkijaBackend.probe(gl);
        System.out.println("LAB skija backend -> " + (backend == null ? "null（不可用）" : backend.backendName()));
        if (backend == null) {
            System.out.println("LAB FAIL: Skija 后端不可用");
            return;
        }

        NocturneClient client = NocturneClient.boot(null);
        GuiOverlay overlay = new GuiOverlay(client.modules(), backend, new FakeInput(), 54);
        overlay.gui().setOpen(true);

        int frames = 0;
        for (int i = 0; i < 10; i++) {
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            overlay.onFrame();
            frames++;
            int err = GL11.glGetError();
            if (err != 0) {
                System.out.println("LAB GL error " + err + " after frame " + i);
                break;
            }
        }

        ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4);
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        int distinct = 0;
        int[] seen = new int[1 << 12];
        java.awt.image.BufferedImage image =
                new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = ((height - 1 - y) * width + x) * 4;
                int r = pixels.get(i) & 0xFF;
                int g = pixels.get(i + 1) & 0xFF;
                int b = pixels.get(i + 2) & 0xFF;
                int a = pixels.get(i + 3) & 0xFF;
                image.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
                int key = ((r >> 4) << 8) | ((g >> 4) << 4) | (b >> 4);
                if (seen[key] == 0) {
                    seen[key] = 1;
                    distinct++;
                }
            }
        }
        javax.imageio.ImageIO.write(image, "png", new java.io.File(out));
        int err = GL11.glGetError();
        System.out.println("LAB frames=" + frames + " distinctColors=" + distinct + " glGetError=" + err
                + " wrote=" + out + " viewport=" + backend.width() + "x" + backend.height());
        System.out.println("LAB " + (err == 0 && distinct > 12 ? "PASS（Skija 画出了真实 GUI）" : "FAIL"));
        Display.destroy();
    }
}
