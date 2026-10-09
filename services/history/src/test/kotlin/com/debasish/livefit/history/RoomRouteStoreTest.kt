package com.debasish.livefit.history

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.debasish.livefit.model.FixSource
import com.debasish.livefit.model.LocationFix
import com.debasish.livefit.model.Provenance
import com.debasish.livefit.model.RouteFix
import com.debasish.livefit.model.SessionDelta
import com.debasish.livefit.model.SessionStatus
import com.debasish.livefit.model.SessionSummary
import com.debasish.livefit.model.WorkoutType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomRouteStoreTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private fun store() = RoomSessionStore(HistoryDatabase.create(ctx, inMemory = true))
    private fun row(src: FixSource, device: Long, phone: Long?, northM: Double = 0.0, received: Long = 0) =
        RouteFix(src, 12.9716 + northM / 111_195.0, 77.5946, 5f, deviceTimeMs = device, phoneTimeMs = phone, receivedAtMs = received, bearingDeg = 90f)

    @Test fun returnsRowsInPhoneTimeOrderWithUncalibratedRowsLast() = runTest {
        val s = store()
        s.storeRouteFixes("s", listOf(row(FixSource.Watch, 10_000, 5_000), row(FixSource.Phone, 1_000, 1_000), row(FixSource.Watch, 2_000, -3_000), row(FixSource.Watch, 900, null)))
        val back = s.routeFixes("s")
        assertEquals(listOf(-3_000L, 1_000L, 5_000L, null), back.map { it.phoneTimeMs })
        assertEquals(listOf(2_000L, 1_000L, 10_000L, 900L), back.map { it.deviceTimeMs })
        assertEquals(90f, back.first().bearingDeg!!, 0f)
    }

    /** Spec §2.2: unique key (sessionId, source, device fix time) — a replay re-mapped with a newer offset is not a second row. */
    @Test fun deviceTimeIdentityMakesReplaysIdempotent() = runTest {
        val s = store()
        s.storeRouteFixes("s", listOf(row(FixSource.Watch, 10_000, null)))
        s.storeRouteFixes("s", listOf(row(FixSource.Watch, 10_000, 5_300)))
        assertEquals(1, s.routeFixes("s").size)
        assertNull("insert never overwrites; normalizeWatchTimes does", s.routeFixes("s").single().phoneTimeMs)
    }

    /** Review #2: calibration rewrites the phone time of every watch row (null or older offset) without touching identity. */
    @Test fun normalizeRewritesWatchPhoneTimesOnly() = runTest {
        val s = store()
        s.storeRouteFixes("s", listOf(row(FixSource.Watch, 10_000, null), row(FixSource.Watch, 11_000, 9_000), row(FixSource.Phone, 7_000, 7_000)))
        s.storeRouteFixes("t", listOf(row(FixSource.Watch, 10_000, null)))
        s.normalizeWatchTimes("s", watchOffsetMs = 3_000)
        val back = s.routeFixes("s")
        assertEquals(listOf(10_000L to 7_000L, 7_000L to 7_000L, 11_000L to 8_000L), back.map { it.deviceTimeMs to it.phoneTimeMs })
        assertEquals("watch first on the 7 s tie; the phone row is untouched", listOf(FixSource.Watch, FixSource.Phone, FixSource.Watch), back.map { it.source })
        assertNull("another session is untouched", s.routeFixes("t").single().phoneTimeMs)
    }

    /** Review r2 #2: the first receipt time is durable — a replay or rebuild cannot move it, so a future fix stays rejected. */
    @Test fun receiptTimeIsKeptFromTheFirstInsert() = runTest {
        val s = store()
        s.storeRouteFixes("s", listOf(row(FixSource.Watch, 900_000, 900_000, received = 700_000)))
        s.storeRouteFixes("s", listOf(row(FixSource.Watch, 900_000, 900_000, received = 1_000_000)))
        s.normalizeWatchTimes("s", watchOffsetMs = 0)
        val back = s.routeFixes("s").single()
        assertEquals(700_000L, back.receivedAtMs)
        assertNull("still rejected after normalization and later reads", back.point())
        assertNull(back.historyPoint())
        s.storeRouteFixes("s", listOf(row(FixSource.Watch, 900_000, 900_000, received = 650_000)))
        assertEquals("an earlier receipt (e.g. the delta path) lowers it; a later one never raises it", 650_000L, s.routeFixes("s").single().receivedAtMs)
    }

    @Test fun sameTimeDifferentSourceKeepsBothWatchFirst() = runTest {
        val s = store()
        s.storeRouteFixes("s", listOf(row(FixSource.Phone, 1_000, 1_000), row(FixSource.Watch, 1_000, 1_000)))
        assertEquals(listOf(FixSource.Watch, FixSource.Phone), s.routeFixes("s").map { it.source })
    }

    /** Review #1: a stored (so acked) delta already has its route rows — nothing can be lost between ack and route insert. */
    @Test fun storingADeltaStoresItsRouteRowsInTheSameTransaction() = runTest {
        val s = store()
        val fixes = listOf(
            LocationFix(12.9716, 77.5946, 4f, 10f, 5_000),
            LocationFix(12.9717, 77.5946, 50f, null, 6_000),   // inaccurate: no row
            LocationFix(12.9718, 77.5946, null, null, 7_000),  // unknown accuracy: no row
            LocationFix(12.9719, 77.5946, 6f, null, 8_000),
        )
        val s2 = RoomSessionStore(HistoryDatabase.create(ctx, inMemory = true), now = { 9_000 })
        s2.storeDelta(SessionDelta(sessionId = "s", seq = 0, locations = fixes, provenance = Provenance.Fake))
        assertTrue("receipt stamped by the store's clock", s2.routeFixes("s").all { it.receivedAtMs == 9_000L })
        s.storeDelta(SessionDelta(sessionId = "s", seq = 0, locations = fixes, provenance = Provenance.Fake))
        val back = s.routeFixes("s")
        assertEquals(listOf(5_000L, 8_000L), back.map { it.deviceTimeMs })
        assertTrue(back.all { it.source == FixSource.Watch && it.phoneTimeMs == null })
        s.storeDelta(SessionDelta(sessionId = "s", seq = 0, locations = fixes, provenance = Provenance.Fake)) // resend
        assertEquals(2, s.routeFixes("s").size)
    }

    /** A Discarded session's tombstone also blocks late route rows (from RouteHub or from a late delta). */
    @Test fun discardDeletesTheRouteAndTheTombstoneBlocksLateRows() = runTest {
        val s = store()
        s.storeDelta(SessionDelta(sessionId = "s", seq = 0, locations = listOf(LocationFix(1.0, 2.0, 3f, null, 4)), provenance = Provenance.Fake))
        s.discard("s")
        assertTrue(s.routeFixes("s").isEmpty())
        s.storeRouteFixes("s", listOf(row(FixSource.Watch, 1, 1)))
        s.storeDelta(SessionDelta(sessionId = "s", seq = 1, locations = listOf(LocationFix(1.0, 2.0, 3f, null, 5)), provenance = Provenance.Fake))
        assertTrue(s.routeFixes("s").isEmpty())
    }

    @Test fun clearHistoryDeletesFinishedRoutesOnlyAndKeepsThemDeleted() = runTest {
        val s = store()
        s.storeDelta(SessionDelta(sessionId = "done", seq = 0, provenance = Provenance.Fake))
        s.finalize(SessionSummary(id = "done", type = WorkoutType.Run, startMs = 0, activeMs = 1, provenance = Provenance.Fake, status = SessionStatus.Complete))
        s.storeRouteFixes("done", listOf(row(FixSource.Watch, 1, 1)))
        s.storeRouteFixes("open", listOf(row(FixSource.Watch, 1, 1)))
        s.clearFinished()
        assertTrue(s.routeFixes("done").isEmpty())
        assertEquals(1, s.routeFixes("open").size)
        s.storeRouteFixes("done", listOf(row(FixSource.Watch, 2, 2))) // a late replay of a cleared session
        assertTrue(s.routeFixes("done").isEmpty())
    }

    /** The owner's phone already has a v1 history database: the upgrade keeps it and adds route_point. */
    @Test fun upgradeFromVersion1KeepsHistoryAndAddsRoutes() = runTest {
        val file = ctx.getDatabasePath("upgrade-test.db").also { it.parentFile?.mkdirs(); it.delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            listOf(
                "CREATE TABLE IF NOT EXISTS `session` (`id` TEXT NOT NULL, `summaryJson` TEXT, `status` TEXT NOT NULL, `startMs` INTEGER NOT NULL, `createdAtMs` INTEGER NOT NULL, `endedAtMs` INTEGER, `endReason` TEXT, PRIMARY KEY(`id`))",
                "CREATE TABLE IF NOT EXISTS `delta` (`sessionId` TEXT NOT NULL, `seq` INTEGER NOT NULL, `json` TEXT NOT NULL, PRIMARY KEY(`sessionId`, `seq`))",
                "CREATE TABLE IF NOT EXISTS `sample` (`sessionId` TEXT NOT NULL, `tMs` INTEGER NOT NULL, `hr` INTEGER, `steps` INTEGER NOT NULL, `distanceKm` REAL NOT NULL, `kcal` REAL NOT NULL, `speedKmh` REAL, `provenance` TEXT NOT NULL, PRIMARY KEY(`sessionId`, `tMs`))",
                "CREATE INDEX IF NOT EXISTS `index_sample_sessionId` ON `sample` (`sessionId`)",
                "INSERT INTO session VALUES ('old', NULL, 'Active', 0, 0, NULL, NULL)",
                "INSERT INTO delta VALUES ('old', 0, '{}')",
            ).forEach(db::execSQL)
            db.version = 1
        }
        val room = Room.databaseBuilder(ctx, HistoryDatabase::class.java, file.absolutePath)
            .addMigrations(HistoryDatabase.MIGRATION_1_2).allowMainThreadQueries().build()
        val s = RoomSessionStore(room)
        assertEquals(listOf("old"), s.openSessionIds())
        s.storeRouteFixes("old", listOf(row(FixSource.Watch, 1, null)))
        assertNull(s.routeFixes("old").single().phoneTimeMs)
        room.close()
    }
}
