// core 模块构建脚本：注入器/加载器的依赖与单 jar 入口声明。
// 该模块提供 dev.noturne.core.Noturne 作为可执行入口，同时被 dist 打包进最终产物。

plugins {
    // 仅应用 Java 插件，无需任何外部 Gradle 插件。
    java
}

dependencies {
    // gson：解析/生成配置与进程列表 JSON。
    implementation("com.google.code.gson:gson:2.10.1")
    // JUnit 5：单元测试编译期依赖。
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    // JUnit Platform 启动器：测试运行时依赖，缺失会导致 test 任务无法发现用例。
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

    // 在 jar 清单中写入入口类，使产物可直接 `java -jar` 运行（启动器即依赖这一点驱动本模块）。
tasks.jar {
    manifest {
        attributes(
            // Main-Class：GUI 双击启动入口；后两项为实现元信息，便于排查版本对应关系。
            "Main-Class" to "dev.noturne.core.Noturne",
            "Implementation-Title" to "noturne-client",
            "Implementation-Version" to project.version.toString(),
        )
    }
}
