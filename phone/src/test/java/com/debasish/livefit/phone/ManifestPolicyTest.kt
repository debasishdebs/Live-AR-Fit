package com.debasish.livefit.phone

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

    /** Spec §4: restricted permission removed; the banner uses the permission-free settings list. */
    @Test fun noBatteryExemptionRequest() = assertTrue("REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" !in manifest)

    @Test fun noBackgroundLocation() = assertTrue("ACCESS_BACKGROUND_LOCATION" !in manifest)

    @Test fun disclosureScreenIsInternal() =
        assertTrue(Regex("""android:name="\.ui\.DisclosureActivity"\s+android:exported="false"""").containsMatchIn(manifest))
}
