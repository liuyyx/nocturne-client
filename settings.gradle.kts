pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.neoforged.net/releases/")
        maven("https://maven.minecraftforge.net/")
    }
}

rootProject.name = "noturne-client"

// core   —— 注入器/加载器（自实现 attach、载荷解密与装载、单 jar 多入口）
// agent  —— 被注入进目标 JVM 的 agent（premain/agentmain）
// client —— 客户端核心（事件总线、模块/值框架、映射与跨版本适配）
// ui     —— ClickGUI / HUD（Epsilon 风格）
include(
    "core",
    "agent",
    "client",
    "ui",
    "injector",
    "dist",
)
