package com.debasish.livefit.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [SessionEntity::class, DeltaEntity::class, SampleEntity::class, RoutePointEntity::class], version = 2, exportSchema = true)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun dao(): HistoryDao

    companion object {
        /** v2: route_point (spec §2.2), phoneTimeMs nullable (review #2). Existing history is kept. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `route_point` (`sessionId` TEXT NOT NULL, `source` TEXT NOT NULL, `fixTimeMs` INTEGER NOT NULL, " +
                        "`phoneTimeMs` INTEGER, `receivedAtMs` INTEGER NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL, `accuracyM` REAL NOT NULL, `bearingDeg` REAL, " +
                        "PRIMARY KEY(`sessionId`, `source`, `fixTimeMs`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_route_point_sessionId` ON `route_point` (`sessionId`)")
            }
        }

        fun create(context: Context, inMemory: Boolean = false): HistoryDatabase =
            (if (inMemory) Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java).allowMainThreadQueries()
            else Room.databaseBuilder(context, HistoryDatabase::class.java, "livefit-history.db"))
                .addMigrations(MIGRATION_1_2).build()

        @Volatile private var instance: HistoryDatabase? = null

        /** One Room instance per process (two instances on one file break change notifications). */
        fun shared(context: Context): HistoryDatabase =
            instance ?: synchronized(this) { instance ?: create(context.applicationContext).also { instance = it } }
    }
}
