import org.gradle.api.tasks.SourceSetContainer

// dist 模块构建脚本：把各模块与第三方依赖合并为单个多入口 noturne jar。
// 该 jar 同时充当注入器 GUI、Java agent 与模组，无需按加载器拆分发包。

plugins {
    // 仅应用 Java 插件；合并与清单写入全部通过自定义 Jar 任务完成。
    java
}

    // 以下 project 依赖保证 dist 的 classes 任务依赖各模块，并让其产物进入合并范围。
dependencies {
    // core：注入/加载逻辑与可执行入口。
    implementation(project(":core"))
    // agent：premain / agentmain 入口。
    implementation(project(":agent"))
    // client：客户端核心。
    implementation(project(":client"))
    // ui：ClickGUI 与 HUD。
    implementation(project(":ui"))
    // injector：注入器 GUI。
    implementation(project(":injector"))
}

/** 在加载器元数据模板中展开 ${version} 占位符，使 fabric.mod.json / mods.toml
 * 中的版本号随项目版本自动同步。 */
tasks.processResources {
    // 仅对这三种模组描述文件做替换，其他资源（如图标、mixins 配置）保持原样。
    filesMatching(listOf("fabric.mod.json", "META-INF/mods.toml", "META-INF/neoforge.mods.toml")) {
        expand("version" to project.version.toString())
    }
}

/**
 * 最终交付物：一个同时具备三种身份的 jar：
 *   - 注入器 GUI（双击启动：清单中的 Main-Class）
 *   - agent      （Premain-Class / Agent-Class，被注入到运行中的 JVM）
 *   - 模组       （classpath 上的 fabric.mod.json / mods.toml / neoforge.mods.toml）
 *
 * <p>模组加载器的桩代码位于独立 source set，刻意不包含在内。
 */
val distJar = tasks.register<Jar>("distJar") {
    // 归入 build 分组，便于与普通 jar 区分。
    group = "build"
    // 任务说明：用于 `./gradlew :dist:distJar` 的输出提示。
    description = "Assembles the single multi-entry noturne jar."
    // 产物名带版本号，启动器据此按「取最新 jar」的策略选择客户端。
    archiveFileName.set("noturne-${project.version}.jar")
    // 各模块资源可能重名（如同一 mixin 配置），保留先遇到的一份即可，避免打包失败。
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    // 清单同时声明三种入口，使一个 jar 能走 GUI、attach 与模组加载三条路径。
    manifest {
        attributes(
            // Main-Class：双击启动的 GUI 入口。
            // Premain-Class / Agent-Class：agent 注入入口。
            "Main-Class" to "dev.noturne.injector.InjectorApp",
            "Premain-Class" to "dev.noturne.agent.NoturneAgent",
            "Agent-Class" to "dev.noturne.agent.NoturneAgent",
            "Can-Retransform-Classes" to "true",
            "Can-Redefine-Classes" to "true",
            // 实现元信息，便于排查产物版本对应关系。
            "Implementation-Title" to "noturne-client",
            "Implementation-Version" to project.version.toString(),
        )
    }

    // 先并入本模块自身的 class 与资源输出。
    from(sourceSets.main.get().output)

    // 打包第三方运行时依赖（帧钩子用的 ASM、映射用的 gson），
    // 使交付 jar 自包含，可直接投放到任意 JVM。
    // 过滤掉目录形态的依赖，只解包 jar 形态，否则 zipTree 会在目录上报错。
    from(configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }) {
        // 排除必须挂在这一次 from 上：顶层 exclude 对 zipTree 展开出来的内容不生效。
        exclude("org/objectweb/asm/**", "org/spongepowered/**", "com/llamalad7/**")
    }

    // 逐个并入各业务模块的 main 输出，并显式 dependsOn 其 classes 任务以保证构建顺序。
    listOf(":core", ":agent", ":client", ":ui", ":injector").forEach { path ->
        val output = project(path).extensions.getByType<SourceSetContainer>()["main"].output
        dependsOn(project(path).tasks.named("classes"))
        from(output)
    }

    // 依赖 jar 里的模块描述符与多版本目录必须排除：它们会让 javac 把这个 jar 当成模块处理，
    // 于是任何「以本 jar 为 classpath」的编译都会报「程序包 xxx 不存在」。
    exclude("module-info.class", "META-INF/versions/**")

    // 字节码操作库（ASM / Mixin / MixinExtras）必须排除：加载器自己也带这些库，
    // 而 Fabric 的 KnotClassLoader 会先在 mods jar 里找类——于是同一个 org.objectweb.asm.MethodVisitor
    // 被两个加载器各加载一次，MixinExtras 与 sponge-mixin 拿到的类型对不上，直接 VerifyError 崩溃。
    // 排除后：模组路径用加载器自带的版本；agent 路径若目标 JVM 没有 ASM，
    // 帧钩子会优雅降级（只记录日志），不影响其余功能。
    exclude("org/objectweb/asm/**", "org/spongepowered/**", "com/llamalad7/**")
}

tasks.named("assemble") {
    dependsOn(distJar)
}
