plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.debasish.livefit.phone"
    compileSdk = 36

    defaultConfig {
        // Must match :watch so the Wearable Data Layer pairs the two apps.
        applicationId = "com.debasish.livefit"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-spike"
        // Which services are Live (see Bindings in ServiceGraph); later tasks flip these per service.
        buildConfigField("boolean", "LIVE_WATCH", "true")
        buildConfigField("boolean", "LIVE_GLASSES", "true")
        buildConfigField("boolean", "LIVE_MUSIC", "false")
        buildConfigField("boolean", "LIVE_VOICE", "true")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    implementation(project(":core:services"))
    implementation(project(":services:workout"))
    implementation(project(":services:sync"))
    implementation(project(":services:confirm"))
    implementation(project(":services:history"))
    implementation(project(":services:glasses-link"))
    implementation(project(":services:watch-link"))
    implementation(project(":services:music"))
    implementation(project(":services:voice"))
    implementation(project(":services:voice-android"))
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("com.rokid.cxr:client-l:1.1.2")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("androidx.wear:wear-remote-interactions:1.1.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.9.0")
}
