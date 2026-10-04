import dev.noturne.ui.skija.SkijaHudPrimitives;
import dev.noturne.ui.skija.SkijaTextureBridge;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.EncodedImageFormat;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.types.Rect;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Paths;

import javax.imageio.ImageIO;

/**
 * 离屏验证纹理桥的「帧快照 + 背景模糊」（无需 GL 上下文，用光栅表面）。
 *
 * <p>验证方式：先画黑白棋盘（模拟游戏画面）→ 抓快照 → 同一画布上分两块画：左边原样贴快照
 * （应当仍是纯黑白），右边走 {@link SkijaHudPrimitives#blur}（模糊后必然出现**中间灰**）。
 * 判定用 JDK 的 {@code ImageIO} 读回自己写出的 PNG 来数像素——不依赖 Skija 的像素回读 API，
 * 少一个可能踩空的接口。
 *
 * <p>用法：java -cp <dist.jar>;<lab> LabTextureBlur <out.png>
 */
public class LabTextureBlur {

    public static void main(String[] args) throws Exception {
        String out = args.length > 0 ? args[0] : "setsuna-blur.png";
        int width = 480;
        int height = 220;

        try (Surface surface = Surface.makeRasterN32Premul(width, height)) {
            Canvas canvas = surface.getCanvas();
            // 1) 黑白棋盘：模拟游戏画面（模糊后应当变成灰）
            Paint paint = new Paint();
            canvas.clear(0xFF101418);
            for (int y = 0; y < height; y += 16) {
                for (int x = 0; x < width; x += 16) {
                    boolean white = ((x / 16) + (y / 16)) % 2 == 0;
                    paint.setColor(white ? 0xFFF0F2F5 : 0xFF1A1E22);
                    canvas.drawRect(Rect.makeXYWH(x, y, 16, 16), paint);
                }
            }
            // 2) 抓快照：必须在画 UI 之前（否则会把 UI 自己模糊进去）
            Image backdrop = SkijaTextureBridge.snapshot(surface);
            System.out.println("LAB snapshot = " + (backdrop == null ? "null（失败）" : "ok"));
            if (backdrop == null) {
                return;
            }
            // 3) 左侧：原样贴一块（对照组）；右侧：同一片棋盘走模糊
            //    注意 SamplingMode 与 Paint 都不能传 null——Skija 的 JNI 绑定直接解引用它们
            canvas.drawImageRect(backdrop, Rect.makeLTRB(260, 120, 440, 200),
                    Rect.makeLTRB(20, 120, 200, 200),
                    io.github.humbleui.skija.SamplingMode.LINEAR, new Paint(), false);
            SkijaHudPrimitives.blur(canvas, 260, 120, 180, 80, 8f, 12f, backdrop);
            backdrop.close();

            try (Image shot = surface.makeImageSnapshot();
                 Data data = shot.encodeToData(EncodedImageFormat.PNG)) {
                Files.write(Paths.get(out), data.getBytes());
            }
        }

        // 4) 读回 PNG 计数：对照组应是纯黑白极端值，模糊组必然出现中间灰
        BufferedImage image = ImageIO.read(Paths.get(out).toFile());
        int sharpExtremes = 0;
        int blurryMidtones = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                int value = (rgb >> 16) & 0xFF;
                if (x >= 20 && x < 200 && y >= 120 && y < 200 && (value < 40 || value > 200)) {
                    sharpExtremes++;
                }
                if (x >= 260 && x < 440 && y >= 120 && y < 200 && value > 40 && value < 200) {
                    blurryMidtones++;
                }
            }
        }
        System.out.println("LAB 对照区极端像素=" + sharpExtremes + "（棋盘原样，应远大于 0）");
        System.out.println("LAB 模糊区中间灰像素=" + blurryMidtones
                + (blurryMidtones > 500 ? "  ✓ 模糊生效" : "  ✗ 模糊未生效"));
        System.out.println("LAB wrote " + out);
    }
}
