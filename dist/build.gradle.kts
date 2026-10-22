import org.gradle.api.tasks.SourceSetContainer
import java.util.zip.ZipFile

// dist 模块构建脚本：把各模块与第三方依赖合并为单个多入口 nocturne jar。
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

// ---------------------------------------------------------------------------
// 内嵌 ASM 的加密载荷（P7-3）。
//
// ASM 不再以「原 jar 资源」内嵌，而是先用 core 的 PayloadPack 加密成 asm.pack，再由 agent 的
// EmbeddedAsmLoader 在内存里解包加载。打包必须走与运行期同一份实现，因此这里直接调用
// dev.nocturne.core.pack.PayloadTool，而不是在构建脚本里另写一套加解密。
// ---------------------------------------------------------------------------
val distAsmJar = configurations.runtimeClasspath.map { files ->
    files.firstOrNull { it.name.startsWith("asm-") && it.name.endsWith(".jar") }
        ?: throw GradleException("ASM jar not found on the dist runtime classpath")
}
val packedAsmDir = layout.buildDirectory.dir("packed-asm")
val packedAsm = packedAsmDir.map { it.file("asm.pack") }

val packAsmPayload = tasks.register<JavaExec>("packAsmPayload") {
    group = "build"
    description = "Packs the ASM jar into an encrypted payload resource for the agent."
    // 用 dist 自己的运行期 classpath：它已经含 :core（PayloadTool/PayloadPack/PayloadKey 都在这）。
    // 不能去解析 :core 的 configuration——Gradle 9 明确禁止跨项目做非独占锁的配置解析；
    // 这里用 files(...) 保持惰性，避免在配置阶段就把依赖图钉死。
    classpath = files(configurations.runtimeClasspath)
    mainClass.set("dev.nocturne.core.pack.PayloadTool")
    inputs.files(distAsmJar)
    outputs.file(packedAsm)
    doFirst {
        packedAsmDir.get().asFile.mkdirs()
        args("asm-pack", distAsmJar.get().absolutePath, packedAsm.get().asFile.absolutePath)
    }
}

/**
 * 最终交付物：一个同时具备两种身份的 jar：
 *   - 注入器 GUI（双击启动：清单中的 Main-Class）
 *   - agent      （Premain-Class / Agent-Class，被注入到运行中的 JVM）
 *
 * <p>只有注入这一条路径：不再产出加载器元数据，也不再有模组形态。
 */
/**
 * 预编译的 KeySink：作为**资源**（而不是编译产物）进交付 jar，路径 {@code nocturne-bootstrap/KeySink.class}。
 *
 * <p>agent 的 bootstrap bridge 会把它改写成 {@code dev/nocturne/bootstrap/KeySink.class} 打进
 * bootstrap 搜索路径；交付 jar 自身不含这个类，Knot 因此拿不到第二份。
 */
val bridgeKeySink = tasks.register<Copy>("bridgeKeySink") {
    group = "build"
    description = "Copies the prebuilt KeySink class as a bridge resource."
    // 取 client 的编译输出（agent 依赖 client，编译顺序由 Gradle 保证）。
    from(project(":client").layout.buildDirectory.file(
            "classes/java/main/dev/nocturne/bootstrap/KeySink.class"))
    into(layout.buildDirectory.dir("generated/bridge-resources/nocturne-bootstrap"))
    rename { "KeySink.class" }
    // 显式依赖 client 的编译输出（Gradle 不允许隐式使用其它任务的产物）。
    dependsOn(project(":client").tasks.named("compileJava"))
}

