package dev.noturne.client.module.modules;

import dev.noturne.client.module.Category;
import dev.noturne.client.module.HudModule;

import java.util.function.Supplier;

/** 在 HUD 角落绘制客户端名称；属 {@link HudModule}，启用即注册、禁用即注销。 */
public final class WatermarkModule extends HudModule {

    /** 要显示的固定文本，在构造后不再变化。 */
    private final String text;

    /** 使用默认文本“noturne”。 */
    public WatermarkModule() {
        this("noturne");
    }

    /**
     * 使用指定文本。
     *
     * @param text HUD 上显示的字符串；{@code null} 或空串表示本模块不绘制任何内容
     */
    public WatermarkModule(String text) {
        this.text = text;
    }

    /** 模块名，注册表内唯一，也是 GUI 中的显示名。 */
    @Override
    public String name() {
        return "Watermark";
    }

    /** 归入“渲染”分组。 */
    @Override
    public Category category() {
        return Category.RENDER;
    }

    /** HUD 行 id，必须在各 HUD 模块间唯一（此处固定为 watermark）。 */
    @Override
    protected String hudId() {
        return "watermark";
    }

    /**
     * 返回每帧被 HUD 拉取的文本供应者。
     *
     * <p>使用匿名类而非 lambda，是为了与项目统一的 Java 源级别保持兼容。
     */
    @Override
    protected Supplier<String> hudText() {
        return new Supplier<String>() {
            @Override
            // 文本是构造期常量，每次拉取直接返回即可，无需再缓存。
            public String get() {
                return text;
            }
        };
    }
}
