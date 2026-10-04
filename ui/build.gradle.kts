// ui 模块构建脚本：ClickGUI 与 HUD（Epsilon 风格）的构建定义。
// 仅在 agent 侧被引用，因此不对外暴露为独立运行时依赖。

plugins {
    // 仅应用 Java 插件；GUI 框架（Swing）由 JDK 自带，无需额外依赖。
    java
}

dependencies {
    // 依赖 client 核心：事件总线与模块/值框架由 UI 直接消费。
    implementation(project(":client"))
    // Skija：Skia 的 JNI 绑定，用来画 GUI。选它的理由是「一份绘制代码管所有版本」：
    // 它对着**当前 GL 上下文**建 DirectContext，把界面直接画进游戏帧缓冲（不依赖游戏的绘制 API，
    // 也不依赖游戏字体）。skija-shared/types 是 Java 8 字节码，能进 1.8.9 的 JVM（已实测）。
    implementation("io.github.humbleui:skija-shared:0.143.17")
    implementation("io.github.humbleui:types:0.2.0")
    // 原生库按平台分发；这里先接 Windows（其余平台待补，见 docs/VERSION-MATRIX.md）。
    // 原生 dll 会被 dist 解包进产物 jar 的 io/github/humbleui/skija/<os>/<arch>/ 下，
    // 由 Skija 自己的 loader 在运行期找到（它会解压到临时目录，这一条已知并接受）。
    runtimeOnly("io.github.humbleui:skija-windows-x64:0.143.17")
    // JUnit 5：单元测试编译期依赖。
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    // JUnit Platform 启动器：测试运行时依赖。
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}
