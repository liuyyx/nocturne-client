// ui 模块构建脚本：ClickGUI 与 HUD（Epsilon 风格）的构建定义。
// 仅在 agent 侧被引用，因此不对外暴露为独立运行时依赖。

plugins {
    // 仅应用 Java 插件；GUI 框架（Swing）由 JDK 自带，无需额外依赖。
    java
}

dependencies {
    // 依赖 client 核心：事件总线与模块/值框架由 UI 直接消费。
    implementation(project(":client"))
    // JUnit 5：单元测试编译期依赖。
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    // JUnit Platform 启动器：测试运行时依赖。
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}
