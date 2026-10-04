// injector 模块构建脚本：注入器 GUI（Swing 前端）的依赖声明。
// 该模块提供 Main-Class，双击即可启动；dist 会把它与 agent、client、ui 合并进同一个 jar。

plugins {
    // 仅应用 Java 插件；GUI 后端为 JDK 自带的 Swing。
    java
}

dependencies {
    // 依赖 core 以复用进程枚举、载荷解密与注入逻辑，避免 GUI 层重复实现。
    implementation(project(":core"))
    // FlatLaf 提供深色主题，并且关键在于它会跟随系统 DPI 缩放字体与度量；
    // 应用内的 UIScale 缩放滑块则基于它继续叠加倍率。
    implementation("com.formdev:flatlaf:3.5.4")
    // 本模块所有布局均使用 MigLayout，不存在任何绝对定位。
    implementation("com.miglayout:miglayout-swing:11.4.2")
    // gson：GUI 与 core 之间传递的 JSON 配置与进程列表。
    implementation("com.google.code.gson:gson:2.10.1")
    // JUnit 5：快捷键换算表（KeyCodes）这类纯逻辑的单元测试，不需要 Swing 环境。
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    // JUnit Platform 启动器：测试运行时依赖，缺失会导致 test 任务无法发现用例。
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}
