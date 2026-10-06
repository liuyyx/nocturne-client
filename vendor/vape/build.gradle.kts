// 隔离编译探针：编译 vendor/vape 下**原样照抄**的 Vape 源码（CC0-1.0，见 LICENSE-CC0.txt）。
//
// 不修改 vendor/vape/** 的任何一行；缺什么都在本脚本与自己的文件里补。
// 依赖清单直接照抄上游 OpenVape4.21/build.gradle 的 dependencies（上游用 Java 17 工具链）。
plugins {
    java
}

repositories {
    mavenCentral()
}

sourceSets {
    named("main") {
        java.srcDirs("src/main/java")
        resources.srcDirs("src/main/resources")
    }
}

dependencies {
    // —— 上游 build.gradle 里声明的运行时依赖，逐条照搬 ——
    implementation("org.javassist:javassist:3.29.2-GA")
    implementation("org.ow2.asm:asm:9.7.1")
    implementation("org.ow2.asm:asm-tree:9.7.1")
    implementation("org.ow2.asm:asm-analysis:9.7.1")
    implementation("org.ow2.asm:asm-util:9.7.1")
    implementation("org.ow2.asm:asm-commons:9.7.1")
    implementation("com.google.guava:guava:17.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("io.netty:netty-all:4.0.23.Final")
    implementation("commons-io:commons-io:2.4")
    implementation("org.lwjgl.lwjgl:lwjgl:2.9.3")
    implementation("org.lwjgl.lwjgl:lwjgl_util:2.9.3")
    compileOnly("org.jetbrains:annotations:24.1.0")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // 上游默认 release=17（其注释：-PtargetRelease=8 用于验证 Java 8 兼容）。
    options.release.set(17)
    options.compilerArgs.addAll(listOf("-nowarn", "-Xlint:none", "-Xmaxerrs", "100000"))
}

// 记录编译 classpath 与源码规模，便于人工核查探针覆盖面。
tasks.register("probeReport") {
    doLast {
        val java = fileTree("src/main/java") { include("**/*.java") }.files
        val lines = java.sumOf { it.readLines().size }
        println("vape probe: ${java.size} java files / $lines lines")
        println("classpath entries: " + sourceSets["main"].compileClasspath.files.size)
    }
}
