import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":content"))
    implementation(project(":setup"))
    implementation(project(":ai"))
    implementation(project(":render-api"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("de.bollwerk.simrunner.MainKt")
    applicationName = "simrunner"
}

tasks.test {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
}

// Relative Pfade der Kommandozeile (Szenarien, PNGs) gelten ab dem Repo-Wurzelverzeichnis.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}
