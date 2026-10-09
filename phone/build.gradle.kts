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
val requireSigning = providers.gradleProperty("livefit.requireSigning").orNull == "true"
// The command line only picks the archive name; the gate itself runs on the resolved graph (fails closed).
base.archivesName.set(if (releaseRequested && signing is SigningResolution.Diagnostic) "phone-unsigned-diagnostic" else "phone")
gradle.taskGraph.whenReady {
    if (ReleaseGate.packagesRelease(allTasks.filter { it.project == project }.map { it.name })) {
        ReleaseGate.problem(signing, requireSigning, tilesKeyMissing = tilesKey == null, labelledDiagnostic = releaseRequested)
            ?.let { throw GradleException("$path: $it") }
    }
}

android {
    namespace = "com.debasish.livefit.phone"
    compileSdk = 36

    defaultConfig {
        // Must match :watch so the Wearable Data Layer pairs the two apps (spec §2).
        applicationId = "com.livear.fit"
        minSdk = 29
        targetSdk = 36
        versionName = liveFitVersion
        versionCode = LiveFitVersion.code(liveFitVersion, LiveFitVersion.FormFactor.Phone)
        // MapTiler key (spec §5); "" = none: debug builds use the OSM server, release builds never get this far.
        buildConfigField("String", "TILES_KEY", "\"${tilesKey.orEmpty()}\"")
        // Compile-time constants (spec §3 "they become constants"): R8 folds the Fake branches away.
        buildConfigField("boolean", "LIVE_WATCH", "true")
        buildConfigField("boolean", "LIVE_GLASSES", "true")
        buildConfigField("boolean", "LIVE_MUSIC", "true")
        buildConfigField("boolean", "LIVE_VOICE", "true")
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
            // No keystore: debug-signed, named *-unsigned-diagnostic, never distributed (spec §3). Misconfigured: unset (and the gate fails).
            signingConfig = when (signing) {
                is SigningResolution.Release -> signingConfigs.getByName("release")
                SigningResolution.Diagnostic -> signingConfigs.getByName("debug")
                is SigningResolution.Misconfigured -> null
            }
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
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
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
