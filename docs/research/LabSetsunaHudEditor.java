import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.module.Module;
import dev.nocturne.client.module.ModuleRegistry;
import dev.nocturne.ui.skija.HudLayout;
import dev.nocturne.ui.skija.SetsunaHud;
import dev.nocturne.ui.skija.SetsunaHudEditor;
import dev.nocturne.ui.skija.SkijaHudSink;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.EncodedImageFormat;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Surface;

import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 离屏验证 HUD 编辑器：拖动元素改写 {@link HudLayout}、重置恢复默认、完成按钮触发回调。
 *
 * <p>编辑器与 HUD 共用同一份位置表，因此"拖完立刻生效"这件事可以直接用位置读数断言，
 * 不必依赖像素比对。
 *
 * <p>用法：java -cp <dist.jar>;<lab> LabSetsunaHudEditor <out.png>
 */
public class LabSetsunaHudEditor {

    public static void main(String[] args) throws Exception {
        String out = args.length > 0 ? args[0] : "setsuna-hud-editor.png";
        int width = 854;
        int height = 480;

        NocturneClient client = NocturneClient.boot(null);
        ModuleRegistry registry = client.modules();
        SkijaHudSink sink = new SkijaHudSink();
        client.setHudSink(sink);
        Module watermark = registry.get("Watermark");
        if (watermark != null) {
            watermark.setEnabled(true);
        }
        for (String name : new String[]{"Sprint", "FullBright"}) {
            Module module = registry.get(name);
            if (module != null) {
                module.setEnabled(true);
            }
        }

        SetsunaHud hud = new SetsunaHud(registry, sink, null);
        SetsunaHudEditor editor = new SetsunaHudEditor(hud);
        final boolean[] closed = {false};
        editor.setOnClose(new Runnable() {
            @Override
            public void run() {
                closed[0] = true;
            }
        });
        editor.setOpen(true);
        editor.setViewport(width, height);
        editor.setFps(240f);

        HudLayout layout = hud.layout();
        // 1) 首次取边界：此时懒初始化默认位置（品牌卡在左上角边距处）
        float[] brand = hud.bounds(SetsunaHud.ID_BRAND, width, height, 240f);
        System.out.println("LAB 品牌默认位置 = " + Math.round(brand[0]) + "," + Math.round(brand[1])
                + " 尺寸 " + Math.round(brand[2]) + "x" + Math.round(brand[3]));

        // 2) 按住品牌卡中心并拖到 (140, 260)
        editor.mouseClicked(brand[0] + 5f, brand[1] + 5f, 0);
        System.out.println("LAB 命中元素 = " + editor.draggingId());
        editor.mouseDragged(145f, 265f, 0, 100d, 200d);
        editor.mouseReleased(145f, 265f, 0);
        System.out.println("LAB 拖动后品牌位置 = " + Math.round(layout.x(SetsunaHud.ID_BRAND)) + ","
                + Math.round(layout.y(SetsunaHud.ID_BRAND)) + "（期望 140,260）");

        // 3) 帧率卡也挪一下（右侧那块），便于截图对比
        float[] fpsBox = hud.bounds(SetsunaHud.ID_FPS, width, height, 240f);
        editor.mouseClicked(fpsBox[0] + 5f, fpsBox[1] + 5f, 0);
        editor.mouseDragged(fpsBox[0] + 5f, 300f, 0, 0d, 280d);
        editor.mouseReleased(fpsBox[0] + 5f, 300f, 0);
        System.out.println("LAB 帧率卡新位置 = " + Math.round(layout.x(SetsunaHud.ID_FPS)) + ","
                + Math.round(layout.y(SetsunaHud.ID_FPS)));

        // 4) 先渲染一张（拖动后的编辑器态）
        try (Surface surface = Surface.makeRasterN32Premul(width, height)) {
            Canvas canvas = surface.getCanvas();
            editor.render(null, canvas);
            try (Image image = surface.makeImageSnapshot();
                 Data data = image.encodeToData(EncodedImageFormat.PNG)) {
                Files.write(Paths.get(out), data.getBytes());
            }
        }
        System.out.println("LAB wrote " + out);

        // 5) 重置位置：应当清空位置表，下次取边界时重新懒初始化回默认
        editor.mouseClicked(width - 12f - 84f - 6f - 42f, height - 12f - 12f, 0);
        System.out.println("LAB 重置后 layout.has(brand)=" + layout.has(SetsunaHud.ID_BRAND)
                + "（期望 false）");
        float[] brandAgain = hud.bounds(SetsunaHud.ID_BRAND, width, height, 240f);
        System.out.println("LAB 重置后品牌位置 = " + Math.round(brandAgain[0]) + ","
                + Math.round(brandAgain[1]) + "（期望回到默认边距）");

        // 6) 完成按钮：应当关闭编辑器并触发回调
        editor.mouseClicked(width - 12f - 42f, height - 12f - 12f, 0);
        System.out.println("LAB 完成后 editor.isOpen=" + editor.isOpen()
                + " 回调触发=" + closed[0] + "（期望 false/true）");
    }
}
