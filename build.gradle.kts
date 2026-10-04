// 根构建脚本：为所有子模块统一施加 Java 版本、编码与测试平台约定。
// 具体依赖与打包逻辑留在各模块的 build.gradle.kts 中。
plugins {
    // 根项目自身不产出 jar，仅应用 Java 插件以提供统一的编译约定入口。
    java
}

    // 全局坐标：group 决定 Maven 坐标前缀，version 参与所有子模块产物版本号。
allprojects {
    group = "dev.noturne"
    version = "0.1.0-SNAPSHOT"
}

/**
 * 凡是要运行在「目标 JVM 内部」的模块，字节码必须以 Java 8 为目标：
 * 客户端要支持 Minecraft 1.8.9（JVM 8）到 26.3（JVM 21+），
 * 而 agent 类若字节码版本高于目标 JVM，attach 时会被 UnsupportedClassVersionError 拒绝。
 * Java 8 字节码可在我们关心的所有 JVM 上运行，因此 release=8 是安全下限。
 */
subprojects {
    // 对每个子模块应用 Java 插件，使其拥有 compileJava / jar / test 等任务。
    apply(plugin = "java")

    // 编译所用的 JDK 工具链固定为 21：既满足新版 Minecraft 的运行时要求，
    // 又靠下面的 release=8 向下输出兼容字节码，做到「新 JDK 编译、旧 JVM 运行」。
    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    // 统一编译参数：目标字节码版本固定为 8，源码编码固定 UTF-8（源码含中文注释）。
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(8)
        options.encoding = "UTF-8"
        // 在现代 JDK 上以 Java 8 为目标会提示 bootclasspath 未设置，
        // 这里显式关闭该条 -Xlint 警告，避免污染构建输出。
        options.compilerArgs.add("-Xlint:-options")
    }

    // 全部子模块的测试统一使用 JUnit Platform 运行器。
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}

// 根项目没有源码：默认的 jar 任务会产出只含 META-INF 的空壳（约 261 字节），
// 极易被误当成可用的客户端 jar（双击无反应、放进 mods 也无效）。这里禁用它，
// 并把真正的交付 jar（dist 的多入口 jar）复制到根 build/libs/，
// 让 `build` 之后在惯常位置就能拿到可用产物。
tasks.named("jar") {
    enabled = false
}

val copyDistJar = tasks.register<Copy>("copyDistJar") {
    group = "build"
    description = "Copies the dist delivery jar into the root build/libs."
    dependsOn(":dist:distJar")
    from(project(":dist").layout.buildDirectory.file("libs/noturne-${version}.jar"))
    into(layout.buildDirectory.dir("libs"))
}

tasks.named("build") {
    dependsOn(copyDistJar)
}
