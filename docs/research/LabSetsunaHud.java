import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.Module;
import dev.nocturne.client.module.ModuleRegistry;
import dev.nocturne.ui.skija.SetsunaHud;
import dev.nocturne.ui.skija.SkijaHudSink;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.EncodedImageFormat;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Surface;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.function.Supplier;

/**
 * 离屏验证 Setsuna HUD：品牌卡、模块发布的文本行、帧率卡、已启用模块列表。
 *
 * <p>数据链路的验证点是「模块 → HudSink → HUD」：这里用 {@link SkijaHudSink} 接住模块发布的文本行
 * （内置的 Watermark 模块就是发布者），再把已启用模块列表画到右侧。
 *
 * <p>用法：java -cp <dist.jar>;<lab> LabSetsunaHud <out.png>
 */
public class LabSetsunaHud {

    /** 再补一个发布行的模块，验证多行排布与异常行的容错。 */
    static final class LabInfoModule extends Module {

        @Override
        public String name() {
            return "LabInfo";
        }

        @Override
        public Category category() {
            return Category.MISC;
        }

        @Override
        protected void onEnable() {
            SkijaHudSink sink = LabSetsunaHud.sink;
            if (sink != null) {
                sink.add("lab.coords", new Supplier<String>() {
                    @Override
                    public String get() {
                        return "XYZ 128 64 -512";
                    }
                });
                // 故意放一个会抛异常的供给器：验证「坏行只跳过自己，不断整帧」
                sink.add("lab.broken", new Supplier<String>() {
                    @Override
                    public String get() {
                        throw new IllegalStateException("boom");
                    }
                });
            }
        }

        @Override
        protected void onDisable() {
            SkijaHudSink sink = LabSetsunaHud.sink;
            if (sink != null) {
                sink.remove("lab.coords");
                sink.remove("lab.broken");
            }
        }
    }

    /** 当前注入的汇；供测试模块的启停钩子写入（模拟真实模块的发布路径）。 */
    static SkijaHudSink sink;

    public static void main(String[] args) throws Exception {
        String out = args.length > 0 ? args[0] : "setsuna-hud.png";
        int width = 854;
        int height = 480;

        NocturneClient client = NocturneClient.boot(null);
        ModuleRegistry registry = client.modules();
        sink = new SkijaHudSink();
        client.setHudSink(sink);

        // 内置的 Watermark 模块是 HudModule：启用它即向汇发布一行
        Module watermark = registry.get("Watermark");
        if (watermark != null) {
            watermark.setEnabled(true);
        }
        registry.register(new LabInfoModule());
        Module labInfo = registry.get("LabInfo");
        if (labInfo != null) {
            labInfo.setEnabled(true);
        }
        // 再多启用两个，让右侧列表有内容
        for (String name : new String[]{"Sprint", "FullBright"}) {
            Module module = registry.get(name);
            if (module != null) {
                module.setEnabled(true);
            }
        }
        System.out.println("LAB sink 行数=" + sink.size() + "（Watermark + LabInfo 的两行，其中一行会抛异常）");

        SetsunaHud hud = new SetsunaHud(registry, sink);
        try (Surface surface = Surface.makeRasterN32Premul(width, height)) {
            Canvas canvas = surface.getCanvas();
            hud.render(canvas, width, height, 240f);
            try (Image image = surface.makeImageSnapshot();
                 Data data = image.encodeToData(EncodedImageFormat.PNG)) {
                Files.write(Paths.get(out), data.getBytes());
            }
        }
        System.out.println("LAB 已启用模块数=" + countEnabled(registry) + "；wrote " + out);
    }

    private static int countEnabled(ModuleRegistry registry) {
        int count = 0;
        for (Module module : registry.all()) {
            if (module.isEnabled()) {
                count++;
            }
        }
        return count;
    }
}
