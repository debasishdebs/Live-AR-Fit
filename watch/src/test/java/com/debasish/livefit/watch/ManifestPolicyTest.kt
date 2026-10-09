package com.debasish.livefit.watch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Play-policy guard on our own manifest (unit tests run with the module directory as working directory). */
class ManifestPolicyTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test fun labelAndIconComeFromResources() {
        assertTrue("android:label=\"@string/app_name\"" in manifest)
        assertTrue("android:icon=\"@mipmap/ic_launcher\"" in manifest)
        assertTrue("android:roundIcon=\"@mipmap/ic_launcher_round\"" in manifest)
    }

    /** Spec §4: tokens and the workout DB are never copied off the device (cloud backup or device transfer). */
    @Test fun backupIsOff() {
        assertTrue("android:allowBackup=\"false\"" in manifest)
        assertTrue("android:dataExtractionRules=\"@xml/data_extraction_rules\"" in manifest)
    }

    /** Spec §4: restricted permission removed; the Ongoing Activity chip replaces it. */
    @Test fun noFullScreenIntent() = assertTrue("USE_FULL_SCREEN_INTENT" !in manifest)

    /** Spec §4 (review P1-1): BODY_SENSORS only up to API 35, the granular permission for API 36+. */
    @Test fun heartRatePermissionIsOsDependent() {
        assertTrue(Regex("""android:name="android\.permission\.BODY_SENSORS"\s+android:maxSdkVersion="35"""").containsMatchIn(manifest))
        assertTrue("android.permission.health.READ_HEART_RATE" in manifest)
    }

    @Test fun noBackgroundLocationOrBackgroundHealth() {
        for (p in listOf("ACCESS_BACKGROUND_LOCATION", "BODY_SENSORS_BACKGROUND", "READ_HEALTH_DATA_IN_BACKGROUND")) assertTrue(p !in manifest, p)
    }

    /** Spec §4: the watch never asks for notification access; :services:music's listener is stripped from its merge. */
    @Test fun noNotificationListener() = assertTrue(
        Regex("""android:name="com\.debasish\.livefit\.services\.music\.MediaListener"\s+tools:node="remove"""").containsMatchIn(manifest),
    )
}
