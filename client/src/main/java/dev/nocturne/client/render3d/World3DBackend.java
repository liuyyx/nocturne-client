package dev.nocturne.client.render3d;

/**
 * 世界空间 3D 渲染后端：由 ui 模块按「当前绘制栈」装配，client 侧只认这个接口。
 *
 * <p>为什么后端不放在 client：GL 绑定必须通过游戏类加载器反射取得（本模块不能编译期依赖
 * LWJGL），而「哪个绘制栈可用」属于 ui 的选择结果。client 只负责相机、模块遍历与能力状态。
 *
 * <p>实现约定：
 * <ul>
 *   <li>{@link #begin(WorldCamera)} 设置自己的投影/模型视图矩阵与状态，返回 {@code false}
 *       表示这一帧没有 GL 上下文（例如窗口最小化），调用方必须直接跳过 {@link #end()}；</li>
 *   <li>{@link #end()} 必须把进入前的 GL 状态与矩阵完整还原（固定管线用属性栈 + 矩阵栈），
 *       否则 HUD/游戏后续绘制的颜色、深度、纹理缓存会串味；</li>
 *   <li>同一时刻只有一个线程使用；不得在 {@code begin}/{@code end} 之外调用批次。</li>
 * </ul>
 */
public interface World3DBackend {

    /** @return 后端名（用于能力矩阵与日志，例如 {@code gl-fixed}） */
    String backendName();

    /** @return 本进程里该后端是否具备完整能力（缺句柄时必须返回 {@code false}） */
    boolean available();

    /** @return 不可用原因；可用时为 {@code null} */
    String unavailableReason();

    /**
     * 进入一帧世界空间绘制。
     *
     * @param camera 当帧相机（非 null，且 {@link WorldCamera#valid()} 为真）
     * @return 是否成功进入；{@code false} 时不得调用 {@link #end()} 或批次
     */
    boolean begin(WorldCamera camera);

    /** 退出本帧绘制并还原所有状态；必须与成功的 {@link #begin(WorldCamera)} 成对调用。 */
    void end();

    /** @return 当帧批次；只在成功的 {@code begin()} 之后非 null */
    World3DBatch batch();

    /** @return 本帧帧缓冲宽度（像素）；未进入时返回 0 */
    int viewportWidth();

    /** @return 本帧帧缓冲高度（像素）；未进入时返回 0 */
    int viewportHeight();
}
