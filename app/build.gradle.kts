plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "lumen.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "lumen.camera"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.2.0"
    }
    buildTypes {
        release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("debug") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    sourceSets["main"].java.srcDir("src/main/kotlin")
}
dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
// Safety invariant #1: openCamera( / setPhysicalCameraId( / getCameraCharacteristics( only in CameraGate.kt.
tasks.register("checkGateInvariant") {
    val src = fileTree("src/main/kotlin")
    doLast {
        val bad = src.files.filter { it.name != "CameraGate.kt" }
            .filter { f -> f.readLines().any { l ->
                val t = l.trim(); !t.startsWith("//") && !t.startsWith("*") &&
                    (t.contains("openCamera(") || t.contains("setPhysicalCameraId(") || t.contains("getCameraCharacteristics(")) } }
        if (bad.isNotEmpty()) throw GradleException("Gate invariant violated in: ${bad.map { it.name }}")
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn("checkGateInvariant") }
