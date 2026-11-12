plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test-junit"))
}

// Builds the bundled demo scenarios from assets/data/demo_points.json.
tasks.register<JavaExec>("demoScenarios") {
    group = "build"
    description = "Writes app/src/main/assets/scenarios/*.json from the demo points."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("app.jamkkanman.core.metrics.DemoScenariosMainKt")
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8")
    args(rootProject.file("app/src/main/assets").absolutePath)
}

// Runs every replay scenario and the measurement suite, then writes docs/metrics/.
tasks.register<JavaExec>("metrics") {
    group = "verification"
    description = "Replays scenarios and measures the quantitative targets."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("app.jamkkanman.core.metrics.MetricsMainKt")
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8")
    args(
        rootProject.file("app/src/main/assets").absolutePath,
        rootProject.file("docs/metrics").absolutePath
    )
}
