package com.debasish.livefit.sync

import com.debasish.livefit.model.DeltaAck
import com.debasish.livefit.model.EndReason
import com.debasish.livefit.model.ExerciseOp
import com.debasish.livefit.model.ExerciseRequest
import com.debasish.livefit.model.LocationFix
import com.debasish.livefit.model.Provenance
import com.debasish.livefit.model.Sample
import com.debasish.livefit.model.SessionDelta
import com.debasish.livefit.model.SessionEvent
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.services.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WatchLocationTest {
    private val live = Provenance.Live("galaxy-watch/health-services")
    private fun tmp(): File = Files.createTempDirectory("wl").toFile()
    private fun fix(t: Long, northM: Double = 0.0) = LocationFix(12.9716 + northM / 111_195.0, 77.5946, 5f, 10f, t)

    private class Backend(var location: Boolean = true) : ExerciseBackend {
        val calls = mutableListOf<String>()
        override val updates = MutableSharedFlow<BackendUpdate>(extraBufferCapacity = 16)
        override fun missingPermissions() = emptyList<String>()
        override fun locationGranted() = location
        override suspend fun otherAppTracking(): String? = null
        override suspend fun start(type: WorkoutType, useGps: Boolean): Boolean { calls += "start:$useGps"; return true }
        override suspend fun pause() = true
        override suspend fun resume() = true
        override suspend fun end(): Boolean { updates.emit(BackendUpdate.Ended(EndReason.User)); return true }
        override suspend fun reattach(last: Sample?): Boolean? = true
    }

    private class Rig(val controller: WatchExerciseController, val recorder: WatchSessionRecorder, val routes: WatchRouteFile)

    private fun TestScope.rig(root: File, backend: Backend, sent: MutableList<SessionDelta>): Rig {
        val routes = WatchRouteFile(File(root, "routes"))
        val rec = WatchSessionRecorder(File(root, "buffer"), live, backgroundScope, send = { sent += it }, onFinalAcked = routes::markAcked)
        val c = WatchExerciseController(
            backgroundScope, backend, rec, Clock { testScheduler.currentTime }, sendResult = {}, sendState = {},
            gpsPrefs = GpsPreferences(File(root, "gps.json")), routes = routes,
        )
        return Rig(c, rec, routes)
    }

    private fun start(gps: Boolean, type: WorkoutType = WorkoutType.Run) =
        ExerciseRequest(requestId = "r", sessionId = "s", op = ExerciseOp.Start(type, gps = gps))

    @Test fun recorderPutsFixesInDeltasAndGpsInStarted() = runTest {
        val sent = mutableListOf<SessionDelta>()
        val rec = WatchSessionRecorder(tmp(), live, backgroundScope, send = { sent += it })
        rec.begin("s", WorkoutType.Run, 1_000, gps = true)
        rec.locations(listOf(fix(2_000), fix(3_000, 10.0)))
        rec.locations(emptyList())
        assertTrue((sent[0].events.single() as SessionEvent.Started).gps)
        assertEquals(listOf(2_000L, 3_000L), sent[1].locations.map { it.fixTimeMs })
        assertEquals(2, sent.size, "an empty batch records nothing")
    }

    /** Spec §2.1: GPS needs ACCESS_FINE_LOCATION; denial = Health Services without GPS (FGS health only, Task 11), never a crash. */
    @Test fun gpsStartNeedsLocationPermission() = runTest {
        val sent = mutableListOf<SessionDelta>()
        val b = Backend(location = false)
        val r = rig(tmp(), b, sent); runCurrent()
        r.controller.handle(start(gps = true))
        assertEquals(listOf("start:false"), b.calls, "Health Services runs without GPS")
        assertTrue((sent[0].events.single() as SessionEvent.Started).gps, "still a GPS workout: Map page + phone fallback cover it")
        assertTrue(r.controller.activeGps)
    }

    @Test fun locationUpdatesAreRecordedAndAppendedToTheRouteFile() = runTest {
        val sent = mutableListOf<SessionDelta>()
        val b = Backend()
        val r = rig(tmp(), b, sent); runCurrent()
        r.controller.handle(start(gps = true))
        assertTrue(r.controller.activeGps)
        b.updates.emit(BackendUpdate.Locations(listOf(fix(5_000), fix(6_000, 8.0)))); runCurrent()
        assertEquals(2, sent.last().locations.size)
        assertEquals("s", r.routes.route.value!!.sessionId)
        assertEquals(listOf(5_000L, 6_000L), r.routes.route.value!!.fixes.map { it.fixTimeMs })
    }

    /** Spec §2.6 / §8: acknowledged deltas are deleted, but the route (and its start) reloads after watch process death. */
    @Test fun routeSurvivesAckedDeltaDeletionAndProcessDeath() = runTest {
        val sent = mutableListOf<SessionDelta>()
        val b = Backend(); val root = tmp()
        val r = rig(root, b, sent); runCurrent()
        r.controller.handle(start(gps = true, type = WorkoutType.Walk))
        b.updates.emit(BackendUpdate.Locations(listOf(fix(5_000), fix(6_000, 8.0)))); runCurrent()
        r.recorder.onAck(DeltaAck(sessionId = "s", seq = sent.last().seq))
        val reborn = WatchRouteFile(File(root, "routes")).apply { open("s") }
        assertEquals(listOf(5_000L, 6_000L), reborn.route.value!!.fixes.map { it.fixTimeMs })
        val rec2 = WatchSessionRecorder(File(root, "buffer"), live, backgroundScope, send = {})
        assertEquals("s", rec2.sessionId, "the running session is still held, so WatchRuntime reopens its route")
    }

    @Test fun routeIsDeletedOnlyWhenFinalizedAndDismissed() {
        val root = tmp()
        val r = WatchRouteFile(root)
        r.append("s", listOf(fix(1_000)))
        r.markAcked("s")
        assertTrue(r.load("s").isNotEmpty(), "summary not dismissed yet")
        r.markDismissed("s")
        assertTrue(r.load("s").isEmpty())
        assertNull(r.route.value)
        r.append("t", listOf(fix(1)))
        r.markDismissed("t")
        assertTrue(r.load("t").isNotEmpty(), "not finalized on the phone yet")
    }

    @Test fun finalAckMarksTheRoute() = runTest {
        val sent = mutableListOf<SessionDelta>()
        val b = Backend(); val root = tmp()
        val r = rig(root, b, sent); runCurrent()
        r.controller.handle(start(gps = true))
        b.updates.emit(BackendUpdate.Locations(listOf(fix(5_000)))); runCurrent()
        r.controller.handle(ExerciseRequest(requestId = "stop", sessionId = "s", op = ExerciseOp.Stop))
        r.recorder.onAck(DeltaAck(sessionId = "s", seq = sent.last().seq))
        assertTrue(File(root, "routes/s/acked").exists())
        r.routes.markDismissed("s")
        assertFalse(File(root, "routes/s").exists())
    }

    @Test fun aTornTailRecordIsIgnored() {
        val root = tmp()
        WatchRouteFile(root).append("s", listOf(fix(1_000), fix(2_000, 5.0)))
        File(root, "s/route.bin").appendBytes(ByteArray(7)) // process died mid-write
        val restored = WatchRouteFile(root).load("s")
        assertEquals(2, restored.size)
        assertEquals(10f, restored[0].bearingDeg)
    }

    /** Review #4: an append after a torn tail must not misalign this and every later record. */
    @Test fun appendAfterATornTailKeepsRecordsAligned() {
        val root = tmp()
        WatchRouteFile(root).append("s", listOf(fix(1_000), fix(2_000, 5.0)))
        File(root, "s/route.bin").appendBytes(ByteArray(7) { 0x55 }) // process died mid-write
        val reopened = WatchRouteFile(root).apply { open("s") }
        reopened.append("s", listOf(fix(3_000, 10.0), fix(4_000, 15.0)))
        assertEquals(4L * WatchRouteFile.RECORD, File(root, "s/route.bin").length(), "tail truncated to the record boundary")
        val back = WatchRouteFile(root).load("s")
        assertEquals(listOf(1_000L, 2_000L, 3_000L, 4_000L), back.map { it.fixTimeMs })
        assertEquals(fix(4_000, 15.0), back.last(), "exact values after the append")
        assertEquals(back, reopened.route.value!!.fixes, "the in-memory route matches the file")
    }

    @Test fun unknownAccuracyRoundTripsAsNull() {
        val r = WatchRouteFile(tmp())
        r.append("s", listOf(LocationFix(1.0, 2.0, null, 7f, 4)))
        val back = r.load("s").single()
        assertNull(back.accuracyM)
        assertEquals(7f, back.bearingDeg)
    }

    @Test fun missingBearingRoundTrips() {
        val r = WatchRouteFile(tmp())
        r.append("s", listOf(LocationFix(1.0, 2.0, 3f, null, 4)))
        assertNull(r.load("s").single().bearingDeg)
    }

    @Test fun sweepDeletesFinishedRoutes() {
        val root = tmp()
        WatchRouteFile(root).apply { append("old", listOf(fix(1))); markAcked("old") }
        File(root, "old/dismissed").writeText("") // both markers, but the process died before deleting
        WatchRouteFile(root).sweep()
        assertFalse(File(root, "old").exists())
    }
}
