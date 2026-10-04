// client 模块构建脚本：客户端核心（事件总线、模块/值框架、映射与跨版本适配）。
// 本模块会被 agent 引入，运行在目标 JVM 内部，故字节码版本由根脚本统一压到 Java 8。

plugins {
    // 仅应用 Java 插件；不依赖任何加载器（Fabric/Forge/NeoForge），保持加载器无关。
    java
}

dependencies {
    // gson：映射表与配置文件的 JSON 读写。
    implementation("com.google.code.gson:gson:2.10.1")
    // JUnit 5：单元测试编译期依赖。
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    // JUnit Platform 启动器：测试运行时依赖。
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}
