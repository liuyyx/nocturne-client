// 隔离编译探针：编译 vendor/setsuna 下原样照抄的 Setsuna 源码。
// 不修改 vendor/setsuna/**，所有补充都放在本工程自己的 src/ 与 libs/。
plugins {
    java
}

val userHome = System.getProperty("user.home")
val mcRoot = "$userHome/AppData/Roaming/.minecraft"
val mcLibs = "$mcRoot/libraries"
val mcVers = "$mcRoot/versions"
val mcClientJar = file("../../../analysis/client-26.1.2.jar")

// 26.1.2 对应版本的 fabric-api（拆出嵌套模块 jar，供编译期直接可见）。
val fabricApi26Dir = file("$mcVers/26.1.2-epsilon/mods")
val fabricApiNested = fileTree("libs/fabric-api") { include("*.jar") }

repositories {
    mavenCentral()
    maven("https://jitpack.io")
    maven("https://maven.fabricmc.net/")
    maven("https://repo.spongepowered.org/repository/maven-public")
    maven("https://maven.caffeinemc.net/releases")
    maven("https://repo.viaversion.com")
    maven("https://maven.lenni0451.net/everything")
}

sourceSets {
    named("main") {
        java.srcDirs(
            // Setsuna 原样照抄的源码（package com.setsuna.*）
            "../setsuna",
            // 本工程自己的桩文件（禁止改动 vendor/setsuna，缺什么补这里）
            "src/stub/java"
        )
    }
}

dependencies {
    // —— Minecraft 26.1.2 客户端（已重映射到 Mojang 官方名，含 net.minecraft.* / com.mojang.blaze3d.*）——
    if (mcClientJar.isFile) {
        implementation(files(mcClientJar))
    } else {
        logger.lifecycle("WARN: missing MC client jar at ${mcClientJar.absolutePath}")
    }

    // —— 本地 .minecraft/libraries 全量（sponge-mixin / lwjgl / mojang / guava / netty / fastutil ...）——
    // 排除其它 MC 版本的映射产物：它们含 net/minecraft/** 会遮蔽 26.1.2 的 split package，
    // 导致 javac 只在第一个含该包的 classpath 条目里找类（找不到 GuiGraphicsExtractor / Model 泛型）。
    implementation(fileTree(mcLibs) {
        include("**/*.jar")
        exclude("net/minecraft/**")
        exclude("net/minecraftforge/**")
        exclude("net/neoforged/**")
        exclude("net/fabricmc/intermediary/**")
        exclude("net/fabricmc/mapping-io/**")
    })

    // —— fabric-api 26.1.2 嵌套模块（ClientLifecycleEvents / ClientPlayNetworking / renderer v1 ...）——
    implementation(fabricApiNested)
    // fabric-api 胖 jar 本体（可能含非嵌套入口类）
    implementation(fileTree(fabricApi26Dir) { include("fabric-api-*.jar") })

    // —— Setsuna 上游显式依赖 ——
    implementation("io.github.humbleui:skija-windows-x64:0.143.17")
    implementation("io.github.humbleui:skija-shared:0.143.17")
    implementation("org.luaj:luaj-jse:3.0.1")
    compileOnly("io.github.llamalad7:mixinextras-common:0.5.3")
    annotationProcessor("io.github.llamalad7:mixinextras-common:0.5.3")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.joml:joml:1.10.8")
    implementation("com.google.zxing:core:3.5.1")
    implementation("org.jetbrains:annotations:24.1.0")
    implementation("org.jspecify:jspecify:1.0.0")
    implementation("com.google.code.findbugs:jsr305:3.0.2")
    implementation("org.checkerframework:checker-qual:3.42.0")
    compileOnly("net.caffeinemc:sodium-fabric:0.8.12+mc26.1.2")
    implementation("com.github.FPSMasterTeam:Cadence:v0.1.1") {
        exclude(group = "com.google.code.gson", module = "gson")
    }

    // Lombok：Setsuna 大量使用 @Getter/@Setter/@RequiredArgsConstructor 等
    compileOnly("org.projectlombok:lombok:1.18.42")
    annotationProcessor("org.projectlombok:lombok:1.18.42")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
    // 编译探针：不因缺少注解处理器产生的告警失败
    options.compilerArgs.addAll(listOf("-nowarn", "-Xlint:none", "-Xmaxerrs", "100000"))
}

// 记录编译用的 classpath，便于人工核查
tasks.register("dumpClasspath") {
    doLast {
        val cp = sourceSets["main"].compileClasspath.asPath
        file("build/compile-classpath.txt").writeText(cp.replace(';', '\n'))
        println("classpath entries: " + cp.split(';').size)
    }
}
