package dev.nocturne.client.mapping;

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
     * 该类在运行期**可能使用的全部候选名**，按尝试优先级排列（去重）。
     *
     * <p>为什么需要它：同一份映射表要同时覆盖四种运行期命名空间——原版混淆名（1.8.9 的
     * {@code ave}）、Fabric 的 intermediary（{@code net/minecraft/class_1657}）、Forge 的 SRG
     * （类名可读、成员 {@code func_/field_}）、NeoForge（1.20.2+ 即 Mojmap 恒等）。只取其中一个
     * 会让另外几种环境按名找不到类，症状看起来毫不相关（GUI 一个字不显示、ESP 永远为空……）。
     *
     * <p>调用方应逐个尝试 {@code Class.forName}，第一个成功者胜出。
     *
     * <p>默认实现只返回 {@link #className} 一个名字，保持既有行为；{@link ObfuscatedMapping} 会按表里
     * 记录的命名空间展开成有序候选，并在末尾追加规范名兜底。
     * 列表<b>至少</b>有一个元素，且首项恒等于 {@link #className}(type)。
     *
     * @param type 要翻译的类型
     * @return 候选运行时类名列表（至少一个元素，首项为 {@link #className}）
     */
    default java.util.List<String> classNameCandidates(ClassType type) {
        return java.util.Collections.singletonList(className(type));
    }

    /**
     * 查询运行期方法名；表未收录时回退为 {@code canonicalName}。
     *
     * @param descriptor 该方法的 JNI 描述符，用于同名重载的消歧；实现可忽略此参数
     */
    String methodName(ClassType owner, String canonicalName, String descriptor);

    /** 查询运行期字段名；表未收录时回退为 {@code canonicalName}。 */
    String fieldName(ClassType owner, String canonicalName);

    /**
     * 该字段在运行期**可能使用的全部候选名**，按尝试优先级排列（去重）。
     *
     * <p>为什么需要它：同一个字段在四种环境下有四种名字——原版混淆名（1.8.9 的 {@code h}）、
     * Fabric 的 intermediary（{@code field_1724}）、Forge 等重映射环境的 SRG 名
     * （{@code f_91073_}）、以及未混淆构建的规范名（{@code player}）。只取其中一个
     * 会让另外几种环境按名访问全部落空（读字段拿到 null、写字段静默失败），而症状看起来毫不相关
     * （字体绑不上、拖 GUI 时视角跟着转……）。
     *
     * <p>默认实现只返回 {@link #fieldName} 一个名字，保持既有行为；
     * {@link ObfuscatedMapping} 会按表里记录的命名空间（vanilla → fabric → forge → neoforge）
     * 展开为有序候选，并在末尾追加规范名兜底。
     *
     * @param owner         声明该字段的类
     * @param canonicalName 未混淆的规范字段名
     * @return 候选名列表（至少一个元素）
     */
    default java.util.List<String> fieldNameCandidates(ClassType owner, String canonicalName) {
        return java.util.Collections.singletonList(fieldName(owner, canonicalName));
    }

    /**
     * 该方法在运行期可能使用的全部候选名，按尝试优先级排列（去重）。
     *
     * @param owner         声明该方法的类
     * @param canonicalName 未混淆的规范方法名
     * @param descriptor    该方法的 JNI 描述符（消歧用，可为 {@code null}）
     * @return 候选名列表（至少一个元素）
     * @see #fieldNameCandidates(ClassType, String)
     */
    default java.util.List<String> methodNameCandidates(ClassType owner, String canonicalName,
                                                       String descriptor) {
        return java.util.Collections.singletonList(methodName(owner, canonicalName, descriptor));
    }

    /**
     * 该方法在运行期可能使用的全部候选（名字 + 配套描述符），按尝试优先级排列（去重）。
     *
     * <p>与方法名候选 {@link #methodNameCandidates} 的区别只有一个，但对混淆构建至关重要：
     * 这里把<b>描述符也和名字成对带上</b>。跨命名空间（原版混淆 / intermediary / SRG / Mojmap）
     * 时，名字与描述符必须来自同一个命名空间——用原版混淆名 {@code A} 去配 SRG 描述符里的类名，
     * 参数类型根本解析不出来。
     *
     * <p>默认实现按 {@link #methodNameCandidates} 的顺序组装，描述符统一取
     * {@link #methodDescriptor}（对恒等映射恒为 {@code null}，调用方据此按实参类型推断）。
     * {@link ObfuscatedMapping} 会为每个候选带上该命名空间自己的描述符。
     *
     * <p>返回的列表<b>至少</b>有一个元素。
     *
     * @param owner         声明该方法的类
     * @param canonicalName 未混淆的规范方法名
     * @return 候选列表（至少一个元素）
     */
    default java.util.List<MethodCandidate> methodCandidates(ClassType owner, String canonicalName) {
        String descriptor = methodDescriptor(owner, canonicalName);
        java.util.List<String> names = methodNameCandidates(owner, canonicalName, descriptor);
        java.util.List<MethodCandidate> candidates =
                new java.util.ArrayList<MethodCandidate>(names.size());
        for (String name : names) {
            candidates.add(new MethodCandidate(name, descriptor));
        }
        return candidates;
    }

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
