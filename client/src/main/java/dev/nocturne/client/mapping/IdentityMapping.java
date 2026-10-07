package dev.nocturne.client.mapping;

/**
 * 未混淆构建（Minecraft 26.1+）的映射：规范名即运行期名。
 *
 * <p>方法与字段的签名对 JNI 依然重要，但它们是通过 {@code java.lang.Class} 反射惰性
 * 发现的，而非查表得到，因此这里无需做任何翻译。
 *
 * <p>命名空间候选（{@link Mapping#classNameCandidates} / {@link Mapping#methodCandidates}）
 * 也无需覆写：接口默认实现会把候选退化为「唯一的一项 = 规范名」，描述符为 {@code null}，
 * 调用方据此走按实参类型推断形参的路径——正是未混淆构建该有的行为。
 */
public final class IdentityMapping implements Mapping {

    /** 返回用于日志展示的映射描述。 */
    @Override
    public String describe() {
        return "identity (unobfuscated build)";
    }

    /** 直接返回 {@link ClassType#canonicalName()}。 */
    @Override
    public String className(ClassType type) {
        return type.canonicalName();
    }

    /** 直接返回规范方法名；{@code owner} 与 {@code descriptor} 均被忽略。 */
    @Override
    public String methodName(ClassType owner, String canonicalName, String descriptor) {
        return canonicalName;
    }

    /** 直接返回规范字段名；{@code owner} 被忽略。 */
    @Override
    public String fieldName(ClassType owner, String canonicalName) {
        return canonicalName;
    }

    /** 恒为 true，调用方可据此跳过映射解析路径。 */
    @Override
    public boolean isIdentity() {
        return true;
    }
}
