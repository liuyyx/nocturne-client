import dev.nocturne.ui.skija.SkijaTextureBridge;

import io.github.humbleui.skija.BackendRenderTarget;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorSpace;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.DirectContext;
import io.github.humbleui.skija.FramebufferFormat;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.skija.SurfaceOrigin;
import io.github.humbleui.types.Rect;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.Pbuffer;
import org.lwjgl.opengl.PixelFormat;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

import javax.imageio.ImageIO;

/**
 * 真 GL 上下文下验证纹理借用：上传一张 4×4 纹理（左半红、右半蓝），由
 * {@link SkijaTextureBridge#borrowGlTexture} 借给 Skia，放大画进帧缓冲后读回像素判定。
 *
 * <p>这是「HUD/GUI 显示游戏纹理（皮肤、物品图标）」那条链路的底层证明：Skia 确实能直接引用
 * 一块已上传的 GL 纹理，而不需要把像素拷回来。
 *
 * <p>用法：java -Djava.library.path=<lwjgl2 natives> -cp <dist.jar>;<lwjgl2>;<lab> LabTextureBorrow <out.png>
 */
public class LabTextureBorrow {

    public static void main(String[] args) throws Exception {
        String out = args.length > 0 ? args[0] : "setsuna-borrow.png";
        int width = 320;
        int height = 160;

        PixelFormat format = new PixelFormat().withDepthBits(24);
        Pbuffer pbuffer = new Pbuffer(width, height, format, null);
        pbuffer.makeCurrent();
        Display.create(format, pbuffer);
        GL11.glViewport(0, 0, width, height);
        GL11.glClearColor(0.06f, 0.08f, 0.10f, 1f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        while (GL11.glGetError() != 0) {
            // 清掉创建上下文时的历史错误
        }
        System.out.println("LAB java=" + System.getProperty("java.version")
                + " GL=" + GL11.glGetString(GL11.GL_VERSION));

        // 1) 上传 4×4 纹理：左半红、右半蓝
        int textureId = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        ByteBuffer pixels = BufferUtils.createByteBuffer(4 * 4 * 4);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                boolean left = x < 2;
                pixels.put((byte) (left ? 255 : 0)).put((byte) 0).put((byte) (left ? 0 : 255))
                        .put((byte) 255);
            }
        }
        pixels.flip();
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 4, 4, 0, GL11.GL_RGBA,
                GL11.GL_UNSIGNED_BYTE, pixels);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        // 2) Skija 上下文 + 画进帧缓冲，借用上面那张纹理
        DirectContext context = DirectContext.makeGL();
        BackendRenderTarget target = BackendRenderTarget.makeGL(width, height, 0, 8, 0,
                FramebufferFormat.GR_GL_RGBA8);
        Surface surface = Surface.makeFromBackendRenderTarget(context, target,
                SurfaceOrigin.BOTTOM_LEFT, ColorType.RGBA_8888, ColorSpace.getSRGB(), null);
        if (surface == null) {
            System.out.println("LAB ✗ 无法建立 Skija 表面");
            return;
        }
        Canvas canvas = surface.getCanvas();
        canvas.clear(0xFF101418);

        SkijaTextureBridge.Borrowed borrowed =
                SkijaTextureBridge.borrowGlTexture(context, textureId, 4, 4);
        System.out.println("LAB borrowGlTexture = " + (borrowed == null ? "null（失败）" : "ok"));
        if (borrowed != null) {
            canvas.drawImageRect(borrowed.image(), Rect.makeLTRB(0, 0, 4, 4),
                    Rect.makeLTRB(40, 40, 280, 120), SamplingMode.LINEAR, new Paint(), false);
            borrowed.close();
        }
        context.flushAndSubmit(false);

        // 3) 读回帧缓冲并判定：目标区域内应当同时出现明显的红与蓝
        ByteBuffer readback = BufferUtils.createByteBuffer(width * height * 4);
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, readback);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int red = 0;
        int blue = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = ((height - 1 - y) * width + x) * 4;
                int r = readback.get(index) & 0xFF;
                int g = readback.get(index + 1) & 0xFF;
                int b = readback.get(index + 2) & 0xFF;
                image.setRGB(x, y, (r << 16) | (g << 8) | b);
                if (x >= 40 && x < 280 && y >= 40 && y < 120) {
                    if (r > 200 && b < 60) {
                        red++;
                    }
                    if (b > 200 && r < 60) {
                        blue++;
                    }
                }
            }
        }
        ImageIO.write(image, "png", new java.io.File(out));
        surface.close();
        context.close();
        System.out.println("LAB 借用区域：红像素=" + red + " 蓝像素=" + blue
                + ((red > 1000 && blue > 1000) ? "  ✓ 纹理借用生效" : "  ✗ 未拿到纹理内容"));
        System.out.println("LAB wrote " + out);
    }
}
