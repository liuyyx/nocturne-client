import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.Module;
import dev.nocturne.client.module.ModuleRegistry;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;
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
 * 离屏验证设置面板的四种控件与右键重置。
 *
 * <p>顺序刻意安排：先做**不受滚动影响**的操作（数值拖动、模式循环、右键重置），最后才点开颜色
 * 调色板——展开会把行拉高并触发「自动滚动使该行可见」，之后所有行的位置都会上移，因此颜色这一项
 * 用**扫描**定位滑块（不依赖滚动量），而不再用固定坐标。
 *
 * <p>用法：java -cp <dist.jar>;<lab> LabSetsunaControls <out.png>
 */
public class LabSetsunaControls {

    /** 带齐四类设置项的测试模块：只为驱动设置面板的每种控件。 */
    static final class LabModule extends Module {

        final BooleanValue flag = add(new BooleanValue("Flag", true));
        final NumberValue amount = add(new NumberValue("Amount", 3.0, 0.0, 10.0, 0.5));
        final ModeValue mode = add(new ModeValue("Mode", "Fast", "Fast", "Slow", "Smart"));
        final ColorValue tint = add(new ColorValue("Tint", 0xFF3ED6B4));

        @Override
        public String name() {
            return "LabControls";
        }

        @Override
        public Category category() {
            return Category.MISC;
        }
    }

    public static void main(String[] args) throws Exception {
        String out = args.length > 0 ? args[0] : "setsuna-controls.png";
        int width = 854;
        int height = 480;

        NocturneClient client = NocturneClient.boot(null);
        ModuleRegistry registry = client.modules();
        LabModule lab = new LabModule();
        registry.register(lab);

        SetsunaClickGui gui = new SetsunaClickGui(registry);
        gui.setOpen(true);
        gui.setViewport(width, height);
        gui.update(System.currentTimeMillis(), 0, 0);

        ClickGuiLayout layout = ClickGuiLayout.of(width, height, 1.0f);

        // 1) 选 MISC（第 4 个分类；rail 项间距 = 30 + 6）
        float railX = layout.x() + layout.railWidth() * 0.5f;
        float railY = layout.y() + layout.categoryTop() + 3f * 36f + 15f;
        System.out.println("LAB 选 MISC -> " + gui.mouseClicked(railX, railY, 0));
        gui.update(System.currentTimeMillis(), railX, railY);

        // 2) 选中列表第一行（LabControls）
        float rowX = layout.moduleX() + 40f;
        float rowY = layout.moduleListY() + 12f;
        System.out.println("LAB 选模块 -> " + gui.mouseClicked(rowX, rowY, 0));
        gui.update(System.currentTimeMillis(), rowX, rowY);

        float innerX = layout.detailX() + 12f;
        float innerWidth = layout.detailWidth() - 24f;
        // 首个设置项的 y：模块名(22) + 分类(20) + 启用按钮(24+6)
        float firstRowY = layout.settingsY() + 72f;

        // 3) 数值轨道拖动：Amount 是第 2 项
        float amountY = firstRowY + 34f + 10f;
        System.out.println("LAB 拖 Amount -> "
                + gui.mouseClicked(innerX + innerWidth * 0.8f, amountY, 0));
        gui.mouseDragged(innerX + innerWidth * 0.8f, amountY, 0, 0, 0);
        gui.mouseReleased(innerX + innerWidth * 0.8f, amountY, 0);
        System.out.println("LAB amount=" + lab.amount.get() + "（期望 8.0）");

        // 4) 模式循环：Mode 是第 3 项
        float modeY = firstRowY + 68f + 8f;
        System.out.println("LAB 点 Mode -> " + gui.mouseClicked(innerX + innerWidth * 0.5f, modeY, 0));
        System.out.println("LAB mode=" + lab.mode.get() + "（期望 Fast -> Slow）");

        // 5) 右键恢复默认：仍在未滚动状态，位置与步骤 3 相同
        gui.mouseClicked(innerX + 20f, amountY, 1);
        System.out.println("LAB 右键重置 amount=" + lab.amount.get() + "（期望回到默认 3.0）");

        // 6) 颜色：Tint 是第 4 项 → 点色块展开（会触发自动滚动），再扫描 H 滑块
        float tintY = firstRowY + 102f;
        int beforeColor = lab.tint.argb();
        System.out.println("LAB 点色块 -> " + gui.mouseClicked(innerX + innerWidth - 17f, tintY, 0)
                + "（期望展开并自动滚动）");
        gui.update(System.currentTimeMillis(), innerX, tintY);

        boolean colorChanged = false;
        float hitY = 0f;
        for (float y = layout.settingsY() + 20f; y <= layout.bodyY() + layout.bodyHeight(); y += 3f) {
            int before = lab.tint.argb();
            gui.mouseClicked(innerX + 60f, y, 0);
            gui.mouseDragged(innerX + 60f, y, 0, 0, 0);
            gui.mouseReleased(innerX + 60f, y, 0);
            if (lab.tint.argb() != before) {
                colorChanged = true;
                hitY = y;
                break;
            }
        }
        System.out.println("LAB 颜色滑块 " + (colorChanged
                ? "✓ 命中于 y=" + Math.round(hitY) + "（相对 settingsY +" + Math.round(hitY - layout.settingsY())
                + "），tint " + Integer.toHexString(beforeColor) + " -> " + Integer.toHexString(lab.tint.argb())
                : "✗ 未命中（扫描 " + Math.round(layout.settingsY() + 20f) + ".."
                + Math.round(layout.bodyY() + layout.bodyHeight()) + "）"));

        // 收尾：把指针停在面板内，便于截图
        gui.update(System.currentTimeMillis(), innerX, tintY);

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
