package com.debasish.livefit.glasses

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

    /** Hi Rokid agent receiver (loopback socket) needs INTERNET; the manifest adds nothing else for it. */
    @Test fun internetIsDeclaredForTheAgentReceiver() {
        assertTrue("<uses-permission android:name=\"android.permission.INTERNET\" />" in manifest)
        assertTrue("usesCleartextTraffic" !in manifest && "networkSecurityConfig" !in manifest)
    }

    /** ACTION_REQUEST_DISCOVERABLE throws SecurityException on Android 12+ without BLUETOOTH_CONNECT. */
    @Test fun bluetoothConnectIsDeclaredForPairing() {
        assertTrue("<uses-permission android:name=\"android.permission.BLUETOOTH_CONNECT\" />" in manifest)
    }

    /** Spec §4: tokens and the workout DB are never copied off the device (cloud backup or device transfer). */
    @Test fun backupIsOff() {
        assertTrue("android:allowBackup=\"false\"" in manifest)
        assertTrue("android:dataExtractionRules=\"@xml/data_extraction_rules\"" in manifest)
    }
}
