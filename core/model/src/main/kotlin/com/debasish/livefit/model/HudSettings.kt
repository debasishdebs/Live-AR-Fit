package com.debasish.livefit.model

import kotlinx.serialization.Serializable

/** Where the HUD block sits on the glasses display (3x3 grid). */
@Serializable
enum class HudPosition(val label: String, val row: Int, val col: Int) {
    TopLeft("Top left", 0, 0), TopCenter("Top", 0, 1), TopRight("Top right", 0, 2),
    CenterLeft("Left", 1, 0), Center("Centre", 1, 1), CenterRight("Right", 1, 2),
    BottomLeft("Bottom left", 2, 0), BottomCenter("Bottom", 2, 1), BottomRight("Bottom right", 2, 2),
}

/** Individually switchable HUD elements. */
@Serializable
enum class HudItem(val label: String) {
    WorkoutType("Workout type"),
    Timer("Timer"),
    HeartRate("Heart rate"),
    Calories("Calories"),
    HeartTrend("Heart-rate graph"),
    Steps("Steps"),
    Distance("Distance"),
    Speed("Speed"),
    Music("Now playing"),
    StatusBar("Status icons"),
}

/** Chosen in the phone app (Settings → Glasses display) and sent to the glasses with every frame. */
@Serializable
data class HudSettings(
    /** Fraction of the display the HUD block occupies, 0.3..1.0. */
    val scale: Float = 0.4f,
    val position: HudPosition = HudPosition.BottomCenter,
    val items: Set<HudItem> = HudItem.entries.toSet(),
)
