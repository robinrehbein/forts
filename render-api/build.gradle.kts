import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// KMP-Modul: vorerst nur JVM-Target (iOS folgt). Bytecode-Ziel 17, gebaut mit JDK 21.
kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
            // Ohne Escape-Analyse: ART (Android) entfernt keine Boxen/Kurzlebigen wie HotSpot C2. `AllocationTest` soll
            // die echte Allokation des Render-Pfads messen, nicht das, was C2 wegoptimiert.
            jvmArgs("-XX:-DoEscapeAnalysis")
        }
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":engine"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test.junit5)
            implementation(project.dependencies.platform(libs.junit.bom))
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}
