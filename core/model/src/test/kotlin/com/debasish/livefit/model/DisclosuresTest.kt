package com.debasish.livefit.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Spec §4: the disclosures must say these things; the privacy policy and Data safety drafts quote the same texts. */
class DisclosuresTest {
    private fun String.says(vararg phrases: String) = phrases.forEach { assertTrue(it in this, "missing \"$it\" in: $this") }

    @Test fun musicSaysWhatIsReadWhereItGoesAndHow() = Disclosures.MUSIC.says(
        "title, artist, playback state and up-next queue", "sent to your paired watch and glasses",
        "Rokid CXR Bluetooth link", "Wear OS Data Layer", "Google's cloud, encrypted", "no server of its own",
        "music features stay off and everything else works",
    )

    @Test fun locationIsWorkoutOnlyAndNamesTheTileProvider() {
        Disclosures.LOCATION.says("only during a workout", "\"Use GPS outdoors\"", "never uses your location in the background", "MapTiler", "IP address", "Google's cloud, encrypted")
        Disclosures.WATCH_LOCATION.says("only during workouts", "never in the background", "MapTiler")
    }

    @Test fun microphoneIsOnDeviceOnly() {
        Disclosures.MIC.says("on-device only", "never uploaded")
        assertFalse("cloud recognition" in Disclosures.MIC)
    }

    @Test fun policyIsPublishedAndHasAContact() {
        assertTrue(Disclosures.PRIVACY_POLICY_URL.startsWith("https://"))
        Disclosures.WATCH_SUMMARY.says("Delete your workout history with Clear history", "uninstall to remove everything", Disclosures.CONTACT_EMAIL, "MapTiler")
    }
}
