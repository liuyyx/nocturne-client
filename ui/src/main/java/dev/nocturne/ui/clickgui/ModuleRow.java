package dev.nocturne.ui.clickgui;

import dev.nocturne.client.module.Module;
import dev.nocturne.ui.anim.Animation;
import dev.nocturne.ui.component.Component;
import dev.nocturne.ui.render.Color;
import dev.nocturne.ui.render.Renderer;
import dev.nocturne.ui.theme.Theme;

/**
 * 一个可点击的模块行：启用态用 PRIMARY_CONTAINER 系底色 + ON_PRIMARY_CONTAINER 文本表达，
 * 禁用态融入面板表面色，悬停时底色与文本同步提亮（状态层式过渡）。
 *
 * <p>左键点击切换模块开关；右键由 {@link ClickGui} 拦截，用于唤出设置面板。
 */
public final class ModuleRow extends Component {

    /** 本行对应的模块。 */
    private final Module module;
    /** 悬停高亮动画，0 表示未悬停、1 表示完全高亮。 */
    private final Animation highlight =
            new Animation(Theme.HOVER_MS, Animation.Easing.EASE_OUT_CUBIC, 0f);

    /** @param module 本行代表的模块 */
    public ModuleRow(Module module) {
        this.module = module;
    }

    /** @return 本行代表的模块 */
    public Module module() {
        return module;
    }

    /** 推进高亮动画；每帧调用一次。 */
    @Override
    public void update(long nowMs) {
        highlight.animateTo(hovered ? 1f : 0f, nowMs);
        highlight.update(nowMs);
    }

    @Override
    public void cancelInteractions() {
        super.cancelInteractions();
        // P19：hovered 已由基类清零，但动画值还在 1 的路上；直接 snap 到 0，
        // 否则重开首帧仍是高亮态（一帧残影）。
        highlight.set(0f);
    }

    @Override
    public void render(Renderer renderer) {
        if (!visible) {
            return;
        }
        float hover = highlight.value();

        // 行底色：启用 = PRIMARY_CONTAINER（悬停时向 PRIMARY 偏移 15%）；禁用 = SURFACE_CONTAINER → HIGH
        // （对应 Epsilon DropdownTheme.moduleEnabled / moduleDisabled）
        Color background;
        if (module.isEnabled()) {
            background = Theme.PRIMARY_CONTAINER.mix(
                    Theme.PRIMARY_CONTAINER.mix(Theme.PRIMARY, 0.15f), hover);
        } else {
            background = Theme.SURFACE_CONTAINER.mix(Theme.SURFACE_CONTAINER_HIGH, hover);
        }
        // 直角矩形、左右各内缩 2px：对应 Epsilon scope.rect(2, 0, width - 4, MODULE_HEIGHT, bg)
        renderer.rect(x + 2f, y, width - 4f, height, background);
        // 底部 0.5px 分隔线、左右各内缩 3px：对应 Epsilon moduleDivider
        renderer.rect(x + 3f, y + height - 0.5f, width - 6f, 0.5f,
                Theme.OUTLINE.withAlpha(Theme.MODULE_DIVIDER_ALPHA));

        // 启用文本用 ON_PRIMARY_CONTAINER；禁用文本随悬停由次要文本提亮到主文本
        Color textColor = module.isEnabled()
                ? Theme.ON_PRIMARY_CONTAINER
                : Theme.TEXT_SECONDARY.mix(Theme.TEXT_PRIMARY, hover);
        float textY = y + (height - renderer.textHeight(Theme.MODULE_TEXT_SIZE)) / 2f;
        renderer.text(module.name(), x + Theme.MODULE_PADDING_X, textY, Theme.MODULE_TEXT_SIZE,
                textColor);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0 || !contains(mx, my)) {
            return false;
        }
        module.toggle();
        return true;
    }
}
