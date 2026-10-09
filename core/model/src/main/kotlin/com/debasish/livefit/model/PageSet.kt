package com.debasish.livefit.model

/** The shared page set (spec §3): which pages exist right now, cycling, and the fallback to Workout. */
object PageSet {
    val ORDER: List<HudPage> = HudPage.entries
    val RECORDING: Set<WorkoutPhase> = setOf(WorkoutPhase.Starting, WorkoutPhase.Active, WorkoutPhase.Paused, WorkoutPhase.Syncing)

    /** Map is shown only while a GPS workout records. */
    fun mapEligible(workout: WorkoutSnapshot): Boolean = workout.gps && workout.phase in RECORDING

    /** Enabled pages in cycle order (Workout always), Map only when [mapEligible]. */
    fun available(settings: PageSettings, mapEligible: Boolean): List<HudPage> =
        ORDER.filter { settings.isEnabled(it) && (it != HudPage.Map || mapEligible) }

    /** [steps] pages forward (negative = back), cycling; from a page that is not available: Workout. */
    fun step(current: HudPage, steps: Int, available: List<HudPage>): HudPage {
        val i = available.indexOf(current)
        if (i < 0) return HudPage.Workout
        return available[Math.floorMod(i + steps, available.size)]
    }

    /** The page to show: [current] while available, else Workout (spec §3.3). */
    fun resolve(current: HudPage, available: List<HudPage>): HudPage = if (current in available) current else HudPage.Workout
}
