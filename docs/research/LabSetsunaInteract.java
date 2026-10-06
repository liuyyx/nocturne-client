import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.module.ModuleRegistry;
import dev.nocturne.ui.skija.ClickGuiLayout;
import dev.nocturne.ui.skija.SetsunaClickGui;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.EncodedImageFormat;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Surface;

import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 离屏验证 Setsuna 三栏界面的**交互链路**：点模块行 → 右栏出现该模块的设置。
 *
 * <p>用光栅 Surface（无需 GL 上下文），因此可在任意 JVM 上跑；界面代码本身是 class 52（Java 8）。
 *
 * <p>用法：java -cp <dist.jar>;<lab> LabSetsunaInteract <out.png>
 */
public class LabSetsunaInteract {

    public static void main(String[] args) throws Exception {
        String out = args.length > 0 ? args[0] : "setsuna-interact.png";
        int width = 854;
        int height = 480;

        NocturneClient client = NocturneClient.boot(null);
        ModuleRegistry registry = client.modules();
        SetsunaClickGui gui = new SetsunaClickGui(registry);
        gui.setOpen(true);
        gui.setViewport(width, height);
        gui.update(System.currentTimeMillis(), 0, 0);

        // 与界面内部同一套几何：中栏第一行的位置
        ClickGuiLayout layout = ClickGuiLayout.of(width, height, 1.0f);
        float rowX = layout.moduleX() + 40f;
        float rowY = layout.moduleListY() + 12f;
        boolean clicked = gui.mouseClicked(rowX, rowY, 0);
        System.out.println("LAB click 模块行 @ " + Math.round(rowX) + "," + Math.round(rowY)
                + " -> consumed=" + clicked);
        gui.update(System.currentTimeMillis(), rowX, rowY);

        // 再点该模块的启用按钮（详情栏第一行），验证开关链路
        float enableX = layout.detailX() + 40f;
        float enableY = layout.settingsY() + 42f + 10f;
        boolean toggled = gui.mouseClicked(enableX, enableY, 0);
        System.out.println("LAB click 启用按钮 @ " + Math.round(enableX) + "," + Math.round(enableY)
                + " -> consumed=" + toggled);
        gui.update(System.currentTimeMillis(), enableX, enableY);

        boolean anyEnabled = false;
        for (dev.nocturne.client.module.Module module : registry.all()) {
            anyEnabled |= module.isEnabled();
        }
        System.out.println("LAB 有模块处于启用状态 = " + anyEnabled);

        try (Surface surface = Surface.makeRasterN32Premul(width, height)) {
            Canvas canvas = surface.getCanvas();
            gui.render(null, canvas);
            try (Image image = surface.makeImageSnapshot();
                 Data data = image.encodeToData(EncodedImageFormat.PNG)) {
                Files.write(Paths.get(out), data.getBytes());
            }
        }
        System.out.println("LAB wrote " + out);
    }
}
