plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.debasish.livefit.services.glasses"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].java.srcDirs("src/main/kotlin")
    sourceSets["test"].java.srcDirs("src/test/kotlin")
}

dependencies {
    api(project(":core:services"))
    implementation(project(":services:sync"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Live link: Rokid CXR-L through the Hi Rokid app (verified on device: no client secret needed).
    implementation("com.rokid.cxr:client-l:1.1.2")
}
dependencies {
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}
