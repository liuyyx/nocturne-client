package dev.nocturne.client.render3d;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.bootstrap.GameplayHook;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.module.Module;
import dev.nocturne.client.render.CameraState;
import dev.nocturne.client.render.WorldProjection;

import java.util.ArrayList;
import java.util.List;

/**
 * 世界空间 3D 渲染器：把游戏世界绘制钩子、相机读取、模块遍历与后端粘在一起。
 *
 * <p>调用链（1.8.9）：
 * <pre>
 * EntityRenderer#renderWorld(FJ)V 末尾
 *   → GameplayHook.worldRender3D(F) → GameplayEvents → 本类 render(F)
 *     → CameraState.read(...)（眼位/朝向/有效 FOV，反射桥）
 *     → World3DBackend.begin(camera)（投影 + 模型视图 + 状态压栈）
 *     → 订阅者（WorldRenderEvent）+ 启用中的 World3DOverlay 模块
 *     → World3DBackend.end()（状态/矩阵还原）
 * </pre>
 *
 * <p>能力状态是显式的：没有后端、后端不支持、钩子未织入，三种情况 {@link #capability()} 都会返回
 * {@code available=false} 并携带具体原因——不存在「日志说接上了、实际什么都没画」。
 *
 * <p>线程模型：只在渲染线程调用。模块快照按 {@code ModuleRegistry.revision()} 缓存，启用状态每帧现查。
 */
public final class World3DRenderer {

    /** 单模块/单后端异常只记录一次，避免每帧刷屏。 */
    private static final long ERROR_LOG_INTERVAL_MS = 5000L;

    private final NocturneClient client;
    /** 3D 相机读取器；持有自己的投影对象（不被 2D 覆盖层使用）。 */
    private final CameraState camera = new CameraState(new WorldProjection());
    /** 复用的事件载荷。 */
    private final WorldRenderEvent event = new WorldRenderEvent();
    /** 复用上下文。 */
    private final Context context = new Context();

    /** 已安装的后端；{@code null} 表示尚未安装或已显式降级。 */
    private volatile World3DBackend backend;
    /** 显式降级时的后端名（能力矩阵用）。 */
    private volatile String backendName = "none";
    /** 显式降级原因。 */
    private volatile String unavailableReason = "3D backend not installed yet";

    /** 缓存的「实现了 World3DOverlay 的模块」快照。 */
    private Module[] overlayModules = new Module[0];
    /** 快照对应的注册表 revision。 */
    private long overlayRevision = Long.MIN_VALUE;

    /** 调试用示例覆盖层（仅在 {@code -Dnocturne.debug.world3d} 下启用）。 */
    private final World3DDebugOverlay debugOverlay =
            System.getProperty("nocturne.debug.world3d") != null ? new World3DDebugOverlay() : null;

    private boolean beginErrorLogged;
    private boolean drawErrorLogged;
    private boolean endErrorLogged;
    private long beginErrorAt;
    private long drawErrorAt;
    private long endErrorAt;

    public World3DRenderer(NocturneClient client) {
        this.client = client;
    }

    /**
     * 安装/替换 3D 后端（由 ui 的叠加层在装配绘制栈时调用）。
     *
     * @param replacement 新后端；{@code null} 等价于降级（应改用 {@link #markUnavailable}）
     */
    public void setBackend(World3DBackend replacement) {
        this.backend = replacement;
        if (replacement == null) {
            this.backendName = "none";
            this.unavailableReason = "3D backend not installed";
            return;
        }
        this.backendName = replacement.backendName();
        this.unavailableReason = replacement.available() ? null : replacement.unavailableReason();
        log(replacement.available()
                ? "world3d backend=" + replacement.backendName()
                : "world3d unavailable (" + replacement.backendName() + "): "
                + replacement.unavailableReason());
    }

    /**
     * 显式降级：当前绘制栈没有可用的世界空间 3D 后端。
     *
     * @param name   绘制栈名（{@code gl-core} / {@code gui-graphics} / …）
     * @param reason 具体原因，必须能让人判断缺的是什么
     */
    public void markUnavailable(String name, String reason) {
        this.backend = null;
        this.backendName = name == null ? "none" : name;
        this.unavailableReason = reason == null ? "3D backend unavailable" : reason;
        log("world3d unavailable (" + this.backendName + "): " + this.unavailableReason);
    }

    /**
     * 当前能力结论。不会分配队列/GL 资源，可在任意线程读取。
     *
     * @return 只读能力对象（每次调用新建，非热路径）
     */
    public World3DCapability capability() {
        World3DBackend b = backend;
        if (b != null && b.available()) {
            if (GameplayHook.isUnavailable("world3d")) {
                return new World3DCapability(false, b.backendName(),
                        GameplayHook.unavailableReason("world3d"));
            }
            return new World3DCapability(true, b.backendName(), null);
        }
        if (b != null) {
            return new World3DCapability(false, b.backendName(), b.unavailableReason());
        }
        return new World3DCapability(false, backendName, unavailableReason);
    }

