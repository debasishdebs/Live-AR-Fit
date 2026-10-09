import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun rootProperties(name: String): Map<String, String> = Properties().apply {
    rootProject.file(name).takeIf { it.isFile }?.inputStream()?.use { load(it) }
}.entries.associate { (k, v) -> k.toString() to v.toString() }

val liveFitVersion = providers.gradleProperty("livefit.version").get()
val releaseRequested = ReleaseGate.requested(gradle.startParameter.taskNames, path)
val signing = ReleaseSigning.resolve(rootProperties("keystore.properties"), System.getenv())
val tilesKey = TilesKey.resolve(rootProperties("local.properties"), System.getenv())
if (releaseRequested) {
    ReleaseSigning.problem(signing, providers.gradleProperty("livefit.requireSigning").orNull == "true")?.let { throw GradleException(it) }
    if (tilesKey == null) throw GradleException("${TilesKey.NAME} is required for release builds (local.properties or env) — spec §5")
}
base.archivesName.set(if (releaseRequested && signing is SigningResolution.Diagnostic) "watch-unsigned-diagnostic" else "watch")

android {
    namespace = "com.debasish.livefit.watch"
    compileSdk = 36

    defaultConfig {
        // Must match :phone so the Wearable Data Layer pairs the two apps (spec §2).
        applicationId = "com.livear.fit"
        minSdk = 30
        targetSdk = 36
        versionName = liveFitVersion
        versionCode = LiveFitVersion.code(liveFitVersion, LiveFitVersion.FormFactor.Watch)
        buildConfigField("String", "TILES_KEY", "\"${tilesKey.orEmpty()}\"")
    }
    signingConfigs {
        create("release") {
            (signing as? SigningResolution.Release)?.input?.let { s ->
                storeFile = file(s.storeFile)
                storePassword = s.storePassword
                keyAlias = s.keyAlias
                keyPassword = s.keyPassword
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (signing is SigningResolution.Release) "release" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    implementation(project(":services:sync"))
    implementation(project(":services:music"))
    implementation("androidx.wear:wear-ongoing:1.0.0")
    implementation("androidx.wear:wear:1.3.0") // AmbientLifecycleObserver (B1: workout screen on AOD)
    implementation("androidx.wear:wear-remote-interactions:1.1.0") // "Open on phone" (spec §5, §7)
    // gms/wear pull fragment 1.2.4 transitively; lintVitalRelease rejects it with the ActivityResult API.
    implementation("androidx.fragment:fragment:1.8.3")
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.wear.compose:compose-material:1.4.0")
    implementation("androidx.wear.compose:compose-foundation:1.4.0")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("androidx.health:health-services-client:1.1.0-alpha05")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.9.0")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
