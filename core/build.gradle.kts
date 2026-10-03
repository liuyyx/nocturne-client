plugins {
    java
}

dependencies {
    implementation("com.google.code.gson:gson:2.10.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

tasks.jar {
    manifest {
        attributes(
            "Main-Class" to "dev.noturne.core.Noturne",
            "Implementation-Title" to "noturne-client",
            "Implementation-Version" to project.version.toString(),
        )
    }
}
