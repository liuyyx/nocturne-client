package net.neoforged.fml.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * NeoForge {@code @Mod} 注解的编译期桩。
 *
 * <p>仅供 agent 模块在编译期引用，不会被打包进产物：运行时由 NeoForge 提供同名真实注解，
 * 并按名字读取之。保留桩定义使 agent 模块无需依赖 NeoForge 构件，也能与加载器版本解耦。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Mod {

    /** 模组 ID；NeoForge 依此发现并实例化对应入口类。 */
    String value();
}
