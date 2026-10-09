package com.debasish.livefit.model

/**
 * Prominent-disclosure and privacy wording (spec §4, §7) — one source for the phone and watch screens. The privacy
 * policy and the Play Data safety drafts (docs/) quote these texts verbatim; change them together.
 */
object Disclosures {
    const val PRIVACY_POLICY_URL = "https://debasishdebs.github.io/Live-AR-Fit/privacy-policy.html"
    const val CONTACT_EMAIL = "d.kanhar@gmail.com"

    /** How every phone↔watch message travels: workout, heart rate, location fixes, settings and music. */
    const val DATA_LAYER =
        "Between your phone and watch, data travels over the Wear OS Data Layer: Bluetooth when the watch is nearby. " +
            "When Bluetooth isn't available, Google Play services may relay it through Google's cloud, encrypted. " +
            "Live AR Fit has no server of its own and never uploads your data to one."

    const val LOCATION_TITLE = "Location for your route"
    const val LOCATION =
        "Live AR Fit uses GPS only during a workout with \"Use GPS outdoors\" on, to record your route and show the map " +
            "on your phone, watch and glasses. It never uses your location in the background when no workout is running. " +
            "Your route is stored on your phone and watch. To draw the map, the phone and watch download map tiles from " +
            "MapTiler, which sees your IP address and the map area shown. $DATA_LAYER"

    /** The watch screen is small: the same facts, shorter. */
    const val WATCH_LOCATION =
        "GPS is used only during workouts with \"Use GPS outdoors\" on — never in the background. Map tiles come from MapTiler, which sees your IP and the map area."

    const val MUSIC_TITLE = "Music control needs notification access"
    const val MUSIC =
        "Live AR Fit reads the active media session of your music app (YouTube Music): the title, artist, playback state " +
            "and up-next queue. This is sent to your paired watch and glasses so they can show and control your music. " +
            "To the glasses it goes over the Rokid CXR Bluetooth link. $DATA_LAYER " +
            "Android calls this \"notification access\"; Live AR Fit does not read your notifications. " +
            "If you decline, music features stay off and everything else works."

    const val MIC_TITLE = "Microphone for voice commands"
    const val MIC =
        "Voice commands are recognised on your phone, on-device only — there is no cloud speech service. Live AR Fit " +
            "listens only after you tap Talk or when it asks you a yes/no question. Audio from the glasses' microphone " +
            "travels to your phone over the Rokid Bluetooth link. Audio is never uploaded or stored."

    /** The watch About summary shown when the phone can't open the policy. */
    const val WATCH_SUMMARY =
        "No account, no ads, no analytics. Workout, heart-rate and route data stay on your phone and watch; music details " +
            "are sent only to your watch and glasses. Voice is recognised on the phone, on-device. Map tiles come from MapTiler. " +
            "Delete your workout history with Clear history; uninstall to remove everything. Contact: $CONTACT_EMAIL"
}
