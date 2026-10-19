package dev.nocturne.ui.fatality;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.Module;
import dev.nocturne.client.module.ModuleRegistry;
import dev.nocturne.ui.gl.OverlayGui;
import dev.nocturne.ui.gl.UiBackend;
import dev.nocturne.ui.render.Renderer;
import dev.nocturne.ui.text.FontRasterizer;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * "FA" ClickGUI：fatality.win 风格的炭黑扁平窗口。分类是顶部 Tab，侧边栏列出当前分类的模块，
 * 每个模块是瀑布流里的一张卡片，底部工具条放搜索与信息。
 *
 * <p>所有尺寸都是设计单位，整套界面在一个缩放因子下绘制，因此在任何 GUI 缩放下比例一致。
 *
 * <p>生命周期由 {@link OverlayGui} 决定（不是 vanilla {@code Screen}）：打开/关闭、输入派发、
 * 每帧绘制都由 {@code GuiOverlay} 驱动，绘制只面向后端的 {@link Renderer} 抽象，
 * 因此在固定管线、核心 profile、26.x 的 extractor 三条路径上是同一份代码。
 */
public final class FaClickGui implements OverlayGui {

    /** 分类 Tab 高。 */
    private static final float TAB_H = 20.5f;
    /** Tab 左右内边距。 */
    private static final float TAB_PAD = 7.5f;
    /** Tab 图标尺寸。 */
    private static final float TAB_ICON = 10.0f;
    /** 图标与文字之间的间距。 */
    private static final float TAB_GAP = 4.0f;
    /** 搜索框宽。 */
    private static final float SEARCH_W = 150.0f;
    /** 侧边栏每一项的行高。 */
    private static final float SIDE_ITEM_H = 19.0f;
    /** 右下角缩放把手的边长。 */
    private static final float GRIP = 9.0f;
    /** 头部向下、底部向上的柔光过渡带高度。 */
    private static final float SHADE = 6.0f;
    /** 提示条停留时长（毫秒）。 */
    private static final long TOAST_MS = 2200L;

    /** 每帧交互与绘制上下文。 */
    private final FaContext ctx = new FaContext(this);
    /** 绘制面（本界面的唯一绘制出口）。 */
    private final BackendFaDraw draw = new BackendFaDraw();
    /** Render 分类的预览卡片。 */
    private final FaPreviewCard preview = new FaPreviewCard();
    /** 模块注册表。 */
    private final ModuleRegistry registry;
    /** 按分类分组的卡片。 */
    private final Map<Category, List<FaGroup>> groups = new EnumMap<Category, List<FaGroup>>(Category.class);
    /** 每个分类独立的滚动/选中状态。 */
    private final Map<Category, View> views = new EnumMap<Category, View>(Category.class);
    /** 搜索结果使用的独立视图。 */
    private final View searchView = new View();
    /** 当前有模块的分类（空分类不出现在 Tab 上）。 */
    private List<Category> categories = Collections.emptyList();
    /** 当前分类。 */
    private Category category = Category.RENDER;
    /** 上次同步模块列表时的注册表版本号。 */
    private long moduleRevision = -1L;

    /** 设计单位 → 设备逻辑像素。 */
    private float scale = 1.0f;
    /** 绘制区在设计单位下的宽高。 */
    private float sw;
    /** 绘制区在设计单位下的高。 */
    private float sh;
    /** 窗口位置与尺寸（设计单位）。 */
    private float wx;
    private float wy;
    private float ww;
    private float wh;
    /** 是否已经排过版（首帧或恢复布局后）。 */
    private boolean placed;
    /** 是否已打开。 */
    private boolean open;
    /** 鼠标左键是否按住（按住期间不重算缩放，否则正在拖的滑条会跳）。 */
    private boolean mouseDown;
    /** 最近一次 setViewport 收到的设备逻辑宽。 */
    private int viewportWidth;
    /** 最近一次 setViewport 收到的设备逻辑高。 */
    private int viewportHeight;
    /** 逻辑像素 → 物理像素（用于字形栅格密度）。 */
    private int backendScale = 1;
    /** 字体诊断只打印一次（-Dnocturne.ui.debug）。 */
    private boolean fontDiagLogged;
    /** 当前绘制后端名（诊断展示）。 */
    private String backendName = "?";

    /** 打开动画。 */
    private final FaMotion openMotion = new FaMotion(0.0f);
    /** 活动 Tab 指示条位置动画。 */
    private final FaMotion tabX = new FaMotion(Float.NaN);
    /** 活动 Tab 指示条宽度动画。 */
    private final FaMotion tabW = new FaMotion(0.0f);
    /** 内容区淡入动画。 */
    private final FaMotion contentFade = new FaMotion(1.0f);
    /** 搜索框展开动画。 */
    private final FaMotion searchOpen = new FaMotion(0.0f);
    /** 提示条淡出动画。 */
    private final FaMotion toastAlpha = new FaMotion(0.0f);
    /** 滚动条显隐动画。 */
    private final FaMotion scrollbar = new FaMotion(0.0f);
    /** 底部图标悬停动画（搜索、信息）。 */
    private final FaMotion[] footerHover = {new FaMotion(0.0f), new FaMotion(0.0f)};
    /** 分类 Tab 悬停动画。 */
    private final Map<Category, FaMotion> tabHover = new EnumMap<Category, FaMotion>(Category.class);

    /** 浮层栈（最上面的先接输入）。 */
    private final List<FaPopup> popups = new ArrayList<FaPopup>();
    /** 刚被点击的浮层所有者；用于避免"点自己把浮层又开一遍"。 */
    private Object suppressedOwner;
    /** 键盘焦点。 */
    private FaKeySink focus;
    /** 搜索输入缓冲。 */
    private final FaTextInput search = new FaTextInput(48);
    /** 搜索框是否展开。 */
    private boolean searchActive;
    /** 当前搜索词（已小写）。 */
    private String query = "";
    /** 提示文本。 */
    private String toast = "";
    /** 提示出现的时间戳（毫秒）。 */
    private long toastAt;

