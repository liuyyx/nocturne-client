// core 模块构建脚本：注入器/加载器的依赖与单 jar 入口声明。
// 该模块提供 dev.nocturne.core.Nocturne 作为可执行入口，同时被 dist 打包进最终产物。

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

// ---------------------------------------------------------------------------
// Windows attach 原生库（P7-1）：MSVC 现地编译。
//
// 源码：src/main/native/windows/attach.c；产物：build/native/windows-x64/
//   nocturne-attach.dll，随后打进 core jar 的 /native/windows-x64/ 资源位，
//   运行时由 NativeLibraryLoader 解压加载。
// 非 Windows 或找不到 cl 时跳过（不中断构建，Java 侧按“原生层不可用”处理）。
// ---------------------------------------------------------------------------
val nativeSrcDir = layout.projectDirectory.dir("src/main/native/windows")
val nativeOutDir = layout.buildDirectory.dir("native/windows-x64")
val nativeDll = nativeOutDir.map { it.file("nocturne-attach.dll") }

val compileAttachNative = tasks.register<Exec>("compileAttachNative") {
    group = "build"
    description = "Compiles the Windows attach native library with MSVC (x64 only)."
    onlyIf("Windows x64 with attach.c present") {
        val os = System.getProperty("os.name", "").lowercase()
        val arch = System.getProperty("os.arch", "").lowercase()
        os.contains("win") && arch.contains("64")
            && nativeSrcDir.file("attach.c").asFile.isFile
    }
    // vcvarsall + cl 经由 cmd 调用：Exec 直接调 bat 拿不到环境变量。
    val jdkHome = System.getenv("JAVA_HOME") ?: System.getProperty("java.home")
    val attachC = nativeSrcDir.file("attach.c").asFile.absolutePath
    val outDll = nativeDll.get().asFile.absolutePath
    val vcvars = "C:\\Program Files (x86)\\Microsoft Visual Studio\\2022" +
        "\\BuildTools\\VC\\Auxiliary\\Build\\vcvarsall.bat"
    commandLine("cmd", "/c",
        "call \"$vcvars\" x64 >nul && cl /nologo /LD /O2 /W3 /utf-8 " +
            "/I\"$jdkHome\\include\" /I\"$jdkHome\\include\\win32\" " +
            "\"$attachC\" /link /DLL /OUT:\"$outDll\" /MACHINE:X64")
    // cl 把 .obj/.lib/.exp 落在工作目录，必须钉在 build 下，否则污染源码树
    //（曾把 core/attach.lib 这类中间产物留在 core/ 并被误提交）。
    workingDir = layout.buildDirectory.get().asFile
    doFirst {
        nativeOutDir.get().asFile.mkdirs()
        layout.buildDirectory.get().asFile.mkdirs()
    }
    // cl 不在 PATH 也无妨：onlyIf 已 gate；vcvars 缺失时报可读错误而非静默跳过。
    doFirst {
        if (!file(vcvars).isFile) {
            throw GradleException("MSVC vcvarsall.bat not found at $vcvars; " +
                "install VS 2022 BuildTools with C++ workload to build the attach native lib")
        }
    }
    // 必须显式声明源码为输入：Exec 任务默认没有 inputs，只靠输出存在性判断是否最新，
    // 结果改了 attach.c 也不会重编，jar 里会一直带着上一次的 dll。
    inputs.file(nativeSrcDir.file("attach.c"))
    outputs.file(nativeDll)
}

// 原生 dll 打进 jar 资源位（Java 侧 NativeLibraryLoader 按此路径加载）。
tasks.named<ProcessResources>("processResources") {
    dependsOn(compileAttachNative)
    from(nativeOutDir) {
        into("native/windows-x64")
    }
}

    // 在 jar 清单中写入入口类，使产物可直接 `java -jar` 运行（启动器即依赖这一点驱动本模块）。
tasks.jar {
    manifest {
        attributes(
            // Main-Class：GUI 双击启动入口；后两项为实现元信息，便于排查版本对应关系。
            "Main-Class" to "dev.nocturne.core.Nocturne",
            "Implementation-Title" to "nocturne-client",
            "Implementation-Version" to project.version.toString(),
        )
    }
}
