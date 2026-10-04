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
     * 恒等映射返回 {@code null}。
     */
    default String methodDescriptor(ClassType owner, String canonicalName) {
        return null;
    }

    /** 恒等映射返回 true，供调用方走「无需查表」的快路径。 */
    boolean isIdentity();
}
