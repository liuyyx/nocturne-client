package dev.nocturne.client.mapping;

/**
 * 一个「方法候选」：某命名空间下的运行期方法名，以及该命名空间配套记录的 JNI 描述符。
 *
 * <p>为什么名与描述符必须成对：同一个规范方法在不同运行期命名空间下的名字与描述符是<b>配套</b>的
 * ——原版混淆名 {@code A} 只在原版混淆构建里存在，其描述符 {@code ()Lave;} 里的类名同样只在那个
 * 构建里存在；换成 Forge 的 SRG 名 {@code m_91087_}，描述符也得跟着换成
 * {@code ()Lnet/minecraft/client/Minecraft;}。拿一个命名空间的名字去配另一个命名空间的描述符，
 * 参数类型解析必然失败（见 {@code dev.nocturne.client.game.JniTypes}），整条调用链会静默落空。
 *
 * <p>不可变；{@code name} 不允许为 {@code null}（候选列表至少要有名字可试），
 * {@code descriptor} 允许为 {@code null}，表示「该命名空间没有记录描述符」——调用方此时应按实参
 * 类型推断形参，而不是把 {@code null} 当作零参方法。
 */
public final class MethodCandidate {

    /** 运行期方法名；永不为 {@code null}。 */
    private final String name;

    /** 与该名字配套的 JNI 描述符；表未记录时为 {@code null}。 */
    private final String descriptor;

    /**
     * 构造一个方法候选。
     *
     * @param name       运行期方法名，不可为 {@code null}
     * @param descriptor 配套的 JNI 描述符，可为 {@code null}（表示该命名空间无描述符记录）
     * @throws NullPointerException {@code name} 为 {@code null} 时抛出
     */
    public MethodCandidate(String name, String descriptor) {
        if (name == null) {
            throw new NullPointerException("method candidate name must not be null");
        }
        this.name = name;
        this.descriptor = descriptor;
    }

    /** @return 运行期方法名，永不为 {@code null} */
    public String name() {
        return name;
    }

    /** @return 与 {@link #name()} 配套的 JNI 描述符；无记录时为 {@code null} */
    public String descriptor() {
        return descriptor;
    }

    /** 名字与描述符都相等才视为同一条候选。 */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MethodCandidate)) {
            return false;
        }
        MethodCandidate candidate = (MethodCandidate) other;
        if (!name.equals(candidate.name)) {
            return false;
        }
        return descriptor == null ? candidate.descriptor == null : descriptor.equals(candidate.descriptor);
    }

    /** 与 {@link #equals(Object)} 一致：名字与描述符共同参与哈希。 */
    @Override
    public int hashCode() {
        return name.hashCode() * 31 + (descriptor == null ? 0 : descriptor.hashCode());
    }

    /** 形如 {@code A()Lave;} 或 {@code m_91087_(descriptor=unknown)} 的诊断串。 */
    @Override
    public String toString() {
        return descriptor == null ? name + "(descriptor=unknown)" : name + descriptor;
    }
}
