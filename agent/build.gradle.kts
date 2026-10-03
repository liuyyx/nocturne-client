plugins {
    java
}

/**
 * Compile-time stubs for the mod-loader entry points.
 *
 * <p>Deliberately NOT packaged: the real {@code ModInitializer}/{@code @Mod} are supplied by the
 * loader at runtime, so noturne needs no loader dependency and stays loader/version agnostic.
 */
val modStubs by sourceSets.creating {
    java.srcDir("src/modStubs/java")
}

dependencies {
    implementation(project(":client"))
    implementation(project(":ui"))
    compileOnly(modStubs.output)
    // Bytecode patching for the frame hook. Bundled into the dist jar (see :dist).
    implementation("org.ow2.asm:asm:9.7.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

tasks.jar {
    manifest {
        attributes(
            "Premain-Class" to "dev.noturne.agent.NoturneAgent",
            "Agent-Class" to "dev.noturne.agent.NoturneAgent",
            "Can-Retransform-Classes" to "true",
            "Can-Redefine-Classes" to "true",
        )
    }
}
