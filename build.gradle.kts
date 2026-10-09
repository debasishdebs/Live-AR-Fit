plugins {
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.27" apply false
}

// Spec §3 (rev 4) hard gate: LOAD alignment of every native library + 16 KB zip alignment of uncompressed libs in APKs; RELRO end = warning.
tasks.register<Exec>("check16kb") {
    group = "verification"
    description = "16 KB check on the release AABs and the glasses APK: LOAD p_align and APK zip alignment fail; RELRO end warns."
    dependsOn(":phone:bundleRelease", ":watch:bundleRelease", ":glasses:assembleRelease")
    commandLine(
        "python3", "tools/release/check_16kb.py",
        "phone/build/outputs/bundle/release", "watch/build/outputs/bundle/release", "glasses/build/outputs/apk/release",
    )
}
