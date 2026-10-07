import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // Snapshot-Tests des HUD (Layoutlib, JVM). 1.3.5 unterstützt AGP 8.7 / compileSdk 35.
    id("app.cash.paparazzi") version "1.3.5"
}

android {
    namespace = "de.bollwerk.app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.android.buildTools.get()

    defaultConfig {
        applicationId = "de.bollwerk.app"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/LICENSE*")
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
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
    implementation(project(":engine"))
    implementation(project(":content"))
    implementation(project(":setup"))
    implementation(project(":ai"))
    implementation(project(":render-api"))
    implementation(project(":render-android"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}

dependencies {
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // Paparazzi-Tests sind JUnit-4-Regeln: auf der JUnit-Plattform über die Vintage-Engine
    //noinspection UseTomlInstead (Version aus der JUnit-BOM; der Katalog gehört nicht zu diesem Modul)
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
}

// Paparazzi-Snapshots werden in jedem Unit-Test-Lauf (`testDebugUnitTest`, `check`) gegen die Goldens unter
// `src/test/snapshots/images` geprüft, nicht nur gerendert: ohne das bliebe eine HUD-Regression im normalen Testlauf grün.
// Das Plugin setzt `paparazzi.test.verify` in einem eigenen doFirst nur für `verifyPaparazzi*`; dieses doFirst wird
// früher registriert, läuft also danach und schaltet den Vergleich auch für den normalen Testlauf ein.
// Neu aufnehmen (gewollte Änderung): `./gradlew :app:recordPaparazziDebug`, Bilder ansehen, mit einchecken.
tasks.withType<Test>().configureEach {
    // Goldens sind Eingaben: ein geändertes Golden lässt den Testlauf erneut laufen
    inputs.files(fileTree("src/test/snapshots")).withPropertyName("paparazziGoldens").withPathSensitivity(PathSensitivity.RELATIVE)
    doFirst {
        if (systemProperties["paparazzi.test.record"]?.toString() != "true") {
            systemProperty("paparazzi.test.verify", true)
        }
    }
}
