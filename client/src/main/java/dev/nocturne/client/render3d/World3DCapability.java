package dev.nocturne.client.render3d;

/**
 * 世界空间 3D 能力：给模块/GUI/诊断用的只读结论，绝不谎报「可用」。
 *
 * <p>三个字段分别回答：能不能画、走的哪个后端、不能画的确切原因。现代版本在 Phase 1 里必然
 * {@code available=false}，原因必须具体到「缺钩子」还是「缺后端」，而不是一句「不支持」。
 */
public final class World3DCapability {

    private final boolean available;
    private final String backend;
    private final String reason;

    public World3DCapability(boolean available, String backend, String reason) {
        this.available = available;
        this.backend = backend == null ? "none" : backend;
        this.reason = reason;
    }

    /** @return 当前进程能否真正画世界空间 3D 几何 */
    public boolean available() {
        return available;
    }

    /** @return 后端名（{@code gl-fixed} / {@code gl-core} / {@code gui-graphics} …） */
    public String backend() {
        return backend;
    }

    /** @return 不可用原因；{@code available=true} 时为 {@code null} */
    public String reason() {
        return reason;
    }

    /** @return 一行诊断文本（日志 / GUI 提示用） */
    public String describe() {
        return available ? ("world3d available via " + backend)
                : ("world3d unavailable (" + backend + "): " + reason);
    }
}