    /**
     * 由世界绘制钩子调用：读取相机并画一帧。
     *
     * @param partialTicks 游戏传进来的当帧插值量
     * @return 是否真的进入了绘制（相机/钩子/后端任一缺失都返回 {@code false}）
     */
    public boolean render(float partialTicks) {
        NocturneClient current = client;
        if (current == null || current.isPanicked()) {
            return false;
        }
        GameBridge bridge = current.gameBridge();
        if (bridge == null || !bridge.inWorld()) {
            return false;
        }
        if (!camera.read(bridge, partialTicks)) {
            return false;
        }
        return renderCamera(camera.view());
    }

    /**
     * 用一份已读好的相机快照画一帧（单元测试与内部调用共用）。
     *
     * @param cam 相机快照；{@code null} 或无效时直接返回
     * @return 是否进入了绘制
     */
    public boolean renderCamera(WorldCamera cam) {
        if (cam == null || !cam.valid()) {
            return false;
        }
        World3DBackend b = backend;
        if (b == null || !b.available()) {
            return false;
        }
        NocturneClient current = client;
        if (current == null || current.isPanicked()) {
            return false;
        }
        refreshOverlays();
        boolean wantsEvent = current.events().hasSubscribers(WorldRenderEvent.class);
        if (!wantsEvent && debugOverlay == null && !anyOverlayEnabled()) {
            return false;
        }
        boolean began;
        try {
            began = b.begin(cam);
        } catch (Throwable t) {
            logBeginError(t);
            return false;
        }
        if (!began) {
            return false;
        }
        try {
            event.update(cam, context, b.viewportWidth(), b.viewportHeight());
            if (wantsEvent) {
                current.events().post(event);
            }
            if (debugOverlay != null) {
                drawSafely(debugOverlay);
            }
            Module[] modules = overlayModules;
            for (int i = 0; i < modules.length; i++) {
                Module module = modules[i];
                if (!(module instanceof World3DOverlay) || !module.isEnabled()) {
                    continue;
                }
                drawSafely((World3DOverlay) module);
            }
        } catch (Throwable t) {
            logDrawError(t);
        } finally {
            try {
                b.end();
            } catch (Throwable t) {
                logEndError(t);
            }
        }
        return true;
    }

    /** 执行单个覆盖层；异常隔离，绝不让一个模块拖垮整帧或游戏。 */
    private void drawSafely(World3DOverlay overlay) {
        try {
            overlay.drawWorld3D(context);
        } catch (Throwable t) {
            logDrawError(t);
        }
    }

    /** 按注册表 revision 缓存实现 World3DOverlay 的模块列表（启用状态不缓存）。 */
    private void refreshOverlays() {
        NocturneClient current = client;
        if (current == null) {
            return;
        }
        long revision = current.modules().revision();
        if (revision == overlayRevision) {
            return;
        }
        overlayRevision = revision;
        List<Module> found = new ArrayList<Module>();
        for (Module module : current.modules().all()) {
            if (module instanceof World3DOverlay) {
                found.add(module);
            }
        }
        overlayModules = found.toArray(new Module[found.size()]);
    }

    private boolean anyOverlayEnabled() {
        Module[] modules = overlayModules;
        for (int i = 0; i < modules.length; i++) {
            if (modules[i].isEnabled()) {
                return true;
            }
        }
        return false;
    }

    private void logBeginError(Throwable error) {
        long now = System.currentTimeMillis();
        if (beginErrorLogged && now - beginErrorAt < ERROR_LOG_INTERVAL_MS) {
            return;
        }
        beginErrorLogged = true;
        beginErrorAt = now;
        log("world3d begin failed: " + error);
    }

    private void logDrawError(Throwable error) {
        long now = System.currentTimeMillis();
        if (drawErrorLogged && now - drawErrorAt < ERROR_LOG_INTERVAL_MS) {
            return;
        }
        drawErrorLogged = true;
        drawErrorAt = now;
        log("world3d overlay failed: " + error);
    }

    private void logEndError(Throwable error) {
        long now = System.currentTimeMillis();
        if (endErrorLogged && now - endErrorAt < ERROR_LOG_INTERVAL_MS) {
            return;
        }
        endErrorLogged = true;
        endErrorAt = now;
        log("world3d end failed: " + error);
    }

    private static void log(String message) {
        System.out.println("[nocturne] " + message);
    }

    /** 当帧上下文：只在本类的绘制窗口内有效。 */
    private final class Context implements World3DContext {

        @Override
        public WorldCamera camera() {
            return camera.view();
        }

        @Override
        public World3DBatch batch() {
            World3DBackend b = backend;
            if (b == null) {
                return NullWorld3DBatch.INSTANCE;
            }
            World3DBatch batch = b.batch();
            return batch == null ? NullWorld3DBatch.INSTANCE : batch;
        }

        @Override
        public int viewportWidth() {
            World3DBackend b = backend;
            return b == null ? 0 : b.viewportWidth();
        }

        @Override
        public int viewportHeight() {
            World3DBackend b = backend;
            return b == null ? 0 : b.viewportHeight();
        }
    }
}
