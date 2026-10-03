import org.gradle.api.tasks.SourceSetContainer

plugins {
    java
}

dependencies {
    implementation(project(":core"))
    implementation(project(":agent"))
    implementation(project(":client"))
    implementation(project(":ui"))
    implementation(project(":injector"))
}

/** Expands ${version} in the loader metadata templates. */
tasks.processResources {
    filesMatching(listOf("fabric.mod.json", "META-INF/mods.toml", "META-INF/neoforge.mods.toml")) {
        expand("version" to project.version.toString())
    }
}

/**
 * The shipped artifact: one jar that is simultaneously
 *   - the injector GUI (double-click: Main-Class in the manifest)
 *   - the agent       (Premain-Class / Agent-Class, injected into a running JVM)
 *   - a mod           (fabric.mod.json / mods.toml / neoforge.mods.toml on the classpath)
 *
 * <p>The mod-loader stubs live in a separate source set and are intentionally excluded.
 */
val distJar = tasks.register<Jar>("distJar") {
    group = "build"
    description = "Assembles the single multi-entry noturne jar."
    archiveFileName.set("noturne-${project.version}.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes(
            "Main-Class" to "dev.noturne.injector.InjectorApp",
            "Premain-Class" to "dev.noturne.agent.NoturneAgent",
            "Agent-Class" to "dev.noturne.agent.NoturneAgent",
            "Can-Retransform-Classes" to "true",
            "Can-Redefine-Classes" to "true",
            "Implementation-Title" to "noturne-client",
            "Implementation-Version" to project.version.toString(),
        )
    }

    from(sourceSets.main.get().output)

    // Bundle third-party runtime dependencies (ASM for bytecode patching, gson for mappings) so the
    // shipped jar is self-contained and can be dropped into any JVM.
    from(configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) })

    listOf(":core", ":agent", ":client", ":ui", ":injector").forEach { path ->
        val output = project(path).extensions.getByType<SourceSetContainer>()["main"].output
        dependsOn(project(path).tasks.named("classes"))
        from(output)
    }
}

tasks.named("assemble") {
    dependsOn(distJar)
}