    /** 拖拽模式：0 无、1 移动窗口、2 调整窗口大小。 */
    private int drag;
    private float dragOffsetX;
    private float dragOffsetY;
    private float startW;
    private float startH;
    private float startX;
    private float startY;
    /** 上一帧的时间戳（纳秒）；0 表示尚未渲染过。 */
    private long lastNanos;
    /** 布局键；变化时卡片位置立即吸附，不播动画。 */
    private String layoutKey = "";

    /** 搜索框的键盘适配。 */
    private final FaKeySink searchSink = new FaKeySink() {
        @Override
        public boolean keyPressed(int key, int modifiers) {
            return search.keyPressed(key, modifiers);
        }

        @Override
        public boolean charTyped(char character) {
            return search.charTyped(character);
        }
    };

    /** 单个分类的滚动与选中状态。 */
    private static final class View {
        /** 内容滚动量（设计单位）。 */
        float scroll;
        /** 滚动动画。 */
        final FaMotion scrollMotion = new FaMotion(0.0f);
        /** 侧边栏高亮条位置动画。 */
        final FaMotion barY = new FaMotion(Float.NaN);
        /** 侧边栏滚动量。 */
        float sideScroll;
        /** 侧边栏滚动动画。 */
        final FaMotion sideScrollMotion = new FaMotion(0.0f);
        /** 被点击选中的模块。 */
        Module selected;
        /** 搜索结果的卡片列表。 */
        List<FaGroup> shown = Collections.emptyList();
        /** 最大滚动量。 */
        float maxScroll;
    }

    /** TEMP-DIAG：绘制几何诊断是否已打过一次。 */
    private boolean loggedDrawDiag;

    /** 顶部语言芯片的矩形（设计坐标，{@code x,y,w,h}）；绘制时更新，点击时命中测试。 */
    private final float[] langChip = {0.0f, 0.0f, 0.0f, 0.0f};

    /**
     * @param registry 模块注册表；界面据此列出各分类的模块
     */
    public FaClickGui(final ModuleRegistry registry) {
        this.registry = registry;
        FaStrings.setChinese(LayoutStore.savedChinese());
        search.onChange(new java.util.function.Consumer<String>() {
            @Override
            public void accept(String text) {
                setQuery(text);
            }
        }).onSubmit(new java.util.function.Consumer<String>() {
            @Override
            public void accept(String text) {
                focus(null);
            }
        }).onCancel(new Runnable() {
            @Override
            public void run() {
                if (search.text().isEmpty()) {
                    closeSearch();
                } else {
                    search.set("");
                    setQuery("");
                }
            }
        });
    }

    // ── 诊断信息（信息浮层用） ───────────────────────────────────────────────

    /** @return 当前绘制后端名 */
    String backendName() {
        return backendName;
    }

    /** @return 设计单位 → 逻辑像素的缩放 */
    float scale() {
        return scale;
    }

    /** @return 是否走字形图集（否则是游戏字体） */
    boolean atlasFonts() {
        return FontRasterizer.atlasActive();
    }

    /** @return 有模块的分类摘要，例如 {@code "MOVEMENT, RENDER (2/4)"} */
    String categorySummary() {
        StringBuilder text = new StringBuilder();
        for (Category value : Category.values()) {
            List<FaGroup> list = groups.get(value);
            if (list == null || list.isEmpty()) {
                continue;
            }
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(value.name());
        }
        return text.length() == 0 ? "none" : text + " (" + categories.size() + "/"
                + Category.values().length + ")";
    }

    // ── OverlayGui ─────────────────────────────────────────────────────────

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void setOpen(boolean state) {
        if (this.open == state) {
            return;
        }
        this.open = state;
        if (state) {
            openMotion.snap(0.0f);
            contentFade.snap(0.6f);
            lastNanos = 0L;
        } else {
            close();
        }
    }

    @Override
    public void toggle() {
        setOpen(!open);
    }

    @Override
    public void setViewport(int width, int height) {
        this.viewportWidth = width;
        this.viewportHeight = height;
        updateScale();
        if (!placed && sw > 0f && sh > 0f) {
            initWindow();
        }
    }

