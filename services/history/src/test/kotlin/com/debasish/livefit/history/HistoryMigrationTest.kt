package com.debasish.livefit.history

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Spec §6: v1 history (commit b1f1131) survives MIGRATION_1_2; the v2 schema JSON is committed. */
@RunWith(RobolectricTestRunner::class)
class HistoryMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-test.db"

    @After fun cleanUp() { context.deleteDatabase(name) }

    @Test fun version1HistorySurvivesTheRoutePointMigration() {
        val v1 = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) = V1_DDL.forEach(db::execSQL)
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build(),
        )
        v1.writableDatabase.apply {
            execSQL("INSERT INTO session (id, summaryJson, status, startMs, createdAtMs, endedAtMs, endReason) VALUES ('s1', NULL, 'Active', 1000, 1000, NULL, NULL)")
            execSQL("INSERT INTO delta (sessionId, seq, json) VALUES ('s1', 0, '{}')")
            execSQL("INSERT INTO sample (sessionId, tMs, hr, steps, distanceKm, kcal, speedKmh, provenance) VALUES ('s1', 1000, 120, 10, 0.01, 1.0, NULL, 'Fake')")
        }
        v1.close()

        // Opening runs MIGRATION_1_2 and validates the result against the v2 entities (Room throws on any mismatch).
        val db = Room.databaseBuilder(context, HistoryDatabase::class.java, name).addMigrations(HistoryDatabase.MIGRATION_1_2).allowMainThreadQueries().build()
        try {
            val sql = db.openHelper.writableDatabase
            fun count(table: String) = sql.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }
            assertEquals(1, count("session"))
            assertEquals(1, count("delta"))
            assertEquals(1, count("sample"))
            assertEquals(0, count("route_point"))
            assertEquals(2, sql.version)
        } finally {
            db.close()
        }
    }

    @Test fun theV2SchemaIsExportedAndCommitted() =
        assertTrue(File("schemas/com.debasish.livefit.history.HistoryDatabase/2.json").isFile)

    private companion object {
        /** Room 2.6.1's DDL for the v1 entities (identical to their createSql in the exported 2.json). */
        val V1_DDL = listOf(
            "CREATE TABLE IF NOT EXISTS `session` (`id` TEXT NOT NULL, `summaryJson` TEXT, `status` TEXT NOT NULL, `startMs` INTEGER NOT NULL, `createdAtMs` INTEGER NOT NULL, `endedAtMs` INTEGER, `endReason` TEXT, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `delta` (`sessionId` TEXT NOT NULL, `seq` INTEGER NOT NULL, `json` TEXT NOT NULL, PRIMARY KEY(`sessionId`, `seq`))",
            "CREATE TABLE IF NOT EXISTS `sample` (`sessionId` TEXT NOT NULL, `tMs` INTEGER NOT NULL, `hr` INTEGER, `steps` INTEGER NOT NULL, `distanceKm` REAL NOT NULL, `kcal` REAL NOT NULL, `speedKmh` REAL, `provenance` TEXT NOT NULL, PRIMARY KEY(`sessionId`, `tMs`))",
            "CREATE INDEX IF NOT EXISTS `index_sample_sessionId` ON `sample` (`sessionId`)",
        )
    }
}
