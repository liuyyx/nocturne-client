plugins {
    java
}

allprojects {
    group = "dev.noturne"
    version = "0.1.0-SNAPSHOT"
}

/**
 * Every module that can run *inside the target JVM* must target Java 8 bytecode:
 * the client supports Minecraft 1.8.9 (JVM 8) through 26.3 (JVM 21+), and an agent
 * class bytecode newer than the target JVM is rejected with UnsupportedClassVersionError.
 * Java 8 bytecode runs on every JVM we care about, so release=8 is the safe floor.
 */
subprojects {
    apply(plugin = "java")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(8)
        options.encoding = "UTF-8"
        // Java 8 target on a modern JDK: silence the bootclasspath warning only.
        options.compilerArgs.add("-Xlint:-options")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
