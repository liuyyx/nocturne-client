// Gradle 设置脚本：声明插件与依赖仓库来源，并登记全部子模块。
// 本文件不参与编译产物，只决定「依赖从哪拉」和「工程由哪些模块组成」。

pluginManagement {
    // 插件解析仓库：Gradle 插件标记只在 Plugin Portal 与 Maven Central 中分发。
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // 依赖仓库模式：PREFER_SETTINGS 表示子模块中出现的 repositories 会被忽略，
    // 强制所有模块统一从这里取依赖，避免版本来源分裂。
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        // Fabric：提供 Fabric Loader / Yarn / Fabric API 的构件。
        maven("https://maven.fabricmc.net/")
        // NeoForged：NeoForge 及其生态（Mojang 映射、启动相关构件）。
        maven("https://maven.neoforged.net/releases/")
        // Forge：老版本 Forge 依赖，用于 1.8.9 等老客户端的映射与类解析。
        maven("https://maven.minecraftforge.net/")
    }
}

// 根项目名，决定构建产物名与 IDE 中的工程显示名。
rootProject.name = "nocturne-client"
// 下方各行为模块职责速览（实现细节见各自模块内的文件）：

// core   —— 注入器/加载器（自实现 attach、载荷解密与装载、单 jar 多入口）
// agent  —— 被注入进目标 JVM 的 agent（premain/agentmain）
// client —— 客户端核心（事件总线、模块/值框架、映射与跨版本适配）
// ui     —— ClickGUI / HUD（Epsilon 风格）
// injector —— 注入器 GUI（Swing 前端，提供 Main-Class）
// dist    —— 把上述模块合并为单个多入口 nocturne jar
include(
    "core",
    "agent",
    "client",
    "ui",
    "injector",
    "dist",
)
