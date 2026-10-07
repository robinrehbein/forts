import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "de.bollwerk.renderandroid"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.android.buildTools.get()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            // JVM-Unit-Tests laufen gegen die android.jar-Attrappe: Paint/Path/Canvas-Aufrufe sind dann wirkungslos
            // (Rückgabe Standardwerte). Tests prüfen damit die Weiterleitung (aufgezeichnetes Canvas) und die reine Logik.
            isReturnDefaultValues = true
        }
        unitTests.all {
            it.useJUnitPlatform()
            // Wie in :render-api: ohne Escape-Analyse messen, damit die Allokationstests das zeigen, was ART tatsächlich allokiert.
            it.jvmArgs("-XX:-DoEscapeAnalysis")
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = false
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(project(":render-api"))
    api(project(":engine"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Die synthetische Szene (Tabellen, Karte, Festungen als FrameSnapshot) liegt in den Tests von :render-api und ist von
// dort nicht abhängbar; für den Smoke-Test wird nur diese eine Datei in die Test-Quellen kopiert (keine Duplikate im Repo).
val copySyntheticScene = tasks.register<Copy>("copySyntheticSceneForTests") {
    from(rootProject.file("render-api/src/commonTest/kotlin/de/bollwerk/renderapi/scene/SyntheticScene.kt"))
    into(layout.buildDirectory.dir("generated/syntheticScene/de/bollwerk/renderapi/scene"))
}
android.sourceSets.getByName("test").java.srcDir(layout.buildDirectory.dir("generated/syntheticScene"))
tasks.matching { it.name != copySyntheticScene.name && (it.name.contains("UnitTest") || it.name.contains("Lint")) }.configureEach {
    dependsOn(copySyntheticScene)
}