val distJar = tasks.register<Jar>("distJar") {
    // 归入 build 分组，便于与普通 jar 区分。
    group = "build"
    // 任务说明：用于 `./gradlew :dist:distJar` 的输出提示。
    description = "Assembles the single multi-entry nocturne jar."
    // 产物名带版本号，启动器据此按「取最新 jar」的策略选择客户端。
    archiveFileName.set("nocturne-${project.version}.jar")
    // 各模块资源可能重名（如同一 mixin 配置），保留先遇到的一份即可，避免打包失败。
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    // 清单同时声明三种入口，使一个 jar 能走 GUI、attach 与模组加载三条路径。
    manifest {
        attributes(
            // Main-Class：双击启动的 GUI 入口。
            // Premain-Class / Agent-Class：agent 注入入口。
            "Main-Class" to "dev.nocturne.injector.InjectorApp",
            "Premain-Class" to "dev.nocturne.agent.NocturneAgent",
            "Agent-Class" to "dev.nocturne.agent.NocturneAgent",
            "Can-Retransform-Classes" to "true",
            "Can-Redefine-Classes" to "true",
            // 实现元信息，便于排查产物版本对应关系。
            "Implementation-Title" to "nocturne-client",
            "Implementation-Version" to project.version.toString(),
        )
    }

    // KeySink 只能由 bootstrap bridge 提供：顶层再排除一次，确保任何来源（工程产物或依赖解包）
    // 都不会把编译版带进交付 jar。
    exclude("dev/nocturne/bootstrap/**")
    // 先并入本模块自身的 class 与资源输出。
    from(sourceSets.main.get().output)
    // 预编译的 KeySink（仅作为资源，见 bridgeKeySink）。
    dependsOn(bridgeKeySink)
    from(layout.buildDirectory.dir("generated/bridge-resources"))

    // 打包第三方运行时依赖（帧钩子用的 ASM、映射用的 gson），
    // 使交付 jar 自包含，可直接投放到任意 JVM。
    // 过滤掉目录形态的依赖，只解包 jar 形态，否则 zipTree 会在目录上报错。
    from(configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }) {
        // 这里排除的是「zipTree 展开出来的类文件」：CopySpec 的 exclude 会沿 spec 树继承，
        // 因此对解包内容同样生效。ASM 的类不进 jar 根，改以加密载荷作为资源内嵌（见下方）。
        exclude("org/objectweb/asm/**", "org/spongepowered/**", "com/llamalad7/**")
        // KeySink 必须是「全局唯一」：只能由 bootstrap bridge 提供。若交付 jar 里也有它，
        // Fabric 的 KnotClassLoader 会自己加载一份，静态字段与 bootstrap 那份互不可见
        // （1.21.1 实测：recordKey 写 Knot 份、叠加层读 bootstrap 份）。
        exclude("dev/nocturne/bootstrap/**")
        // 目录条目也得排：zipTree 会把 "org/objectweb/" 这样的目录带进来，在 jar 里留下一个
        // 空的 org/objectweb/（无意义，而且看起来像「ASM 被展开了」的误导信号）。
        exclude("org/objectweb/")
    }

    // ASM 以「加密载荷」形式内嵌，路径固定为 dev/nocturne/agent/asm.pack：agent 运行时用子加载器
    // （child-first 于 org.objectweb.asm.）在内存中解包加载，从而既能拿到 ASM，又不让展开的
    // org/objectweb/asm/** 出现在 jar 根被游戏自己的加载器抢先加载。
    dependsOn(packAsmPayload)
    from(packedAsmDir) {
        into("dev/nocturne/agent")
    }

    // 逐个并入各业务模块的 main 输出，并显式 dependsOn 其 classes 任务以保证构建顺序。
    listOf(":core", ":agent", ":client", ":ui", ":injector").forEach { path ->
        val output = project(path).extensions.getByType<SourceSetContainer>()["main"].output
        dependsOn(project(path).tasks.named("classes"))
        from(output)
    }

    // 依赖 jar 里的模块描述符必须排除：它会让 javac 把这个 jar 当成模块处理，
    // 于是任何「以本 jar 为 classpath」的编译都会报「程序包 xxx 不存在」。
    exclude("module-info.class", "META-INF/versions/**/module-info.class")

    // 字节码操作库在 jar 根必须排除展开的类：加载器自己也带这些库，
    // 而 Fabric 的 KnotClassLoader 会先在 mods jar 里找类——于是同一个 org.objectweb.asm.MethodVisitor
    // 被两个加载器各加载一次，MixinExtras 与 sponge-mixin 拿到的类型对不上，直接 VerifyError 崩溃。
    // 排除后：模组路径用加载器自带的版本；agent 路径改从内嵌资源 dev/nocturne/agent/asm.pack
    // 以子加载器解包加载 ASM，帧钩子因此不再依赖目标 JVM 是否自带 ASM，也不在 jar 里留下明文 ASM。
    exclude("org/objectweb/asm/**", "org/spongepowered/**", "com/llamalad7/**")

    // 产物自检：必须内嵌 dev/nocturne/agent/asm.pack，且 jar 根不得出现展开的 ASM 类。
    // 这两个不变式一旦被破坏（例如打包任务被跳过或 exclude 失效），帧钩子会静默失效，故在此硬失败。
    doLast {
        val jarFile = archiveFile.get().asFile
        ZipFile(jarFile).use { zip ->
            if (zip.getEntry("dev/nocturne/agent/asm.pack") == null) {
                throw GradleException(
                    "dist jar ${jarFile.name} is missing the embedded ASM payload " +
                        "'dev/nocturne/agent/asm.pack'; the frame hook would silently fail."
                )
            }
            var stray: String? = null
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val name = entries.nextElement().name
                if (name.startsWith("org/objectweb/asm/")) {
                    stray = name
                    break
                }
            }
            if (stray != null) {
                throw GradleException(
                    "dist jar ${jarFile.name} leaked an expanded ASM class '$stray'; " +
                        "ASM must only be embedded as the encrypted payload " +
                        "'dev/nocturne/agent/asm.pack'."
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 类名随机化：官方二进制的必经过道。
//
// 作用对象必须是**合并后的最终 jar**——各模块若在编译期各自改名，合并之后跨模块引用就
// 对不上了，所以这一步只能挂在 distJar 之后，而不是各模块的编译期。
//
// 随机化由 tools/randomize/randomize.py 完成（与 tools/mapping 同一套构建期 Python 约定）。
// 它写完会立刻自校验悬空引用并以非 0 退出：引用对不上时构建期就红，不留到目标 JVM 里
// 变成「类找不到」的静默失效。
// ---------------------------------------------------------------------------
val randomizedJar = tasks.register<Exec>("randomizedJar") {
    group = "build"
    description = "Produces the class-name-randomized delivery jar (the official binary)."
    dependsOn(distJar)

    // 解释器名可覆盖：Windows 上通常是 python，其余平台是 python3。
    val python = (findProperty("python") as? String) ?: "python3"
    // 脚本在根项目下，dist 子项目要显式指回根。
    val script = rootProject.layout.projectDirectory.file("tools/randomize/randomize.py").asFile
    val srcJar = distJar.flatMap { it.archiveFile }
    val outJar = layout.buildDirectory.file("libs/nocturne-${project.version}-randomized.jar")

    inputs.file(script)
    inputs.files(srcJar)
    outputs.file(outJar)

    doFirst {
        outJar.get().asFile.parentFile.mkdirs()
        val args = mutableListOf(python, script.absolutePath,
            "--in", srcJar.get().asFile.absolutePath,
            "--out", outJar.get().asFile.absolutePath,
            "--prefix", "dev/nocturne")
        // 不给 --salt 时工具自行取随机种子（每次官方构建都不同）；
        // CI 传 -PclassSalt=<固定值> 即可得到可复现产物。
        (findProperty("classSalt") as? String)?.takeIf { it.isNotBlank() }?.let {
            args += listOf("--salt", it)
        }
        commandLine(args)
    }
}

tasks.named("assemble") {
    dependsOn(distJar)
}

// java 插件默认的 jar 任务会产出只含 META-INF 的空壳（约 1KB），与交付 jar 并列会误导；
// 交付物只有上面的 distJar。
tasks.named("jar") {
    enabled = false
}
