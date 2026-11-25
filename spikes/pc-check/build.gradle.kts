plugins {
    kotlin("jvm") version "2.4.21"
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation("io.github.dokar3:quickjs-kt-jvm:1.0.18")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    testLogging {
        showStandardStreams = true
        events("passed", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Print the quickjs jar path so the workflow can list the native libraries inside it.
tasks.register("printQuickJsJar") {
    doLast {
        configurations.getByName("runtimeClasspath").files
            .filter { it.name.contains("quickjs") }
            .forEach { println("QJS_JAR=" + it.absolutePath) }
    }
}
