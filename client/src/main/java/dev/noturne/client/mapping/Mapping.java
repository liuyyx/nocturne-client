package dev.noturne.client.mapping;

/**
 * 将规范（Mojang）名称翻译为运行中的 JVM 实际暴露的名称。
 *
 * <p>计划有两种实现：用于未混淆构建（26.1+）的恒等映射，以及用于混淆构建
 * （1.8.9 – 1.21.x）的表驱动映射。模块只针对规范名编写，因而无需修改即可在两种构建上运行。
 *
 * <p>解析契约：所有查询方法<b>永不返回 {@code null}</b>——查不到时必须回退为规范名，
 * 让反射仍有机会命中可读名；仅 {@link #methodDescriptor(ClassType, String)} 例外，
 * 它以 {@code null} 表示“表中无描述符记录”。
 */
public interface Mapping {

    /** 当前映射的人类可读描述，例如 {@code identity} 或 {@code mojmap-1.21.4}。 */
    String describe();

    /** 查询运行期类名；表未收录时回退为 {@link ClassType#canonicalName()}。 */
    String className(ClassType type);

    /**
     * 查询运行期方法名；表未收录时回退为 {@code canonicalName}。
     *
     * @param descriptor 该方法的 JNI 描述符，用于同名重载的消歧；实现可忽略此参数
     */
    String methodName(ClassType owner, String canonicalName, String descriptor);

    /** 查询运行期字段名；表未收录时回退为 {@code canonicalName}。 */
    String fieldName(ClassType owner, String canonicalName);

    /**
     * 表中记录的某方法的 JNI 描述符（若存在）。
     *
     * <p>之所以需要它，是因为混淆构建会复用短方法名，描述符才是区分它们的依据。
     *
     * <p><b>三态契约</b>：返回 {@code null} 只表示「表中<b>没有</b>该方法的描述符记录」，绝不表示
     * 「零参方法」——零参方法在表里是一条非空的 {@code ()...} 描述符，与「未知」天然可区分。
     * 调用方因此<b>不得</b>把 {@code null} 当作空参数组使用；应先询问 {@link #hasMethodDescriptor}
     * 判断「表中是否确实有描述符」，没有时再按实参类型推导形参（H-35）。恒等映射永远返回
     * {@code null}，其 {@link #hasMethodDescriptor} 恒为 {@code false}。
     */
    default String methodDescriptor(ClassType owner, String canonicalName) {
        return null;
    }

    /**
     * 表中是否确实为某方法记录了 JNI 描述符。
     *
     * <p>把 {@link #methodDescriptor} 的「未知」与「零参」两种含义显式拆开：只有本方法返回
     * {@code true} 时，{@link #methodDescriptor} 的返回值才可用于挑选重载；返回 {@code false}
     * 时调用方应按实参类型推导（未混淆构建），而不是退化成无参查找。默认实现以
     * {@code methodDescriptor != null} 判定，恒等映射沿用默认即恒为 {@code false}。
     *
     * @param owner         声明该方法的类
     * @param canonicalName 未混淆的规范方法名
     * @return 表中是否有该方法的描述符记录
     */
    default boolean hasMethodDescriptor(ClassType owner, String canonicalName) {
        return methodDescriptor(owner, canonicalName) != null;
    }

    /** 恒等映射返回 true，供调用方走「无需查表」的快路径。 */
    boolean isIdentity();
}
