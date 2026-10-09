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
val requireSigning = providers.gradleProperty("livefit.requireSigning").orNull == "true"
// The command line only picks the archive name; the gate itself runs on the resolved graph (fails closed).
base.archivesName.set(if (releaseRequested && signing is SigningResolution.Diagnostic) "glasses-unsigned-diagnostic" else "glasses")
gradle.taskGraph.whenReady {
    if (ReleaseGate.packagesRelease(allTasks.filter { it.project == project }.map { it.name })) {
        ReleaseGate.problem(signing, requireSigning, tilesKeyMissing = false, labelledDiagnostic = releaseRequested)
            ?.let { throw GradleException("$path: $it") }
    }
}

android {
    namespace = "com.debasish.livefit.glasses"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.livear.fit.glasses"
        minSdk = 28
        targetSdk = 32 // Rokid OS is fixed (spec §3)
        versionName = liveFitVersion
        versionCode = LiveFitVersion.code(liveFitVersion, LiveFitVersion.FormFactor.Glasses)
        ndk { abiFilters += listOf("arm64-v8a") }
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
            // Same upload key as phone/watch, so GitHub updates install over each other (spec §3).
            signingConfig = when (signing) {
                is SigningResolution.Release -> signingConfigs.getByName("release")
                SigningResolution.Diagnostic -> signingConfigs.getByName("debug")
                is SigningResolution.Misconfigured -> null // the gate fails; never a silent debug signature
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    // Play's target-API floor doesn't apply: the glasses APK ships via GitHub and Rokid OS fixes targetSdk 32.
    lint { disable += "ExpiredTargetSdkVersion" }
}

dependencies {
    implementation(project(":services:sync"))
    implementation(project(":services:voice"))
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("com.rokid.cxr:cxr-service-bridge:1.5") // newest release; source-compatible for our usage (1.5 adds callbacks)
    implementation("androidx.core:core-ktx:1.13.1")
}
