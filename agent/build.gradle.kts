// agent 模块构建脚本：被注入进目标 JVM 的 Java agent（premain / agentmain）。
// 其 jar 清单中的 Premain-Class / Agent-Class 是 attach 与 -javaagent 两条注入路径的入口。

plugins {
    // 仅应用 Java 插件；agent 机制本身是 JDK 内置能力，无需外部插件。
    java
}

// ---------------------------------------------------------------------------
// 编译期依赖的外部 jar 路径解析。
//
// mixin 与 Minecraft 的 jar 都在本机 .minecraft 里，无法用 Maven 坐标稳定引用（版本随实例变）。
// 找不到时退回一个不存在的路径：编译会失败并给出明确原因，而不是静默跳过。
// ---------------------------------------------------------------------------
val minecraftHome = System.getProperty("user.home") + "/AppData/Roaming/.minecraft"
val mixinJar = fileTree("$minecraftHome/libraries/net/fabricmc/sponge-mixin") {
    include("**/sponge-mixin-*.jar")
}.files.maxByOrNull { it.lastModified() } ?: file("$minecraftHome/missing-sponge-mixin.jar")
val fabricLoaderJar = fileTree("$minecraftHome/libraries/net/fabricmc/fabric-loader") {
    include("**/fabric-loader-*.jar")
}.files.maxByOrNull { it.lastModified() } ?: file("$minecraftHome/missing-fabric-loader.jar")
val asmTreeJar = fileTree("$minecraftHome/libraries/org/ow2/asm/asm-tree") {
    include("**/asm-tree-*.jar")
}.files.maxByOrNull { it.lastModified() } ?: file("$minecraftHome/missing-asm-tree.jar")
val mcJar = fileTree("$minecraftHome/versions") {
    include("**/.fabric/remappedJars/*/client-intermediary.jar")
}.files.maxByOrNull { it.lastModified() } ?: file("$minecraftHome/missing-client-intermediary.jar")

dependencies {
    // 客户端核心与 UI 会被 agent 一并带入目标 JVM。
    implementation(project(":client"))
    implementation(project(":ui"))
    // core：载荷容器（PayloadPack/PayloadLoader）与密钥派生——内嵌 ASM 以加密载荷形式分发，
    // agent 侧解包后再内存加载，因此 agent 与 core 共用同一份实现（不允许两边各写一遍加解密）。
    implementation(project(":core"))
    // ASM：字节码改写（帧钩子 + GUI 绘制钩子）。版本必须跟得上目标游戏的 class 版本：
    // 26.3 是 Java 25（class 69），9.7.1 只能读到 Java 24，会对 Hud.extractRenderState 直接抛
    // 「Unsupported class file major version 69」，钩子静默失效。
    implementation("org.ow2.asm:asm:9.8")
    // Mixin：Fabric 环境下唯一能可靠注入游戏类的通道。KnotClassLoader 用 defineClass 直接
    // 定义游戏类，Instrumentation 的 retransform/redefine 对它管理的类都不可靠（三条路都实测过）。
    // 只在编译期需要（运行期的 mixin 由 Fabric 自己提供）。
    compileOnly(files(mixinJar))
    // Mixin 的 IMixinConfigPlugin.postApply 直接给 ASM ClassNode，需要 asm-tree。
    // 运行期由 Fabric loader 内自带的那份提供，不进交付 jar。
    compileOnly(files(asmTreeJar))
    // Fabric loader：mod 入口需要 ClientModInitializer。运行期由 Fabric 提供。
    compileOnly(files(fabricLoaderJar))
    // Minecraft intermediary jar：mixin 类要引用目标类型（class_437/class_332），编译期必须有。
    compileOnly(files(mcJar))
    // JUnit 5：单元测试编译期依赖。
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    // JUnit Platform 启动器：测试运行时依赖。
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

    // 写入 agent 清单：Premain-Class 支持启动期注入，Agent-Class 支持运行期 attach。
tasks.jar {
    manifest {
        attributes(
            "Premain-Class" to "dev.nocturne.agent.NocturneAgent",
            "Agent-Class" to "dev.nocturne.agent.NocturneAgent",
            // 允许重转换/重定义类，是 attach 之后还能二次挂钩的前提。
            "Can-Retransform-Classes" to "true",
            "Can-Redefine-Classes" to "true",
        )
    }
}