    @Override
    public void update(long nowMs, double mouseX, double mouseY) {
        ctx.mouseX = (float) (mouseX / scale);
        ctx.mouseY = (float) (mouseY / scale);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        double dx = mx / scale;
        double dy = my / scale;
        mouseDown = true;
        FaPopup popup = topPopupAt(dx, dy);
        if (popup != null) {
            // 关掉叠在它上面的浮层；控件会自己重新取焦点。
            closeFrom(popups.indexOf(popup) + 1);
            focus(null);
            popup.mouseClicked(ctx, dx, dy, button);
            return true;
        }
        // 点在浮层之外：关掉全部浮层；若点的正是拥有浮层的控件，只关不重开。
        Object topOwner = null;
        for (int i = popups.size() - 1; i >= 0; i--) {
            if (!popups.get(i).closing()) {
                topOwner = popups.get(i).owner();
                break;
            }
        }
        suppressedOwner = topOwner;
        closeAllPopups();
        focus(null);
        try {
            return handleClick(dx, dy, button);
        } finally {
            suppressedOwner = null;
        }
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        mouseDown = false;
        if (drag != 0) {
            drag = 0;
            saveLayout();
        }
        for (FaPopup popup : popups) {
            popup.mouseReleased();
        }
        for (FaBox box : currentBoxes()) {
            box.mouseReleased();
        }
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        double x = mx / scale;
        double y = my / scale;
        if (drag == 1) {
            wx = (float) x - dragOffsetX;
            wy = (float) y - dragOffsetY;
            keepOnScreen();
            return true;
        }
        if (drag == 2) {
            ww = clamp(startW + (float) x - startX, FaTheme.MIN_W, sw - wx - 2.0f);
            wh = clamp(startH + (float) y - startY, FaTheme.MIN_H, sh - wy - 2.0f);
            return true;
        }
        for (FaPopup popup : popups) {
            popup.mouseDragged(ctx, x, y);
        }
        for (FaBox box : currentBoxes()) {
            box.mouseDragged(ctx, x, y);
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        double x = mx / scale;
        double y = my / scale;
        FaPopup popup = topPopupAt(x, y);
        if (popup != null) {
            return popup.mouseScrolled(ctx, x, y, amount);
        }
        View view = view();
        if (FaContext.inside(x, y, wx, contentY(), FaTheme.SIDEBAR_W, contentH())) {
            view.sideScroll -= (float) amount * SIDE_ITEM_H * 1.5f;
            return true;
        }
        if (!FaContext.inside(x, y, contentX(), contentY(), contentW(), contentH())) {
            return false;
        }
        // 悬停在可步进的控件（滑条）上时，滚轮改值；否则滚动内容。
        for (FaBox box : currentBoxes()) {
            if (box.mouseScrolled(ctx, x, y, amount)) {
                return true;
            }
        }
        closeAllPopups();
        view.scroll = clamp(view.scroll - (float) amount * 34.0f, 0.0f, view.maxScroll);
        view.selected = null;
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int modifiers) {
        if (focus != null && focus.keyPressed(keyCode, modifiers)) {
            return true;
        }
        if (keyCode == KeyEvent.VK_ESCAPE) {
            for (int i = popups.size() - 1; i >= 0; i--) {
                if (!popups.get(i).closing()) {
                    popups.get(i).close();
                    return true;
                }
            }
            if (searchActive) {
                closeSearch();
                return true;
            }
            setOpen(false);
            return true;
        }
        if (keyCode == KeyEvent.VK_F && (modifiers & 2) != 0) {
            openSearch();
            return true;
        }
        return false;
    }

    @Override
    public boolean charTyped(char character) {
        return focus != null && focus.charTyped(character);
    }

    @Override
    public void cancelInteractions() {
        mouseDown = false;
        drag = 0;
        focus(null);
        for (FaPopup popup : popups) {
            popup.mouseReleased();
        }
        for (FaBox box : currentBoxes()) {
            box.mouseReleased();
        }
        closeAllPopups();
    }

    @Override
    public void render(Renderer renderer) {
        if (renderer instanceof UiBackend) {
            UiBackend backend = (UiBackend) renderer;
            backendScale = Math.max(1, backend.scale());
            backendName = backend.backendName();
        } else {
            backendScale = 1;
            backendName = renderer.getClass().getSimpleName();
        }
        // 字体句柄每帧按「后端 + 缩放」同步：换了后端或缩放，字形图集必须整体重建。
        FaTheme.fonts = FontRasterizer.bind(renderer, scale, backendScale);
        if (!fontDiagLogged && System.getProperty("nocturne.ui.debug") != null) {
            fontDiagLogged = true;
            System.out.println("[nocturne] gui render: backend=" + backendName + " scale=" + scale
                    + " backendScale=" + backendScale + " fonts="
                    + FaTheme.fonts.getClass().getSimpleName()
                    + " atlas=" + FontRasterizer.atlasActive());
        }

        long now = System.nanoTime();
        float dt = lastNanos == 0L ? 1.0f / 60.0f : Math.min(0.1f, (now - lastNanos) / 1.0e9f);
        lastNanos = now;
        syncModules();
        // 按住鼠标时重算缩放会把正在拖的控件挪走，所以等松开再算。
        if (!mouseDown) {
            updateScale();
        }
        draw.begin(renderer, scale);
        ctx.draw = draw;
        ctx.dt = dt;
        drawFrame();
    }

    // ── 窗口生命周期 ────────────────────────────────────────────────────────

    /** 从保存的布局或屏幕中心初始化窗口几何。 */
    private void initWindow() {
        syncModules();
        float maxW = Math.max(FaTheme.MIN_W, sw - 16.0f);
        float maxH = Math.max(FaTheme.MIN_H, sh - 16.0f);
        if (LayoutStore.hasLayout()) {
            ww = clamp(Math.max(LayoutStore.savedWidth(), FaTheme.MIN_W), FaTheme.MIN_W, maxW);
            wh = clamp(Math.max(LayoutStore.savedHeight(), FaTheme.MIN_H), FaTheme.MIN_H, maxH);
            wx = LayoutStore.ratioX() * sw;
            wy = LayoutStore.ratioY() * sh;
            applySavedCategory(LayoutStore.savedCategory());
        } else {
            ww = Math.min(FaTheme.DEFAULT_W, maxW);
            wh = Math.min(FaTheme.DEFAULT_H, maxH);
            wx = (sw - ww) * 0.5f;
            wy = (sh - wh) * 0.5f;
        }
        fitWindow();
        placed = true;
        layoutKey = "";
    }

    /** 恢复上次选中的分类；该分类当前没有模块时保持默认。 */
    private void applySavedCategory(String name) {
        if (name == null || name.isEmpty()) {
            return;
        }
        for (Category value : Category.values()) {
            if (value.name().equalsIgnoreCase(name)) {
                category = value;
                return;
            }
        }
    }

    /** 关闭时的收尾：保存布局、复位交互状态。 */
    private void close() {
        cancelInteractions();
        saveLayout();
        searchActive = false;
        search.set("");
        setQuery("");
    }

    /** 保存窗口布局。 */
    private void saveLayout() {
        float rx = sw > 0f ? wx / sw : 0.5f;
        float ry = sh > 0f ? wy / sh : 0.5f;
        LayoutStore.save(rx, ry, ww, wh, category.name());
    }

    /**
     * 定出本帧的缩放：设计目标是一块 960×540 的画布；更小的绘制区整体缩小。
     *
     * <p>缩放变化时保持窗口中心不动，避免"改分辨率窗口跑掉"。
     */
    private void updateScale() {
        if (viewportWidth <= 0 || viewportHeight <= 0) {
            return;
        }
        float fit = Math.min(1.0f, Math.min((viewportWidth - 16.0f) / FaTheme.DEFAULT_W,
                (viewportHeight - 16.0f) / FaTheme.DEFAULT_H));
        float next = Math.max(0.3f, fit);
        if (next == scale && sw > 0.0f) {
            return;
        }
        if (placed) {
            float centerX = (wx + ww * 0.5f) * scale;
            float centerY = (wy + wh * 0.5f) * scale;
            wx = centerX / next - ww * 0.5f;
            wy = centerY / next - wh * 0.5f;
        }
        scale = next;
        sw = viewportWidth / scale;
        sh = viewportHeight / scale;
        if (placed) {
            fitWindow();
        }
    }

    /** 同步模块列表：注册表版本号变化时重建（运行期新注册的模块不会变成幽灵卡片）。 */
    private void syncModules() {
        long revision = registry.revision();
        if (revision == moduleRevision && !groups.isEmpty()) {
            return;
        }
        loadModules(registry.all());
        moduleRevision = revision;
    }

    /** 按分类重建卡片列表；没有模块的分类不出现在 Tab 上（契约见 {@link Category}）。 */
    void loadModules(List<Module> visible) {
        groups.clear();
        List<Category> available = new ArrayList<Category>();
        for (Category value : Category.values()) {
            List<FaGroup> list = new ArrayList<FaGroup>();
            for (Module module : visible) {
                if (module.category() == value) {
                    list.add(new FaGroup(module));
                }
            }
            if (list.isEmpty()) {
                continue;
            }
            groups.put(value, list);
            if (!views.containsKey(value)) {
                views.put(value, new View());
            }
            if (!tabHover.containsKey(value)) {
                tabHover.put(value, new FaMotion(0.0f));
            }
            available.add(value);
        }
        categories = Collections.unmodifiableList(available);
        if (!categories.contains(category)) {
            category = categories.isEmpty() ? Category.MISC : categories.get(0);
        }
        layoutKey = "";
        if (!query.isEmpty()) {
            setQuery(query);
        }
    }

    // ── 浮层与焦点 ──────────────────────────────────────────────────────────

    /** @return 给定控件是否有打开的浮层 */
    boolean isPopupOpen(Object owner) {
        for (FaPopup popup : popups) {
            if (popup.owner() == owner && !popup.closing()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 打开或关闭 {@code owner} 的浮层。
     *
     * @param factory 需要时用来创建浮层
     * @param beside  为 true 时放在锚点旁边，否则放下方（底部空间不足时翻到上方）
     */
    void togglePopup(Object owner, Supplier<FaPopup> factory, float ax, float ay, float aw, float ah,
                     boolean beside) {
        if (owner == suppressedOwner) {
            return;
        }
        for (int i = 0; i < popups.size(); i++) {
            FaPopup popup = popups.get(i);
            if (popup.owner() == owner && !popup.closing()) {
                closeFrom(i);
                return;
            }
        }
        FaPopup popup = factory.get();
        if (beside) {
            popup.placeBeside(ax, ay, aw, ah, 0, 0, sw, sh);
        } else if (ay >= wy + wh - FaTheme.FOOTER_H - 1.0f) {
            popup.placeAbove(ax, ay, 0, 0, sw, sh);
        } else {
            popup.place(ax, ay, aw, ah, 0, 0, sw, sh);
        }
        popups.add(popup);
    }

    /** 关闭从 {@code index} 起（含）往上的所有浮层。 */
    private void closeFrom(int index) {
        for (int i = popups.size() - 1; i >= index; i--) {
            popups.get(i).close();
        }
    }

    /** 关闭全部浮层。 */
    private void closeAllPopups() {
        closeFrom(0);
    }

    /** 切换键盘焦点。 */
    void focus(FaKeySink sink) {
        if (focus == sink) {
            return;
        }
        FaKeySink previous = focus;
        focus = sink;
        if (previous != null) {
            previous.focusLost();
        }
    }

    /** 显示一条提示（底部工具条右侧）。 */
    void toast(String message) {
        toast = message;
        toastAt = System.currentTimeMillis();
    }

    /** @return 是否有打开的浮层覆盖给定矩形（预览卡片据此推迟画装备） */
    boolean popupOver(float x, float y, float w, float h) {
        for (FaPopup popup : popups) {
            if (!popup.closing() && popup.x < x + w && popup.x + popup.w > x
                    && popup.y < y + h && popup.y + popup.h > y) {
                return true;
            }
        }
        return false;
    }

    // ── 绘制 ────────────────────────────────────────────────────────────────

    /** 绘制一帧。 */
    private void drawFrame() {
        ctx.draw = draw;
        popups.removeIf(new java.util.function.Predicate<FaPopup>() {
            @Override
            public boolean test(FaPopup popup) {
                return popup.finished();
            }
        });
        ctx.hoverEnabled = topPopupAt(ctx.mouseX, ctx.mouseY) == null && drag == 0;
        float openAmount = openMotion.toward(1.0f, ctx.dt, 14.0f);
        try (FaDraw.Scope ignored = draw.push()) {
            float lift = (1.0f - openAmount) * 8.0f;
            draw.translate(0.0f, lift);
            draw.opacity(openAmount);
            drawWindow(lift, openAmount);
            for (int i = 0; i < popups.size(); i++) {
                FaPopup popup = popups.get(i);
                ctx.hoverEnabled = popup.contains(ctx.mouseX, ctx.mouseY)
                        && topPopupAt(ctx.mouseX, ctx.mouseY) == popup;
                popup.render(ctx);
            }
        }
    }

    /** 画窗口本体（底衬、侧边栏、内容、头部、底部）。 */
    private void drawWindow(float lift, float openAmount) {
        draw.shadow(wx, wy, ww, wh, FaTheme.RADIUS, 16.0f, 0xA0000000);
        draw.rect(wx, wy, ww, wh, FaTheme.RADIUS, FaTheme.WINDOW);
        drawSidebar();
        drawContent();
        drawHeader(lift, openAmount);
        drawFooter();
    }

    /** @return 品牌文字右边缘 */
    private float logoRight() {
        return wx + 16.0f + FaTheme.logo().width(FaTheme.BRAND);
    }

    /** @return 第 {@code index} 个 Tab 的矩形 */
    private float[] tabBounds(int index, boolean iconsOnly) {
        FaFont font = FaTheme.tab();
        float x = Math.max(wx + 103.0f, logoRight() + 14.0f);
        for (int i = 0; i <= index; i++) {
            Category value = categories.get(i);
            float labelW = iconsOnly ? 0.0f : TAB_GAP + font.width(value.name());
            float w = TAB_PAD + TAB_ICON + labelW + TAB_PAD;
            if (i == index) {
                return new float[]{x, wy + (FaTheme.HEADER_H - TAB_H) * 0.5f, w, TAB_H};
            }
            x += w + 2.0f;
        }
        return new float[]{x, wy, 0f, TAB_H};
    }

    /** @return 空间不足时 Tab 只显示图标 */
    private boolean tabsIconsOnly() {
        if (categories.isEmpty()) {
            return false;
        }
        float[] last = tabBounds(categories.size() - 1, false);
        return last[0] + last[2] > tabsRightLimit() - 10.0f;
    }

    /** @return 右侧状态文字的左边界；Tab 不许越过它 */
    private float tabsRightLimit() {
        return wx + ww - 18.0f - FaTheme.small().width(statusText());
    }

    /** @return 头部右侧的状态文字（后端 + 缩放） */
    private String statusText() {
        return backendName + " " + String.format(Locale.ROOT, "%.2f", scale);
    }

    /** 画头部：渐变表面、分类 Tab、状态文字。 */
    private void drawHeader(float lift, float openAmount) {
        float h = FaTheme.HEADER_H;
        draw.vertical(wx, wy + h, ww, SHADE, 0xB0101010, 0x00101010);
        draw.rect(wx, wy, ww, FaTheme.RADIUS * 2.0f, FaTheme.RADIUS, FaTheme.HEADER_TOP);
        draw.vertical(wx, wy + FaTheme.RADIUS, ww, h - FaTheme.RADIUS,
                FaTheme.mix(FaTheme.HEADER_TOP, FaTheme.HEADER, 0.1f), FaTheme.HEADER);
        draw.rect(wx, wy + h - 0.5f, ww, 0.5f, FaTheme.DIVIDER);
        float mid = wy + h * 0.5f;
        draw.textMid(FaTheme.BRAND, wx + 16.0f, mid + 0.5f, FaTheme.logo(), FaTheme.LOGO);

        boolean iconsOnly = tabsIconsOnly();
        FaFont font = FaTheme.tab();
        int activeIndex = categories.indexOf(category);
        if (activeIndex >= 0 && query.isEmpty()) {
            float[] active = tabBounds(activeIndex, iconsOnly);
            float relX = active[0] - wx;
            if (Float.isNaN(tabX.value())) {
                tabX.snap(relX);
                tabW.snap(active[2]);
            }
            float px = wx + tabX.toward(relX, ctx.dt, 18.0f);
            float pw = tabW.toward(active[2], ctx.dt, 18.0f);
            draw.rect(px, active[1], pw, TAB_H, 3.0f, FaTheme.TAB_ACTIVE);
        }
        for (int i = 0; i < categories.size(); i++) {
            Category value = categories.get(i);
            float[] b = tabBounds(i, iconsOnly);
            boolean selected = value == category && query.isEmpty();
            FaMotion hover = tabHover.get(value);
            float hov = hover == null ? 0.0f
                    : hover.toward(ctx.hovered(b[0], b[1], b[2], b[3]) ? 1.0f : 0.0f, ctx.dt, 16.0f);
            int textColor = selected ? FaTheme.TAB_TEXT_ACTIVE
                    : FaTheme.mix(FaTheme.TAB_TEXT, FaTheme.TAB_TEXT_HOVER, hov);
            int iconColor = selected ? FaTheme.ACCENT : textColor;
            draw.icon(FaTheme.icon(value), b[0] + TAB_PAD + TAB_ICON * 0.5f, mid, TAB_ICON, iconColor);
            if (!iconsOnly) {
                draw.textMid(value.name(), b[0] + TAB_PAD + TAB_ICON + TAB_GAP, mid + 0.5f, font, textColor);
            }
        }

        String status = statusText();
        draw.textRightMid(status, wx + ww - 18.0f, mid, FaTheme.small(), FaTheme.MUTED);
        // 语言开关：右侧状态文本左边一枚小芯片，点一下中/英切换。
        float chipW = 26.0f;
        float chipH = 13.0f;
        float chipX = wx + ww - 18.0f - FaTheme.small().width(status) - 10.0f - chipW;
        float chipY = mid - chipH * 0.5f;
        langChip[0] = chipX;
        langChip[1] = chipY;
        langChip[2] = chipW;
        langChip[3] = chipH;
        boolean chipHover = FaContext.inside(ctx.mouseX, ctx.mouseY, chipX, chipY, chipW, chipH);
        draw.rect(chipX, chipY, chipW, chipH, 3.0f,
                FaTheme.mix(FaTheme.TAB_ACTIVE, FaTheme.TAB_TEXT_HOVER, chipHover ? 0.35f : 0.0f));
        draw.textCenter(FaStrings.isChinese() ? "中" : "EN", chipX + chipW * 0.5f, mid + 0.5f,
                FaTheme.small(), chipHover ? FaTheme.TAB_TEXT_ACTIVE : FaTheme.TAB_TEXT);
    }

    /** @return 内容区左边缘 */
    private float contentX() {
        return wx + FaTheme.SIDEBAR_W;
    }

    /** @return 内容区上边缘 */
    private float contentY() {
        return wy + FaTheme.HEADER_H;
    }

    /** @return 内容区宽 */
    private float contentW() {
        return ww - FaTheme.SIDEBAR_W;
    }

    /** @return 内容区高 */
    private float contentH() {
        return wh - FaTheme.HEADER_H - FaTheme.FOOTER_H;
    }

    /** @return 当前视图（搜索时用独立视图） */
    private View view() {
        if (!query.isEmpty()) {
            return searchView;
        }
        View existing = views.get(category);
        if (existing == null) {
            existing = new View();
            views.put(category, existing);
        }
        return existing;
    }

    /** @return 当前分类下的卡片 */
    private List<FaGroup> currentGroups() {
        if (!query.isEmpty()) {
            return searchView.shown;
        }
        List<FaGroup> list = groups.get(category);
        return list == null ? Collections.<FaGroup>emptyList() : list;
    }

    /** @return 内容瀑布流里要排的卡片；Render 分类额外插入预览卡片（在第二位） */
    private List<FaBox> currentBoxes() {
        List<FaGroup> list = currentGroups();
        List<FaBox> boxes = new ArrayList<FaBox>(list.size() + 1);
        boxes.addAll(list);
        if (query.isEmpty() && category == Category.RENDER) {
            boxes.add(Math.min(1, boxes.size()), preview);
        }
        return boxes;
    }

    /** @return 侧边栏第一项的上边缘 */
    private float sideTop() {
        return contentY() + 11.0f;
    }

    /** 画侧边栏：当前分类的模块名列表 + 活动项高亮条。 */
    private void drawSidebar() {
        View view = view();
        List<FaGroup> list = currentGroups();
        float top = sideTop();
        float height = contentH() - 16.0f;
        float maxSide = Math.max(0.0f, list.size() * SIDE_ITEM_H - height);
        view.sideScroll = clamp(view.sideScroll, 0.0f, maxSide);
        float offset = view.sideScrollMotion.toward(view.sideScroll, ctx.dt, 16.0f);
        Module active = activeModule(view, list);
        FaFont font = FaTheme.label();
        try (FaDraw.Scope clip = draw.clip(wx, top - 2.0f, FaTheme.SIDEBAR_W, height + 4.0f)) {
            int activeIndex = -1;
            for (int i = 0; i < list.size(); i++) {
                FaGroup group = list.get(i);
                float iy = top + i * SIDE_ITEM_H - offset;
                if (group.module == active) {
                    activeIndex = i;
                }
                if (iy + SIDE_ITEM_H < top - 2.0f || iy > top + height) {
                    continue;
                }
                boolean hovered = ctx.hovered(wx, iy, FaTheme.SIDEBAR_W, SIDE_ITEM_H);
                int color = group.module == active ? FaTheme.SIDE_TEXT_ACTIVE
                        : hovered ? FaTheme.LABEL
                        : group.module.isEnabled() ? FaTheme.SIDE_TEXT_ENABLED : FaTheme.SIDE_TEXT;
                draw.textMid(FaTheme.fit(group.title(), font, FaTheme.SIDEBAR_W - 22.0f), wx + 16.0f,
                        iy + SIDE_ITEM_H * 0.5f, font, color);
            }
            if (activeIndex >= 0) {
                float target = activeIndex * SIDE_ITEM_H - offset;
                if (Float.isNaN(view.barY.value())) {
                    view.barY.snap(target);
                }
                float barY = top + view.barY.toward(target, ctx.dt, 18.0f);
                draw.rect(wx + 9.0f, barY + (SIDE_ITEM_H - 16.0f) * 0.5f, 2.0f, 16.0f, 1.0f, FaTheme.ACCENT);
            }
        }
    }

    /** 点过的模块保持高亮，否则跟随滚动位置。 */
    private Module activeModule(View view, List<FaGroup> list) {
        if (list.isEmpty()) {
            return null;
        }
        if (view.selected != null) {
            for (FaGroup group : list) {
                if (group.module == view.selected) {
                    return view.selected;
                }
            }
        }
        FaGroup best = list.get(0);
        for (FaGroup group : list) {
            if (group.targetY() <= view.scroll + 18.0f && group.targetY() > best.targetY()) {
                best = group;
            }
        }
        return best.module;
    }

    /** @return 内容区能排几列（1..3）；包内可见以便布局测试直接钉住列数计算 */
    int columns(float contentWidth) {
        return Math.max(1, Math.min(3, (int) Math.floor((contentWidth - FaTheme.CONTENT_PAD + FaTheme.COLUMN_GAP)
                / (FaTheme.MIN_COLUMN_W + FaTheme.COLUMN_GAP))));
    }

    /** 画内容区：按最短列瀑布流摆放卡片。 */
    private void drawContent() {
        View view = view();
        List<FaBox> list = currentBoxes();
        float cx = contentX();
        float cy = contentY();
        float cw = contentW();
        float ch = contentH();
        int columns = columns(cw);
        float columnW = (cw - FaTheme.CONTENT_PAD - FaTheme.COLUMN_GAP * (columns - 1)) / columns;
        String key = (query.isEmpty() ? category.name() : "?" + query) + "|" + columns + "|"
                + Math.round(columnW);
        boolean snap = !key.equals(layoutKey);
        layoutKey = key;

        float[] columnHeights = new float[columns];
        for (FaBox box : list) {
            int column = 0;
            for (int i = 1; i < columns; i++) {
                if (columnHeights[i] < columnHeights[column] - 0.5f) {
                    column = i;
                }
            }
            box.place(column * (columnW + FaTheme.COLUMN_GAP), columnHeights[column], snap);
            columnHeights[column] += box.targetHeight() + FaTheme.GROUP_GAP;
        }
        float total = 0.0f;
        for (float height : columnHeights) {
            total = Math.max(total, height);
        }
        view.maxScroll = Math.max(0.0f, total - FaTheme.GROUP_GAP + FaTheme.CONTENT_PAD * 2.0f - ch);
        view.scroll = clamp(view.scroll, 0.0f, view.maxScroll);
        if (snap) {
            view.scrollMotion.snap(view.scroll);
        }
        float scroll = view.scrollMotion.toward(view.scroll, ctx.dt, 15.0f);

        float fade = contentFade.toward(1.0f, ctx.dt, 12.0f);
        try (FaDraw.Scope clip = draw.clip(cx, cy, cw, ch)) {
            try (FaDraw.Scope faded = draw.push()) {
                draw.translate(0.0f, (1.0f - fade) * 6.0f);
                draw.opacity(fade);
                float originY = cy + FaTheme.CONTENT_PAD - scroll;
                for (FaBox box : list) {
                    box.render(ctx, cx, originY, columnW, cy, cy + ch, 1.0f);
                }
                if (list.isEmpty()) {
                    draw.textCenter(query.isEmpty() ? "No modules"
                                    : "Nothing matches \"" + query + "\"",
                            cx + cw * 0.5f, cy + ch * 0.4f, FaTheme.label(), FaTheme.MUTED);
                }
            }
        }
        if (view.maxScroll > 0.5f) {
            boolean moving = Math.abs(scroll - view.scroll) > 0.5f;
            boolean near = ctx.hovered(cx + cw - 12.0f, cy, 12.0f, ch);
            float shown = scrollbar.toward(moving || near ? 1.0f : 0.35f, ctx.dt, moving ? 20.0f : 6.0f);
            float trackH = ch - 12.0f;
            float thumbH = Math.max(18.0f, trackH * ch / (ch + view.maxScroll));
            float thumbY = cy + 6.0f + (trackH - thumbH) * (scroll / view.maxScroll);
            draw.rect(cx + cw - 5.0f, thumbY, 2.0f, thumbH, 1.0f, FaTheme.alpha(0xFF333333, shown));
        }
    }

    /** @return 第 {@code index} 个底部图标的中心 x */
    private float footerIconX(int index) {
        return wx + 16.0f + index * 23.0f;
    }

    /** 底部工具条：搜索与信息两个图标、搜索框、提示、右下角缩放手柄。 */
    private void drawFooter() {
        float fy = wy + wh - FaTheme.FOOTER_H;
        draw.vertical(wx, fy - SHADE, ww, SHADE, 0x00101010, 0xB0101010);
        draw.rect(wx, fy, ww, FaTheme.FOOTER_H, FaTheme.RADIUS, FaTheme.FOOTER);
        draw.rect(wx, fy, ww, 0.5f, FaTheme.DIVIDER);
        float mid = fy + FaTheme.FOOTER_H * 0.5f;
        String[] icons = {FaTheme.I_SEARCH, FaTheme.I_INFO};
        for (int i = 0; i < icons.length; i++) {
            float ix = footerIconX(i);
            float hover = footerHover[i].toward(ctx.hovered(ix - 9, fy, 18, FaTheme.FOOTER_H) ? 1.0f : 0.0f,
                    ctx.dt, 16.0f);
            boolean active = i == 0 && searchActive || i == 1 && isPopupOpen(icons[1]);
            int color = active ? FaTheme.ACCENT : FaTheme.mix(FaTheme.ICON, FaTheme.ICON_HOVER, hover);
            draw.icon(icons[i], ix, mid, 11.0f, color);
        }

        float openAmount = searchOpen.toward(searchActive ? 1.0f : 0.0f, ctx.dt, 16.0f);
        float fieldX = footerIconX(1) + 16.0f;
        if (openAmount > 0.01f) {
            float fieldW = SEARCH_W * openAmount;
            draw.rect(fieldX, mid - 7.0f, fieldW, 14.0f, FaTheme.FIELD_RADIUS, FaTheme.FIELD);
            try (FaDraw.Scope clip = draw.clip(fieldX, mid - 8.0f, fieldW, 16.0f)) {
                search.render(draw, fieldX + 6.0f, mid, fieldW - 12.0f, FaTheme.field(), FaTheme.LABEL,
                        focus == searchSink, "Search modules and settings", FaTheme.MUTED);
            }
        }
        float toastVisible = toastAlpha.toward(
                System.currentTimeMillis() - toastAt < TOAST_MS && !toast.isEmpty() ? 1.0f : 0.0f, ctx.dt, 10.0f);
        if (toastVisible > 0.01f) {
            float tx = fieldX + SEARCH_W * openAmount + (openAmount > 0.01f ? 12.0f : 0.0f);
            draw.textMid(toast, tx, mid, FaTheme.field(), FaTheme.alpha(FaTheme.FIELD_TEXT_HOVER, toastVisible));
        }
        int gripColor = drag == 2 || ctx.hovered(wx + ww - GRIP, wy + wh - GRIP, GRIP, GRIP)
                ? FaTheme.ICON_HOVER : 0xFF2E2E2E;
        for (int i = 0; i < 3; i++) {
            draw.rect(wx + ww - 4.0f - i * 2.5f, wy + wh - 4.0f, 1.2f, 1.2f, 0.6f, gripColor);
        }
    }

    // ── 搜索 ────────────────────────────────────────────────────────────────

    /** 展开搜索框并聚焦。 */
    private void openSearch() {
        searchActive = true;
        search.focused();
        focus(searchSink);
    }

    /** 收起搜索框并清空搜索词。 */
    private void closeSearch() {
        searchActive = false;
        search.set("");
        setQuery("");
        focus(null);
    }

    /** 应用搜索词：收窄卡片与行。 */
    private void setQuery(String text) {
        String next = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        // 中文界面下按中文名搜索：先把输入映射回英文名再匹配（数据侧键始终是英文）。
        String mapped = FaStrings.searchKey(next);
        if (!mapped.equals(next)) {
            next = mapped.toLowerCase(Locale.ROOT);
        }
        boolean changed = !next.equals(query);
        query = next;
        if (query.isEmpty()) {
            for (List<FaGroup> list : groups.values()) {
                for (FaGroup group : list) {
                    group.filter("");
                }
            }
            searchView.shown = Collections.emptyList();
        } else {
            List<FaGroup> matches = new ArrayList<FaGroup>();
            for (Category value : categories) {
                List<FaGroup> list = groups.get(value);
                if (list == null) {
                    continue;
                }
                for (FaGroup group : list) {
                    if (group.filter(query)) {
                        matches.add(group);
                    }
                }
            }
            searchView.shown = matches;
        }
        if (changed) {
            searchView.scroll = 0.0f;
            searchView.selected = null;
            contentFade.snap(0.6f);
            closeAllPopups();
        }
    }

    // ── 输入命中 ────────────────────────────────────────────────────────────

    /** @return 指针位置上最上层的浮层 */
    private FaPopup topPopupAt(double mx, double my) {
        for (int i = popups.size() - 1; i >= 0; i--) {
            if (popups.get(i).contains(mx, my)) {
                return popups.get(i);
            }
        }
        return null;
    }

    /** 处理落在窗口本体上的点击。 */
    private boolean handleClick(double mx, double my, int button) {
        float fy = wy + wh - FaTheme.FOOTER_H;
        if (FaContext.inside(mx, my, wx + ww - GRIP, wy + wh - GRIP, GRIP, GRIP) && button == 0) {
            drag = 2;
            startW = ww;
            startH = wh;
            startX = (float) mx;
            startY = (float) my;
            return true;
        }
        if (FaContext.inside(mx, my, wx, fy, ww, FaTheme.FOOTER_H)) {
            if (button != 0) {
                return true;
            }
            if (FaContext.inside(mx, my, footerIconX(0) - 9, fy, 18, FaTheme.FOOTER_H)) {
                if (searchActive) {
                    closeSearch();
                } else {
                    openSearch();
                }
            } else if (FaContext.inside(mx, my, footerIconX(1) - 9, fy, 18, FaTheme.FOOTER_H)) {
                togglePopup(FaTheme.I_INFO, new Supplier<FaPopup>() {
                    @Override
                    public FaPopup get() {
                        return new FaInfoPopup(FaTheme.I_INFO, FaClickGui.this);
                    }
                }, wx + ww - 200.0f, fy, 18, FaTheme.FOOTER_H, false);
            } else if (searchActive && FaContext.inside(mx, my, footerIconX(1) + 16, fy, SEARCH_W,
                    FaTheme.FOOTER_H)) {
                search.focused();
                focus(searchSink);
            }
            return true;
        }
        if (FaContext.inside(mx, my, wx, wy, ww, FaTheme.HEADER_H)) {
            boolean iconsOnly = tabsIconsOnly();
            for (int i = 0; i < categories.size(); i++) {
                float[] b = tabBounds(i, iconsOnly);
                if (FaContext.inside(mx, my, b[0], b[1], b[2], b[3])) {
                    if (button == 0) {
                        switchCategory(categories.get(i));
                    }
                    return true;
                }
            }
            if (button == 0 && FaContext.inside(mx, my, langChip[0], langChip[1],
                    langChip[2], langChip[3])) {
                FaStrings.setChinese(!FaStrings.isChinese());
                LayoutStore.saveChinese(FaStrings.isChinese());
                layoutKey = "";
                return true;
            }
            if (button == 0) {
                drag = 1;
                dragOffsetX = (float) mx - wx;
                dragOffsetY = (float) my - wy;
            }
            return true;
        }
        if (!FaContext.inside(mx, my, wx, wy, ww, wh)) {
            return false;
        }
        if (FaContext.inside(mx, my, wx, contentY(), FaTheme.SIDEBAR_W, contentH())) {
            View view = view();
            List<FaGroup> list = currentGroups();
            float top = sideTop() - view.sideScrollMotion.value();
            int index = (int) Math.floor((my - top) / SIDE_ITEM_H);
            if (button == 0 && index >= 0 && index < list.size()) {
                FaGroup group = list.get(index);
                view.selected = group.module;
                view.scroll = clamp(group.targetY(), 0.0f, view.maxScroll);
                group.flash();
            }
            return true;
        }
        if (FaContext.inside(mx, my, contentX(), contentY(), contentW(), contentH())) {
            for (FaBox box : currentBoxes()) {
                if (box.mouseClicked(ctx, mx, my, button)) {
                    return true;
                }
            }
        }
        return true;
    }

    /** 切换分类。 */
    private void switchCategory(Category next) {
        if (searchActive) {
            closeSearch();
        }
        if (next == category) {
            return;
        }
        category = next;
        contentFade.snap(0.0f);
        View view = view();
        view.selected = null;
        // 换分类必须复位滚动：否则新分类继承旧分类的滚动量，卡片从中间开始、半张被裁掉。
        view.scroll = 0.0f;
        view.scrollMotion.snap(0.0f);
        view.sideScroll = 0.0f;
        view.sideScrollMotion.snap(0.0f);
        layoutKey = "";
    }

    // ── 几何辅助 ────────────────────────────────────────────────────────────

    /** 把窗口夹到绘制区内（至少露出 60 设计单位，便于再拖回来）。 */
    private void fitWindow() {
        ww = clamp(ww, Math.min(FaTheme.MIN_W, sw - 8.0f), Math.max(FaTheme.MIN_W, sw - 8.0f));
        wh = clamp(wh, Math.min(FaTheme.MIN_H, sh - 8.0f), Math.max(FaTheme.MIN_H, sh - 8.0f));
        keepOnScreen();
    }

    /** 保证窗口标题栏与左边缘始终可点。 */
    private void keepOnScreen() {
        wx = clamp(wx, 4.0f - ww + 60.0f, sw - 60.0f);
        wy = clamp(wy, 4.0f, sh - FaTheme.HEADER_H);
    }

    /** 夹取到 {@code [min, max]}；{@code max < min} 时取 {@code min}。 */
    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(Math.max(min, max), value));
    }
}
