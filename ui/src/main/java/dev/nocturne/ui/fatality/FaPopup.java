package dev.nocturne.ui.fatality;

/**
 * 浮层基类：下拉列表、调色板等悬浮在窗口之上的小面板。
 *
 * <p>浮层可以叠放：最上面的先接输入，点在浮层之外的点击只关掉最上面那一层（由
 * {@code FaClickGui} 协调）。位置由锚点推导（下方放不下就翻到上方）。
 */
abstract class FaPopup {

    /** 触发本浮层的控件；同一个控件重复触发时切换开关。 */
    protected final Object owner;
    /** 浮层位置与尺寸（设计单位）。 */
    protected float x;
    /** 浮层位置（设计单位）。 */
    protected float y;
    /** 浮层尺寸（设计单位）。 */
    protected float w;
    /** 浮层尺寸（设计单位）。 */
    protected float h;
    /** 出现/消失动画。 */
    private final FaMotion open = new FaMotion(0.0f);
    /** 是否已请求关闭。 */
    private boolean closing;

    protected FaPopup(Object owner) {
        this.owner = owner;
    }

    /** @return 触发本浮层的控件 */
    Object owner() {
        return owner;
    }

    /** 放在锚点下方；下方空间不足时翻到上方。 */
    void place(float anchorX, float anchorY, float anchorW, float anchorH, float minX, float minY,
               float maxX, float maxY) {
        x = Math.max(minX + 2.0f, Math.min(anchorX, maxX - w - 2.0f));
        float below = anchorY + anchorH + 3.0f;
        float above = anchorY - h - 3.0f;
        y = below + h <= maxY - 2.0f || above < minY ? below : above;
        y = Math.max(minY + 2.0f, Math.min(y, maxY - h - 2.0f));
    }

    /** 放在锚点上方（底部工具条的菜单用），并夹取到屏幕内。 */
    void placeAbove(float anchorX, float anchorY, float minX, float minY, float maxX, float maxY) {
        x = Math.max(minX + 2.0f, Math.min(anchorX, maxX - w - 2.0f));
        y = Math.max(minY + 2.0f, anchorY - h - 4.0f);
    }

    /** 放在锚点旁边（优先右侧），并夹取到屏幕内。 */
    void placeBeside(float anchorX, float anchorY, float anchorW, float anchorH, float minX, float minY,
                     float maxX, float maxY) {
        float right = anchorX + anchorW + 6.0f;
        x = right + w <= maxX - 2.0f ? right : anchorX - w - 6.0f;
        x = Math.max(minX + 2.0f, Math.min(x, maxX - w - 2.0f));
        y = Math.max(minY + 2.0f, Math.min(anchorY - 4.0f, maxY - h - 2.0f));
    }

    /** 绘制本帧（含出现/消失动画）。 */
    final void render(FaContext ctx) {
        float alpha = open.toward(closing ? 0.0f : 1.0f, ctx.dt, closing ? 26.0f : 20.0f);
        if (alpha <= 0.01f) {
            return;
        }
        layout(ctx);
        try (FaDraw.Scope ignored = ctx.draw.push()) {
            ctx.draw.translate(0.0f, (1.0f - alpha) * -4.0f);
            ctx.draw.shadow(x, y, w, h, FaTheme.FIELD_RADIUS, 8.0f, FaTheme.alpha(FaTheme.SHADOW, alpha));
            ctx.draw.rect(x, y, w, h, FaTheme.FIELD_RADIUS, FaTheme.alpha(background(), alpha));
            ctx.draw.outline(x, y, w, h, FaTheme.FIELD_RADIUS, 0.5f, FaTheme.alpha(FaTheme.POPUP_BORDER, alpha));
            draw(ctx, alpha);
        }
    }

    /** @return 背景色 */
    protected int background() {
        return FaTheme.POPUP;
    }

    /** 绘制前更新尺寸与内容；位置保持不变。 */
    protected void layout(FaContext ctx) {
    }

    /** 具体内容绘制。 */
    protected abstract void draw(FaContext ctx, float alpha);

    /** @return 指针是否在浮层内（已请求关闭的浮层不再接输入） */
    boolean contains(double mx, double my) {
        return !closing && FaContext.inside(mx, my, x, y, w, h);
    }

    /** @return 是否已完全淡出、可以从列表里移除 */
    boolean finished() {
        return closing && open.value() <= 0.01f;
    }

    /** @return 是否已请求关闭 */
    boolean closing() {
        return closing;
    }

    /** 请求关闭（带动画）。 */
    void close() {
        if (closing) {
            return;
        }
        closing = true;
        onClose();
    }

    /** 关闭时的收尾钩子。 */
    protected void onClose() {
    }

    /** 只在点击落在浮层内部时调用。 */
    boolean mouseClicked(FaContext ctx, double mx, double my, int button) {
        return true;
    }

    void mouseDragged(FaContext ctx, double mx, double my) {
    }

    void mouseReleased() {
    }

    boolean mouseScrolled(FaContext ctx, double mx, double my, double amount) {
        return contains(mx, my);
    }

    boolean keyPressed(FaContext ctx, int key, int modifiers) {
        return false;
    }

    boolean charTyped(FaContext ctx, char character) {
        return false;
    }
}
