// agent 模块构建脚本：被注入进目标 JVM 的 Java agent（premain / agentmain）。
// 其 jar 清单中的 Premain-Class / Agent-Class 是 attach 与 -javaagent 两条注入路径的入口。

plugins {
    // 仅应用 Java 插件；agent 机制本身是 JDK 内置能力，无需外部插件。
    java
}

/**
 * 模组加载器入口点的编译期桩（stub）。
 *
 * <p>刻意不打包进产物：真正的 {@code ModInitializer}/{@code @Mod} 由加载器在运行时提供，
 * 因此 noturne 无需依赖任何加载器，既避免版本冲突，也保持加载器无关（loader/version agnostic）。
 */
val modStubs by sourceSets.creating {
    // 桩代码位于独立目录，只参与编译，不进入最终 jar。
    java.srcDir("src/modStubs/java")
}

dependencies {
    // 客户端核心与 UI 会被 agent 一并带入目标 JVM。
    implementation(project(":client"))
    implementation(project(":ui"))
    // 桩以 compileOnly 引入：编译期需要，运行期由加载器提供，故不可打包。
    compileOnly(modStubs.output)
    // ASM：帧钩子所需的字节码改写（用于对目标方法织入 hook）。
    implementation("org.ow2.asm:asm:9.7.1")
    // JUnit 5：单元测试编译期依赖。
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    // JUnit Platform 启动器：测试运行时依赖。
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

    // 写入 agent 清单：Premain-Class 支持启动期注入，Agent-Class 支持运行期 attach。
tasks.jar {
    manifest {
        attributes(
            "Premain-Class" to "dev.noturne.agent.NoturneAgent",
            "Agent-Class" to "dev.noturne.agent.NoturneAgent",
            // 允许重转换/重定义类，是 attach 之后还能二次挂钩的前提。
            "Can-Retransform-Classes" to "true",
            "Can-Redefine-Classes" to "true",
        )
    }
}
