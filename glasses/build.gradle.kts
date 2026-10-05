plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.debasish.livefit.glasses"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.debasish.livefit.glasses"
        minSdk = 28
        targetSdk = 32
        versionCode = 1
        versionName = "0.1-spike"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":services:workout"))
    implementation(project(":services:metrics"))
    implementation(project(":services:music"))
    implementation(project(":services:voice"))
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("com.rokid.cxr:cxr-service-bridge:1.4")
    implementation("androidx.core:core-ktx:1.13.1")
}
